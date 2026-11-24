package com.wechatdigest.app

import com.wechatdigest.core.attachment.AttachmentContentExtractor
import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.GroupConfig
import com.wechatdigest.core.model.SyncCheckpoint
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.GroupConfigStore
import com.wechatdigest.storage.SyncCheckpointStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Result of executing an end-to-end monitored chatroom sync pass. */
sealed class SyncResult {
    /** Sync completed across monitored rooms, with optional per-room error summaries. */
    data class Success(
        val newEventsCount: Int,
        val totalScannedMessages: Int,
        val roomErrors: List<String> = emptyList()
    ) : SyncResult()

    /** Sync could not run or all rooms failed before scanning any messages. */
    data class Failure(val message: String) : SyncResult()
}

/** Progress snapshot emitted while [SyncService.syncNow] scans and extracts notices across rooms. */
data class SyncProgress(
    val roomIndex: Int,
    val totalRooms: Int,
    val roomName: String,
    val stageLabel: String,
    val newEventsSoFar: Int
)

/**
 * Coordinates incremental message reads from WeChat SQLite shards, deterministic sender allowlists,
 * static prefiltering, multi-pass LLM extraction, card persistence, and sync checkpoint advancement.
 */
class SyncService(
    private val eventCardStore: EventCardStore,
    private val groupConfigStore: GroupConfigStore? = null,
    val syncCheckpointStore: SyncCheckpointStore? = null,
    private val llmClientFactory: (LlmConfig) -> LlmClient = { KtorLlmClient(it) }
) {

    private companion object {
        const val MIN_MESSAGES_PER_SYNC = 1
        const val MAX_MESSAGES_PER_SYNC = 500
        const val MAX_ROOM_LABEL_CHARS = 48
        const val MAX_SYNC_FAILURE_MSG_CHARS = 320
    }

    /**
     * Scans all configured chatrooms in [settings] since their last saved checkpoints and persists newly
     * extracted event cards incrementally as each card finishes, advancing checkpoints per room only on
     * clean completion.
     *
     * @param onCardPersisted Optional callback invoked sequentially (on [Dispatchers.IO]) whenever a newly
     *   synthesized card is committed cleanly to [eventCardStore] (duplicates suppressed via `dedupe_key`
     *   do not trigger it). Exceptions inside UI callbacks are logged without rolling back persisted cards
     *   or blocking checkpoint advancement.
     * @param onProgress Optional callback invoked sequentially whenever room scanning/extraction progress advances.
     */
    suspend fun syncNow(
        settings: AppSettings,
        onCardPersisted: (suspend (EventCard) -> Unit)? = null,
        onProgress: (suspend (SyncProgress) -> Unit)? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        validateSettings(settings)?.let { return@withContext SyncResult.Failure(it) }

        val dataDir = File(settings.wechatDataDir).canonicalFile
        val keysFile = settings.keysPath.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }?.takeIf { it.isFile }
        val driver = WeChatDatabaseDriver(baseDir = dataDir, customKeysFile = keysFile)
        val contactRepo = WeChatContactRepository(driver)
        val attachmentResolver = WeChatAttachmentResolver(driver)
        val messageRepo = WeChatMessageRepository(driver, contactRepo, attachmentResolver)
        val roomNamesMap = try {
            contactRepo.listAllChatrooms().associate { it.roomId to it.displayName }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println(
                "[SyncService] Failed loading chatroom display names: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            emptyMap()
        }

        var totalScanned = 0
        val totalInsertedCounter = AtomicInteger(0)
        val totalRooms = settings.monitoredRoomIds.size
        val callbackMutex = Mutex()
        val errors = mutableListOf<String>()

        llmClientFactory(settings.llm).use { llmClient ->
            val extractor = EventExtractor(
                llmClient = llmClient,
                maxConcurrency = EventExtractor.resolveConcurrencyForConfig(settings.llm)
            )
            settings.monitoredRoomIds.forEachIndexed { roomIdx, roomId ->
                val roomOutcome = syncRoom(
                    roomId = roomId,
                    roomIndex = roomIdx + 1,
                    totalRooms = totalRooms,
                    settings = settings,
                    messageRepo = messageRepo,
                    extractor = extractor,
                    roomDisplayName = roomNamesMap[roomId].orEmpty(),
                    totalInsertedCounter = totalInsertedCounter,
                    callbackMutex = callbackMutex,
                    onCardPersisted = onCardPersisted,
                    onProgress = onProgress
                )
                totalScanned += roomOutcome.scannedCount
                roomOutcome.errorMessage?.let { errors.add(it) }
            }
        }

        if (totalScanned == 0 && errors.isNotEmpty()) {
            SyncResult.Failure(errors.joinToString("; ").take(MAX_SYNC_FAILURE_MSG_CHARS))
        } else {
            SyncResult.Success(
                newEventsCount = totalInsertedCounter.get(),
                totalScannedMessages = totalScanned,
                roomErrors = errors.toList()
            )
        }
    }

    private data class RoomSyncOutcome(
        val scannedCount: Int = 0,
        val errorMessage: String? = null
    )

    private fun validateSettings(settings: AppSettings): String? {
        if (settings.wechatDataDir.isBlank()) {
            return "WeChat data directory is not configured."
        }
        val dataDir = File(settings.wechatDataDir)
        if (!dataDir.exists() || !dataDir.isDirectory) {
            return "WeChat data directory does not exist."
        }
        if (settings.monitoredRoomIds.isEmpty()) {
            return "No chatrooms selected for monitoring."
        }
        return null
    }

    private suspend fun syncRoom(
        roomId: String,
        roomIndex: Int,
        totalRooms: Int,
        settings: AppSettings,
        messageRepo: WeChatMessageRepository,
        extractor: EventExtractor,
        roomDisplayName: String,
        totalInsertedCounter: AtomicInteger,
        callbackMutex: Mutex,
        onCardPersisted: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (SyncProgress) -> Unit)?
    ): RoomSyncOutcome {
        var scannedInRoom = 0
        val rawRoomLabel = roomDisplayName.ifBlank { roomId }
        val roomLabel = EventDetailItemParser.sanitizeSingleLine(rawRoomLabel, MAX_ROOM_LABEL_CHARS)
            .ifBlank { "Group $roomIndex" }
        return try {
            notifyProgressSafely(
                callbackMutex,
                onProgress,
                SyncProgress(roomIndex, totalRooms, roomLabel, "Scanning messages...", totalInsertedCounter.get())
            )
            val batchLimit = settings.messagesPerSync.coerceIn(MIN_MESSAGES_PER_SYNC, MAX_MESSAGES_PER_SYNC)
            val checkpoint = syncCheckpointStore?.getOrThrow(roomId)
            val rawMessages = if (checkpoint != null && checkpoint.lastLocalId > 0L) {
                messageRepo.getMessagesSince(roomId, sinceLocalId = checkpoint.lastLocalId, limit = batchLimit)
            } else {
                messageRepo.getRecentMessages(roomId, limit = batchLimit)
            }
            if (rawMessages.isEmpty()) return RoomSyncOutcome()

            scannedInRoom = rawMessages.size
            val maxLocalId = rawMessages.maxOf { it.localId }
            val groupConfig = groupConfigStore?.getOrThrow(roomId)
            val senderFiltered = applySenderAllowlist(rawMessages, groupConfig)
            val substantive = MessageSanitizer.filterSubstantiveMessages(senderFiltered, settings.prefilter)

            if (substantive.isNotEmpty()) {
                extractAndPersistRoomEvents(
                    substantive = substantive,
                    roomId = roomId,
                    roomIndex = roomIndex,
                    totalRooms = totalRooms,
                    roomDisplayName = roomDisplayName,
                    roomLabel = roomLabel,
                    settings = settings,
                    groupConfig = groupConfig,
                    extractor = extractor,
                    totalInsertedCounter = totalInsertedCounter,
                    callbackMutex = callbackMutex,
                    onCardPersisted = onCardPersisted,
                    onProgress = onProgress
                )
            }

            advanceCheckpoint(roomId, maxLocalId)
            RoomSyncOutcome(scannedCount = scannedInRoom)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val safeRoom = LlmJsonUtils.sanitizeLogMessage(roomId)
            val detail = LlmJsonUtils.sanitizeLogMessage(e.message)
            val err = "Error syncing room $safeRoom: $detail"
            System.err.println("[SyncService] $err")
            RoomSyncOutcome(scannedCount = scannedInRoom, errorMessage = err)
        }
    }

    private fun applySenderAllowlist(
        messages: List<com.wechatdigest.core.model.WeChatMessage>,
        groupConfig: GroupConfig?
    ): List<com.wechatdigest.core.model.WeChatMessage> {
        val allowed = groupConfig?.allowedSenderIds
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        return if (allowed.isNotEmpty()) messages.filter { it.senderId in allowed } else messages
    }

    private suspend fun extractAndPersistRoomEvents(
        substantive: List<com.wechatdigest.core.model.WeChatMessage>,
        roomId: String,
        roomIndex: Int,
        totalRooms: Int,
        roomDisplayName: String,
        roomLabel: String,
        settings: AppSettings,
        groupConfig: GroupConfig?,
        extractor: EventExtractor,
        totalInsertedCounter: AtomicInteger,
        callbackMutex: Mutex,
        onCardPersisted: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (SyncProgress) -> Unit)?
    ) {
        val effectiveRules = groupConfig?.customPrompt.orEmpty()
        val effectivePersona = groupConfig?.customPersona?.takeIf { it.isNotBlank() } ?: settings.userPersona
        val recentRoomCards = eventCardStore.listRecentByRoom(roomId, limit = 10)
        val preparedMessages = if (settings.parseAttachmentContents) {
            enrichMessagesWithAttachmentPreviews(
                messages = substantive,
                focusHints = extractFocusHints(effectivePersona, effectiveRules)
            )
        } else {
            substantive
        }

        val dbWriteMutex = Mutex()
        extractor.extractEventsResult(
            messages = preparedMessages,
            roomId = roomId,
            userPersona = effectivePersona,
            targetLanguage = settings.targetLanguage,
            customRules = effectiveRules,
            roomDisplayName = roomDisplayName,
            prefilterConfig = settings.prefilter,
            recentRoomCards = recentRoomCards,
            onCardReady = { card ->
                val inserted = dbWriteMutex.withLock {
                    eventCardStore.upsertOrThrow(listOf(card))
                }
                if (inserted > 0) {
                    totalInsertedCounter.addAndGet(inserted)
                    notifyCardPersistedSafely(callbackMutex, onCardPersisted, card)
                }
            },
            onProgress = { stage, _, _ ->
                notifyProgressSafely(
                    callbackMutex,
                    onProgress,
                    SyncProgress(
                        roomIndex = roomIndex,
                        totalRooms = totalRooms,
                        roomName = roomLabel,
                        stageLabel = stage,
                        newEventsSoFar = totalInsertedCounter.get()
                    )
                )
            }
        ).getOrThrow()
    }

    private suspend fun notifyCardPersistedSafely(
        callbackMutex: Mutex,
        onCardPersisted: (suspend (EventCard) -> Unit)?,
        card: EventCard
    ) {
        if (onCardPersisted == null) return
        callbackMutex.withLock {
            try {
                onCardPersisted.invoke(card)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                System.err.println("[SyncService] Card notification error: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            }
        }
    }

    private suspend fun notifyProgressSafely(
        callbackMutex: Mutex,
        onProgress: (suspend (SyncProgress) -> Unit)?,
        progress: SyncProgress
    ) {
        if (onProgress == null) return
        callbackMutex.withLock {
            try {
                onProgress.invoke(progress)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                System.err.println("[SyncService] Progress callback error: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            }
        }
    }

    private fun enrichMessagesWithAttachmentPreviews(
        messages: List<WeChatMessage>,
        focusHints: Set<String>
    ): List<WeChatMessage> {
        return messages.map { msg ->
            if (msg.attachments.isEmpty()) return@map msg
            val previews = buildMap<String, String> {
                for (att in msg.attachments) {
                    val path = att.localPath?.takeIf { it.isNotBlank() } ?: continue
                    val file = File(path)
                    if (!file.isFile) continue
                    val extracted = AttachmentContentExtractor.extractBoundedPreview(file, focusHints)
                    if (!extracted.isNullOrBlank()) {
                        put(att.previewKey(), extracted)
                    }
                }
            }
            if (previews.isEmpty()) msg else msg.copy(attachmentPreviews = previews)
        }
    }

    private fun extractFocusHints(persona: String, customRules: String): Set<String> {
        return "$persona $customRules"
            .split(Regex("[\\s,.;:!?/\\\\|()\\[\\]{}\"']+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(12)
            .toSet()
    }

    private fun advanceCheckpoint(roomId: String, maxLocalId: Long) {
        val now = System.currentTimeMillis() / 1000
        syncCheckpointStore?.saveOrThrow(
            SyncCheckpoint(
                roomId = roomId,
                lastLocalId = maxLocalId,
                lastSyncTimestamp = now,
                updatedAt = now
            )
        )
    }
}
