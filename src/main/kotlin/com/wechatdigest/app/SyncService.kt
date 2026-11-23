package com.wechatdigest.app

import com.wechatdigest.core.attachment.AttachmentContentExtractor
import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmJsonUtils
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
import kotlinx.coroutines.withContext
import java.io.File

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
    }

    /**
     * Scans all configured chatrooms in [settings] since their last saved checkpoints and persists newly
     * extracted event cards, advancing checkpoints per room only on clean completion.
     */
    suspend fun syncNow(settings: AppSettings): SyncResult = withContext(Dispatchers.IO) {
        validateSettings(settings)?.let { return@withContext SyncResult.Failure(it) }

        val dataDir = File(settings.wechatDataDir)
        val keysFile = if (settings.keysPath.isNotBlank()) File(settings.keysPath) else null
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
        var totalInserted = 0
        val errors = mutableListOf<String>()

        llmClientFactory(settings.llm).use { llmClient ->
            val extractor = EventExtractor(
                llmClient = llmClient,
                maxConcurrency = EventExtractor.resolveConcurrencyForConfig(settings.llm)
            )
            for (roomId in settings.monitoredRoomIds) {
                val roomOutcome = syncRoom(roomId, settings, messageRepo, extractor, roomNamesMap[roomId].orEmpty())
                totalScanned += roomOutcome.scannedCount
                totalInserted += roomOutcome.insertedCount
                roomOutcome.errorMessage?.let { errors.add(it) }
            }
        }

        if (totalScanned == 0 && errors.isNotEmpty()) {
            SyncResult.Failure(errors.joinToString("; "))
        } else {
            SyncResult.Success(
                newEventsCount = totalInserted,
                totalScannedMessages = totalScanned,
                roomErrors = errors.toList()
            )
        }
    }

    private data class RoomSyncOutcome(
        val scannedCount: Int = 0,
        val insertedCount: Int = 0,
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
        settings: AppSettings,
        messageRepo: WeChatMessageRepository,
        extractor: EventExtractor,
        roomDisplayName: String
    ): RoomSyncOutcome {
        var scannedInRoom = 0
        return try {
            val batchLimit = settings.messagesPerSync.coerceIn(MIN_MESSAGES_PER_SYNC, MAX_MESSAGES_PER_SYNC)
            val checkpoint = syncCheckpointStore?.get(roomId)
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

            val insertedCount = if (substantive.isNotEmpty()) {
                extractAndPersistRoomEvents(
                    substantive = substantive,
                    roomId = roomId,
                    roomDisplayName = roomDisplayName,
                    settings = settings,
                    groupConfig = groupConfig,
                    extractor = extractor
                )
            } else {
                0
            }

            advanceCheckpoint(roomId, maxLocalId)
            RoomSyncOutcome(scannedCount = scannedInRoom, insertedCount = insertedCount)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = "Error syncing room $roomId: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            System.err.println("[SyncService] $err")
            RoomSyncOutcome(scannedCount = scannedInRoom, insertedCount = 0, errorMessage = err)
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
        roomDisplayName: String,
        settings: AppSettings,
        groupConfig: GroupConfig?,
        extractor: EventExtractor
    ): Int {
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

        val events = extractor.extractEventsResult(
            messages = preparedMessages,
            roomId = roomId,
            userPersona = effectivePersona,
            targetLanguage = settings.targetLanguage,
            customRules = effectiveRules,
            roomDisplayName = roomDisplayName,
            prefilterConfig = settings.prefilter,
            recentRoomCards = recentRoomCards
        ).getOrThrow()

        return if (events.isNotEmpty()) eventCardStore.upsertOrThrow(events) else 0
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
        syncCheckpointStore?.save(
            SyncCheckpoint(
                roomId = roomId,
                lastLocalId = maxLocalId,
                lastSyncTimestamp = now,
                updatedAt = now
            )
        )
    }
}
