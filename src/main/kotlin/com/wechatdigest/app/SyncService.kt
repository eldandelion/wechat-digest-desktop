package com.wechatdigest.app

import com.wechatdigest.core.attachment.AttachmentContentExtractor
import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCardStatus
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Result of executing an end-to-end monitored chatroom sync pass. */
sealed class SyncResult {
    /** Sync completed across monitored rooms, with optional per-room error summaries and inserted cards. */
    data class Success(
        val newEventsCount: Int,
        val totalScannedMessages: Int,
        val roomErrors: List<String> = emptyList(),
        val insertedCards: List<EventCard> = emptyList()
    ) : SyncResult()

    /** Sync was cancelled cleanly mid-flight before finishing all rooms. */
    data object Cancelled : SyncResult()

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
    private val decryptedCacheDir: File = File(System.getProperty("user.home"), "Library/Application Support/WeChatDigest/decrypted"),
    private val llmClientFactory: (LlmConfig) -> LlmClient = { KtorLlmClient(it) }
) {

    private companion object {
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

        val rawDataDir = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(settings.wechatDataDir)
            ?: return@withContext SyncResult.Failure("WeChat data directory is not configured.")
        if (Files.isSymbolicLink(rawDataDir.toPath())) {
            return@withContext SyncResult.Failure("Refusing to follow symbolic link for WeChat data directory.")
        }
        val dataDir = rawDataDir.canonicalFile
        val rawKeysFile = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(settings.keysPath)
        if (rawKeysFile != null && Files.isSymbolicLink(rawKeysFile.toPath())) {
            return@withContext SyncResult.Failure("Refusing to follow symbolic link for keys file.")
        }
        val keysFile = rawKeysFile?.canonicalFile?.takeIf { it.isFile }
        val driver = WeChatDatabaseDriver(
            baseDir = dataDir,
            customKeysFile = keysFile,
            cacheDir = decryptedCacheDir
        )

        var totalScanned = 0
        val totalInsertedCounter = AtomicInteger(0)
        val persistedCards = java.util.Collections.synchronizedList(mutableListOf<EventCard>())
        val totalRooms = settings.monitoredRoomIds.size
        val callbackMutex = Mutex()
        val errors = mutableListOf<String>()

        WeChatSessionContext(driver).use { session ->
            val contactRepo = WeChatContactRepository(driver, sessionContext = session)
            val attachmentRoot = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(settings.wechatAttachmentRootDir)
            val attachmentResolver = WeChatAttachmentResolver(driver, explicitAttachmentRootDir = attachmentRoot)
            val messageRepo = WeChatMessageRepository(
                driver = driver,
                contactRepository = contactRepo,
                attachmentResolver = attachmentResolver,
                sessionContext = session
            )
            val roomsById = try {
                contactRepo.listAllChatrooms().associateBy { it.roomId }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                System.err.println(
                    "[SyncService] Failed loading chatroom display names: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
                )
                emptyMap()
            }

            val activeFingerprints = java.util.Collections.synchronizedSet(
                eventCardStore.listActiveFingerprints().toMutableSet()
            )
            val roomOwnersMap = try {
                contactRepo.loadRoomOwnersMap()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyMap()
            }
            llmClientFactory(settings.llm).use { llmClient ->
                val extractor = EventExtractor(
                    llmClient = llmClient,
                    maxConcurrency = EventExtractor.resolveConcurrencyForConfig(settings.llm)
                )
                settings.monitoredRoomIds.forEachIndexed { roomIdx, roomId ->
                    val roomMeta = roomsById[roomId]
                    val roomOutcome = syncRoom(
                        roomId = roomId,
                        roomIndex = roomIdx + 1,
                        totalRooms = totalRooms,
                        settings = settings,
                        messageRepo = messageRepo,
                        extractor = extractor,
                        roomDisplayName = roomMeta?.displayName.orEmpty(),
                        roomAnnouncement = roomMeta?.announcement.orEmpty(),
                        roomOwnerId = roomOwnersMap[roomId].orEmpty(),
                        activeFingerprints = activeFingerprints,
                        totalInsertedCounter = totalInsertedCounter,
                        callbackMutex = callbackMutex,
                        onCardPersisted = { card ->
                            persistedCards.add(card)
                            onCardPersisted?.invoke(card)
                        },
                        onProgress = onProgress
                    )
                    totalScanned += roomOutcome.scannedCount
                    roomOutcome.errorMessage?.let { errors.add(it) }
                }
            }
        }

        if (totalScanned == 0 && errors.isNotEmpty()) {
            SyncResult.Failure(errors.joinToString("; ").take(MAX_SYNC_FAILURE_MSG_CHARS))
        } else {
            SyncResult.Success(
                newEventsCount = totalInsertedCounter.get(),
                totalScannedMessages = totalScanned,
                roomErrors = errors.toList(),
                insertedCards = synchronized(persistedCards) { persistedCards.toList() }
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
        val dataDir = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(settings.wechatDataDir)
        if (dataDir == null || !dataDir.exists() || !dataDir.isDirectory) {
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
        roomAnnouncement: String,
        roomOwnerId: String,
        activeFingerprints: MutableSet<String>,
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
            val batchLimit = settings.messagesPerSync.coerceIn(
                WeChatMessageRepository.MIN_MESSAGES_PER_SYNC,
                WeChatMessageRepository.MAX_MESSAGES_PER_SYNC
            )
            val checkpoint = syncCheckpointStore?.getOrThrow(roomId)
            val readResult = messageRepo.getMessagesSinceCheckpoint(
                roomId = roomId,
                checkpoint = checkpoint,
                limit = batchLimit
            )
            val rawMessages = readResult.messages
            if (rawMessages.isEmpty()) return RoomSyncOutcome()

            scannedInRoom = rawMessages.size
            val groupConfig = groupConfigStore?.getOrThrow(roomId)
            val recallCleaned = MessageSanitizer.suppressSystemAndRecalledMessages(rawMessages)
            val senderFiltered = applySenderFilters(recallCleaned, groupConfig)
            val substantive = MessageSanitizer.filterSubstantiveMessages(senderFiltered, settings.prefilter)

            if (substantive.isNotEmpty()) {
                extractAndPersistRoomEvents(
                    substantive = substantive,
                    roomId = roomId,
                    roomIndex = roomIndex,
                    totalRooms = totalRooms,
                    roomDisplayName = roomDisplayName,
                    roomAnnouncement = roomAnnouncement,
                    roomOwnerId = roomOwnerId,
                    roomLabel = roomLabel,
                    settings = settings,
                    groupConfig = groupConfig,
                    extractor = extractor,
                    activeFingerprints = activeFingerprints,
                    totalInsertedCounter = totalInsertedCounter,
                    callbackMutex = callbackMutex,
                    onCardPersisted = onCardPersisted,
                    onProgress = onProgress
                )
            }

            advanceCheckpoint(
                roomId = roomId,
                maxLocalId = readResult.latestLocalId,
                lastShardName = readResult.latestShardName,
                lastCreateTime = readResult.latestCreateTime,
                hasMore = readResult.hasMore
            )
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

    private fun applySenderFilters(
        messages: List<com.wechatdigest.core.model.WeChatMessage>,
        groupConfig: GroupConfig?
    ): List<com.wechatdigest.core.model.WeChatMessage> {
        val allowed = groupConfig?.allowedSenderIds
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val muted = groupConfig?.mutedSenderIds
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val allowFiltered = if (allowed.isNotEmpty()) messages.filter { it.senderId in allowed } else messages
        return if (muted.isNotEmpty()) allowFiltered.filterNot { it.senderId in muted } else allowFiltered
    }

    private suspend fun extractAndPersistRoomEvents(
        substantive: List<com.wechatdigest.core.model.WeChatMessage>,
        roomId: String,
        roomIndex: Int,
        totalRooms: Int,
        roomDisplayName: String,
        roomAnnouncement: String,
        roomOwnerId: String,
        roomLabel: String,
        settings: AppSettings,
        groupConfig: GroupConfig?,
        extractor: EventExtractor,
        activeFingerprints: MutableSet<String>,
        totalInsertedCounter: AtomicInteger,
        callbackMutex: Mutex,
        onCardPersisted: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (SyncProgress) -> Unit)?
    ) {
        val effectiveRules = groupConfig?.customPrompt.orEmpty()
        val effectivePersona = groupConfig?.customPersona?.takeIf { it.isNotBlank() } ?: settings.userPersona
        val recentRoomCards = eventCardStore.listRecentByRoom(roomId, limit = 10)
        val hasScopedPolicy = effectiveRules.isNotBlank() || !groupConfig?.allowedSenderIds.isNullOrEmpty()
        val preparedMessages = if (settings.parseAttachmentContents) {
            enrichMessagesWithAttachmentPreviews(
                messages = substantive,
                focusHints = extractFocusHints(effectivePersona, effectiveRules),
                enableVisualOcr = settings.enableVisualOcr
            )
        } else {
            substantive
        }
        // Do not let unrestricted rooms suppress messages in rooms that define custom rules or allowlists.
        val fingerprintSnapshot = if (hasScopedPolicy) {
            emptySet()
        } else {
            synchronized(activeFingerprints) { activeFingerprints.toSet() }
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
            roomAnnouncement = roomAnnouncement,
            crossRoomFingerprints = fingerprintSnapshot,
            onCardReady = { card ->
                val applyResult = dbWriteMutex.withLock {
                    val outcome = eventCardStore.applyUpdateOrInsertOrThrow(listOf(card), roomOwnerId = roomOwnerId)
                    if (outcome.supersededCardIds.isNotEmpty()) {
                        val refreshedActive = eventCardStore.listActiveFingerprints()
                        synchronized(activeFingerprints) {
                            activeFingerprints.clear()
                            activeFingerprints.addAll(refreshedActive)
                        }
                    }
                    outcome
                }
                if (applyResult.insertedCards.isNotEmpty()) {
                    for (persisted in applyResult.insertedCards) {
                        if (persisted.contentFingerprint.isNotBlank() && persisted.status != EventCardStatus.CANCELLED) {
                            activeFingerprints.add(persisted.contentFingerprint)
                        }
                        totalInsertedCounter.incrementAndGet()
                        notifyCardPersistedSafely(callbackMutex, onCardPersisted, persisted)
                    }
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

    internal fun enrichMessagesWithAttachmentPreviews(
        messages: List<WeChatMessage>,
        focusHints: Set<String>,
        enableVisualOcr: Boolean = true,
        ocrService: com.wechatdigest.core.attachment.LocalVisionOcrService? = AttachmentContentExtractor.defaultOcrService
    ): List<WeChatMessage> {
        return messages.map { msg ->
            if (msg.attachments.isEmpty()) return@map msg
            val previews = linkedMapOf<String, String>()
            val updatedAttachments = msg.attachments.map { att ->
                val path = att.localPath?.takeIf { it.isNotBlank() } ?: return@map att
                val file = File(path)
                if (!file.isFile) return@map att
                val outcome = AttachmentContentExtractor.extractWithStatus(
                    file = file,
                    focusHints = focusHints,
                    enableVisualOcr = enableVisualOcr,
                    ocrService = ocrService,
                    extensionOverride = att.fileExtension
                )
                if (!outcome.preview.isNullOrBlank()) {
                    previews[att.previewKey()] = outcome.preview
                }
                if (outcome.analyzedByAi && !att.wasAnalyzedByAi) {
                    att.copy(wasAnalyzedByAi = true)
                } else {
                    att
                }
            }
            if (previews.isEmpty() && updatedAttachments == msg.attachments) {
                msg
            } else {
                msg.copy(attachments = updatedAttachments, attachmentPreviews = previews)
            }
        }
    }

    internal fun extractFocusHints(persona: String, customRules: String): Set<String> {
        return "$persona $customRules"
            .split(Regex("[\\s,.;:!?/\\\\|()\\[\\]{}\"']+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(12)
            .toSet()
    }

    private fun advanceCheckpoint(
        roomId: String,
        maxLocalId: Long,
        lastShardName: String = "",
        lastCreateTime: Long = 0L,
        hasMore: Boolean = false
    ) {
        val now = System.currentTimeMillis() / 1000
        syncCheckpointStore?.saveOrThrow(
            SyncCheckpoint(
                roomId = roomId,
                lastLocalId = maxLocalId,
                lastSyncTimestamp = now,
                updatedAt = now,
                lastShardName = lastShardName,
                lastCreateTime = lastCreateTime,
                hasMore = hasMore
            )
        )
    }
}
