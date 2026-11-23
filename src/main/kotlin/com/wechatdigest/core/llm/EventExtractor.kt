package com.wechatdigest.core.llm

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.LlmExtractionResponse
import com.wechatdigest.core.model.LlmTriageResponse
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Orchestrates the 3-pass AI extraction pipeline (Triage Chunker -> Batched Rule & Substance Gatekeeper ->
 * Grounded Summarizer) plus a deterministic script-compliance gate with conditional [CardTranslator] repair.
 */
class EventExtractor(
    private val llmClient: LlmClient,
    triageWindowSize: Int = DEFAULT_TRIAGE_WINDOW_SIZE,
    triageMaxWindowSize: Int = DEFAULT_TRIAGE_MAX_WINDOW_SIZE,
    maxConcurrency: Int = resolveDefaultConcurrency(llmClient),
    gatekeeperBatchSize: Int = DEFAULT_GATEKEEPER_BATCH_SIZE,
    gatekeeperBatchMaxChars: Int = DEFAULT_GATEKEEPER_BATCH_MAX_CHARS,
    zoneId: ZoneId = ZoneId.systemDefault(),
    private val observer: ExtractionObserver = ExtractionObserver.NoOp
) {

    companion object {
        /** Default soft message count per Pass 1 triage window. */
        const val DEFAULT_TRIAGE_WINDOW_SIZE = 15

        /** Default hard message count cap per Pass 1 triage window for same-sender bursts. */
        const val DEFAULT_TRIAGE_MAX_WINDOW_SIZE = 20

        /** Default maximum total characters in a single Pass 1 triage window. */
        const val DEFAULT_TRIAGE_WINDOW_MAX_CHARS = 24_000

        /** Maximum character length retained per message when building LLM prompts. */
        const val MAX_SINGLE_MESSAGE_CHARS = 8_000

        /** Maximum concurrent in-flight LLM requests permitted for cloud OpenAI-compatible endpoints. */
        const val DEFAULT_CLOUD_CONCURRENCY = 4

        /** Maximum concurrent in-flight LLM requests permitted for local Ollama or loopback endpoints. */
        const val DEFAULT_LOCAL_CONCURRENCY = 1

        /** Default maximum number of candidate chunks evaluated in one batched Pass 2 request. */
        const val DEFAULT_GATEKEEPER_BATCH_SIZE = 5

        /** Default character budget per batched Pass 2 Gatekeeper prompt. */
        const val DEFAULT_GATEKEEPER_BATCH_MAX_CHARS = 6_000

        private const val TIGHT_BURST_GAP_SECONDS = 300L

        private val ipv4LiteralRegex = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        private val ipv4ShorthandOrIntRegex = Regex("^(?:\\d{1,3}\\.\\d{1,9}|\\d+)$")

        /**
         * Returns a conservative concurrency cap for [config]: `1` for local Ollama or loopback/private
         * URLs (to prevent queue timeouts) and `4` for remote cloud OpenAI-compatible APIs.
         */
        fun resolveConcurrencyForConfig(config: LlmConfig): Int {
            if (config.providerType == LlmProviderType.OLLAMA || isLocalEndpoint(config.endpointUrl)) {
                return DEFAULT_LOCAL_CONCURRENCY
            }
            return DEFAULT_CLOUD_CONCURRENCY
        }

        internal fun resolveDefaultConcurrency(llmClient: LlmClient): Int {
            return when (llmClient) {
                is ConcurrencyAwareLlmClient -> llmClient.recommendedConcurrency
                is KtorLlmClient -> resolveConcurrencyForConfig(llmClient.config)
                else -> DEFAULT_CLOUD_CONCURRENCY
            }
        }

        internal fun isLocalEndpoint(endpointUrl: String): Boolean {
            val trimmed = endpointUrl.trim()
            if (trimmed.isEmpty()) return false
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val rawHost = runCatching { URI(withScheme).host }.getOrNull() ?: return false
            val host = rawHost.trimEnd('.').removePrefix("[").removeSuffix("]").substringBefore('%').lowercase(Locale.ROOT)
            if (host.isEmpty()) return false
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                host == "host.docker.internal" || "." !in host && ":" !in host
            ) {
                return true
            }
            if (host.startsWith("127.") || ipv4ShorthandOrIntRegex.matches(host) && !ipv4LiteralRegex.matches(host)) {
                return true
            }
            return isLocalOrPrivateIpLiteral(host)
        }

        private fun isLocalOrPrivateIpLiteral(host: String): Boolean {
            val looksLikeIp = ipv4LiteralRegex.matches(host) || ':' in host
            if (!looksLikeIp) return false
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
        }
    }

    private class StageFailureException(val failureError: Throwable) : Exception(failureError)

    internal data class NoticeChunk(
        val topic: String,
        val messages: List<WeChatMessage>,
        val categoryHint: String = "NOTICE",
        val reason: String = ""
    )

    private val effectiveWindowSize = triageWindowSize.coerceAtLeast(1)
    private val effectiveMaxWindowSize = triageMaxWindowSize.coerceAtLeast(effectiveWindowSize)
    private val effectiveMaxConcurrency = maxConcurrency.coerceAtLeast(1)
    private val effectiveBatchSize = gatekeeperBatchSize.coerceAtLeast(1)
    private val effectiveBatchMaxChars = gatekeeperBatchMaxChars.coerceAtLeast(1)

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        .withZone(zoneId)

    /**
     * Extracts structured, translated event cards from [messages] for [roomId], returning an empty list on failure.
     */
    suspend fun extractEvents(
        messages: List<WeChatMessage>,
        roomId: String,
        userPersona: String = "",
        targetLanguage: String = "English",
        customRules: String = "",
        roomDisplayName: String = "",
        prefilterConfig: PrefilterConfig = PrefilterConfig(),
        recentRoomCards: List<EventCard> = emptyList()
    ): List<EventCard> {
        return extractEventsResult(
            messages = messages,
            roomId = roomId,
            userPersona = userPersona,
            targetLanguage = targetLanguage,
            customRules = customRules,
            roomDisplayName = roomDisplayName,
            prefilterConfig = prefilterConfig,
            recentRoomCards = recentRoomCards
        ).getOrElse { emptyList() }
    }

    /**
     * Extracts event cards using Pass 1 triage, Pass 2 batched gatekeeper, Pass 3 synthesis, and
     * conditional target-language repair, preserving failures so callers avoid advancing sync checkpoints.
     */
    suspend fun extractEventsResult(
        messages: List<WeChatMessage>,
        roomId: String,
        userPersona: String = "",
        targetLanguage: String = "English",
        customRules: String = "",
        roomDisplayName: String = "",
        prefilterConfig: PrefilterConfig = PrefilterConfig(),
        recentRoomCards: List<EventCard> = emptyList()
    ): Result<List<EventCard>> {
        val substantive = MessageSanitizer.filterSubstantiveMessages(messages, prefilterConfig)
        if (observer !== ExtractionObserver.NoOp) {
            observer.onPrefilterComplete(messages.size, substantive.size, substantive.map { it.localId })
        }
        if (substantive.isEmpty()) return Result.success(emptyList())

        val semaphore = Semaphore(effectiveMaxConcurrency)
        val discovered = runPass1Triage(substantive, userPersona, targetLanguage, customRules, recentRoomCards, semaphore)
            .getOrElse { return Result.failure(it) }

        val messageIndexById = substantive.withIndex().associate { it.value.localId to it.index }
        val consolidated = consolidateTriageChunks(discovered, recentRoomCards, messageIndexById)
        if (observer !== ExtractionObserver.NoOp) {
            observer.onPass1Consolidated(discovered.size, consolidated.size, consolidated.map { it.topic })
        }
        if (consolidated.isEmpty()) return Result.success(emptyList())

        val verified = runPass2Gatekeeper(consolidated, userPersona, customRules, semaphore)
            .getOrElse { return Result.failure(it) }
        if (verified.isEmpty()) return Result.success(emptyList())

        val synthesized = runPass3Synthesis(verified, roomId, roomDisplayName, userPersona, targetLanguage, customRules, semaphore)
            .getOrElse { return Result.failure(it) }
        return ensureCardsTranslated(synthesized, targetLanguage, semaphore)
    }

    private suspend fun <T, R> mapConcurrent(
        items: List<T>,
        semaphore: Semaphore,
        block: suspend (T) -> Result<R>
    ): Result<List<R>> {
        if (items.isEmpty()) return Result.success(emptyList())
        if (effectiveMaxConcurrency <= 1 || items.size == 1) {
            return mapSequentialWithPermit(items, semaphore, block)
        }
        return try {
            val results = coroutineScope {
                items.map { item -> async { executeWithPermit(semaphore, item, block) } }.awaitAll()
            }
            Result.success(results)
        } catch (e: StageFailureException) {
            currentCoroutineContext().ensureActive()
            if (e.failureError is CancellationException) throw e.failureError
            Result.failure(e.failureError)
        }
    }

    private suspend fun <T, R> mapSequentialWithPermit(
        items: List<T>,
        semaphore: Semaphore,
        block: suspend (T) -> Result<R>
    ): Result<List<R>> {
        val sequential = ArrayList<R>(items.size)
        for (item in items) {
            val value = semaphore.withPermit { block(item) }.getOrElse { err ->
                if (err is CancellationException) throw err
                return Result.failure(err)
            }
            sequential.add(value)
        }
        return Result.success(sequential)
    }

    private suspend fun <T, R> executeWithPermit(
        semaphore: Semaphore,
        item: T,
        block: suspend (T) -> Result<R>
    ): R = semaphore.withPermit {
        block(item).getOrElse { throw StageFailureException(it) }
    }

    private suspend fun runPass1Triage(
        substantiveMessages: List<WeChatMessage>,
        userPersona: String,
        targetLanguage: String,
        customRules: String,
        recentRoomCards: List<EventCard>,
        semaphore: Semaphore
    ): Result<List<NoticeChunk>> {
        val systemPrompt = buildTriageSystemPrompt(userPersona, targetLanguage, customRules, recentRoomCards)
        val windows = splitIntoTriageWindows(substantiveMessages)
        return mapConcurrent(windows, semaphore) { window ->
            val startMs = System.currentTimeMillis()
            val userPrompt = ExtractionPrompts.buildTriageUserPrompt(formatTranscriptForPrompt(window), recentRoomCards)
            val rawJson = queryLlm("Pass 1 triage", systemPrompt, userPrompt)
                .getOrElse { return@mapConcurrent Result.failure(it) }
            var jsonRepaired = false
            var usedLegacyFallback = false
            val parsed = parseTriageResponseDetailed(
                rawJson = rawJson,
                windowMessages = window,
                onRepaired = { jsonRepaired = true },
                onLegacyFallback = { usedLegacyFallback = true }
            )
            if (observer !== ExtractionObserver.NoOp) {
                observer.onPass1WindowResult(
                    windowMessageIds = window.map { it.localId },
                    discoveredChunkCount = parsed.getOrNull()?.size ?: 0,
                    jsonRepaired = jsonRepaired,
                    usedLegacyFallback = usedLegacyFallback,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            }
            parsed
        }.map { perWindow -> perWindow.flatten() }
    }

    private suspend fun runPass2Gatekeeper(
        chunks: List<NoticeChunk>,
        userPersona: String,
        customRules: String,
        semaphore: Semaphore
    ): Result<List<NoticeChunk>> {
        val singlePrompt = buildGatekeeperSystemPrompt(userPersona, customRules)
        val batchedPrompt = ExtractionPrompts.buildBatchedGatekeeperSystemPrompt(userPersona, customRules)
        val batches = splitIntoGatekeeperBatches(chunks)

        return mapConcurrent(batches, semaphore) { batch ->
            val startMs = System.currentTimeMillis()
            val (systemPrompt, userPrompt) = buildGatekeeperPrompts(batch, singlePrompt, batchedPrompt)
            val rawJson = queryLlm("Pass 2 gatekeeper", systemPrompt, userPrompt)
                .getOrElse { return@mapConcurrent Result.failure(it) }
            val evaluation = Pass2Gatekeeper.evaluateBatchVerdictDetailed(batch, rawJson)
            if (observer !== ExtractionObserver.NoOp && evaluation.isSuccess) {
                val detail = evaluation.getOrThrow()
                observer.onPass2BatchResult(
                    verdicts = detail.traces,
                    jsonRepaired = detail.jsonRepaired,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            }
            evaluation.map { it.approvedChunks }
        }.map { approvedBatches -> approvedBatches.flatten() }
    }

    private fun buildGatekeeperPrompts(
        batch: List<NoticeChunk>,
        singlePrompt: String,
        batchedPrompt: String
    ): Pair<String, String> {
        if (batch.size == 1) {
            val single = batch.single()
            return singlePrompt to ExtractionPrompts.buildGatekeeperUserPrompt(
                chunkTopic = single.topic,
                transcript = formatSourceMessagesForPrompt(single.messages)
            )
        }
        val indexed = batch.mapIndexed { idx, chunk ->
            Triple(idx, chunk.topic, formatSourceMessagesForPrompt(chunk.messages))
        }
        return batchedPrompt to ExtractionPrompts.buildBatchedGatekeeperUserPrompt(indexed)
    }

    private suspend fun runPass3Synthesis(
        chunks: List<NoticeChunk>,
        roomId: String,
        roomDisplayName: String,
        userPersona: String,
        targetLanguage: String,
        customRules: String,
        semaphore: Semaphore
    ): Result<List<EventCard>> {
        val systemPrompt = buildSystemPrompt(userPersona, targetLanguage, customRules)
        return mapConcurrent(chunks, semaphore) { chunk ->
            val userPrompt = ExtractionPrompts.buildSynthesisUserPrompt(chunk.topic, formatTranscriptForPrompt(chunk.messages))
            synthesizeChunkWithRetry(systemPrompt, userPrompt, chunk, roomId, roomDisplayName)
        }.map { cards -> cards.filterNotNull() }
    }

    private suspend fun synthesizeChunkWithRetry(
        systemPrompt: String,
        userPrompt: String,
        chunk: NoticeChunk,
        roomId: String,
        roomDisplayName: String
    ): Result<EventCard?> {
        val startMs = System.currentTimeMillis()
        var lastResult: Result<EventCard?> = Result.failure(IllegalStateException("Pass 3 did not execute"))
        var attemptsUsed = 0
        var anyRepaired = false

        repeat(2) { attempt ->
            attemptsUsed = attempt + 1
            val rawResponse = queryLlm("Pass 3 chunk synthesis", systemPrompt, userPrompt)
                .getOrElse { err ->
                    lastResult = Result.failure(err)
                    val retryable = attempt == 0 && err.message.orEmpty().contains("Empty or missing message", ignoreCase = true)
                    if (!retryable) return lastResult
                    return@repeat
                }
            var jsonRepaired = false
            val parsed = parseChunkExtractionResult(rawResponse, chunk, roomId, roomDisplayName) { jsonRepaired = true }
            if (jsonRepaired) anyRepaired = true
            if (parsed.isSuccess) {
                if (observer !== ExtractionObserver.NoOp) {
                    observer.onPass3ChunkResult(
                        chunkTopic = chunk.topic,
                        chunkMessageIds = chunk.messages.map { it.localId },
                        attempts = attemptsUsed,
                        jsonRepaired = anyRepaired,
                        cardProduced = parsed.getOrNull() != null,
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                }
                return parsed
            }
            lastResult = parsed
            val parseErr = parsed.exceptionOrNull()
            val retryableParse = attempt == 0 && parseErr is kotlinx.serialization.SerializationException
            if (!retryableParse) return lastResult
        }
        return lastResult
    }

    private suspend fun ensureCardsTranslated(
        cards: List<EventCard>,
        targetLanguage: String,
        semaphore: Semaphore
    ): Result<List<EventCard>> {
        if (cards.isEmpty()) return Result.success(emptyList())
        val complianceResults = cards.map { LanguageHelper.checkCardCompliance(it, targetLanguage) }
        val nonCompliant = cards.mapIndexedNotNull { index, card ->
            val compliance = complianceResults[index]
            if (compliance.isCompliant) {
                if (observer !== ExtractionObserver.NoOp) {
                    observer.onLanguageGateResult(card.title, compliance, repairAttempts = 0, jsonRepairedCount = 0, finalCompliant = true)
                }
                null
            } else {
                Triple(index, card, compliance)
            }
        }
        if (nonCompliant.isEmpty()) return Result.success(cards)

        val translator = CardTranslator(llmClient)
        val translatedPairs = mapConcurrent(nonCompliant, semaphore) { (index, card, initialCompliance) ->
            repairSingleCard(translator, index, card, initialCompliance, targetLanguage)
        }.getOrElse { return Result.failure(it) }

        val updated = cards.toMutableList()
        for ((index, repairedCard) in translatedPairs) {
            updated[index] = repairedCard
        }
        return Result.success(updated.toList())
    }

    private suspend fun repairSingleCard(
        translator: CardTranslator,
        index: Int,
        originalCard: EventCard,
        initialCheck: LanguageComplianceResult,
        targetLanguage: String
    ): Result<Pair<Int, EventCard>> {
        var jsonRepairedCount = 0
        val firstAttempt = translator.translateCard(originalCard, targetLanguage) { jsonRepairedCount++ }
            .getOrElse { return Result.failure(it) }
        val firstCheck = LanguageHelper.checkCardCompliance(firstAttempt, targetLanguage)
        if (firstCheck.isCompliant) {
            if (observer !== ExtractionObserver.NoOp) {
                observer.onLanguageGateResult(firstAttempt.title, initialCheck, repairAttempts = 1, jsonRepairedCount = jsonRepairedCount, finalCompliant = true)
            }
            return Result.success(index to firstAttempt)
        }

        val targetName = LanguageHelper.resolveTargetLanguageName(targetLanguage)
        val hint = "Your previous output still contained source-language text in fields: " +
            "${firstCheck.nonCompliantFields.joinToString()}. Translate ALL text strictly into $targetName."
        val secondAttempt = translator.translateCard(firstAttempt, targetLanguage, correctiveHint = hint) { jsonRepairedCount++ }
            .getOrElse { return Result.failure(it) }
        val secondCheck = LanguageHelper.checkCardCompliance(secondAttempt, targetLanguage)
        if (secondCheck.isCompliant) {
            if (observer !== ExtractionObserver.NoOp) {
                observer.onLanguageGateResult(secondAttempt.title, initialCheck, repairAttempts = 2, jsonRepairedCount = jsonRepairedCount, finalCompliant = true)
            }
            return Result.success(index to secondAttempt)
        }

        val bestCandidate = pickMostCompliantCard(
            listOf(
                originalCard to initialCheck.nonCompliantFields.size,
                firstAttempt to firstCheck.nonCompliantFields.size,
                secondAttempt to secondCheck.nonCompliantFields.size
            )
        )
        if (observer !== ExtractionObserver.NoOp) {
            observer.onLanguageGateResult(bestCandidate.title, initialCheck, repairAttempts = 2, jsonRepairedCount = jsonRepairedCount, finalCompliant = false)
        }
        System.err.println(
            "[EventExtractor] Warning: Card '${bestCandidate.id}' still contains mixed script after 2 translation repairs " +
                "(remaining: ${secondCheck.nonCompliantFields.joinToString()})."
        )
        return Result.success(index to bestCandidate)
    }

    private fun pickMostCompliantCard(candidates: List<Pair<EventCard, Int>>): EventCard {
        var best = candidates.first().first
        var bestRemaining = candidates.first().second
        for ((card, remaining) in candidates.drop(1)) {
            if (remaining <= bestRemaining) {
                best = card
                bestRemaining = remaining
            }
        }
        return best
    }

    private suspend fun queryLlm(stage: String, systemPrompt: String, userPrompt: String): Result<String> {
        return try {
            Result.success(llmClient.chatCompletion(systemPrompt, userPrompt))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("[EventExtractor] $stage LLM query failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            Result.failure(e)
        }
    }

    internal fun splitIntoGatekeeperBatches(chunks: List<NoticeChunk>): List<List<NoticeChunk>> =
        Pass2Gatekeeper.splitIntoBatches(chunks, effectiveBatchSize, effectiveBatchMaxChars)

    internal fun parseBatchedGatekeeperVerdictsResult(rawJson: String, expectedCount: Int): Result<List<Boolean>> =
        Pass2Gatekeeper.parseBatchedVerdictsResult(rawJson, expectedCount)

    internal fun parseGatekeeperVerdictResult(rawJson: String): Result<Boolean> =
        Pass2Gatekeeper.parseSingleVerdictResult(rawJson)

    internal fun splitIntoTriageWindows(messages: List<WeChatMessage>): List<List<WeChatMessage>> {
        if (messages.isEmpty()) return emptyList()
        val windows = mutableListOf<List<WeChatMessage>>()
        var current = mutableListOf<WeChatMessage>()
        var currentChars = 0L

        for (msg in messages) {
            val previewChars = msg.attachmentPreviews.values.sumOf { it.length }
            val msgChars = minOf(msg.textContent.length, MAX_SINGLE_MESSAGE_CHARS).toLong() + previewChars.toLong() + 64L
            if (current.isNotEmpty() && shouldSplitWindow(current, currentChars, msg, msgChars)) {
                windows.add(current.toList())
                current = mutableListOf()
                currentChars = 0L
            }
            current.add(msg)
            currentChars += msgChars
        }
        if (current.isNotEmpty()) windows.add(current.toList())
        return windows
    }

    private fun shouldSplitWindow(
        current: List<WeChatMessage>,
        currentChars: Long,
        nextMsg: WeChatMessage,
        nextChars: Long
    ): Boolean {
        val prev = current.last()
        val sameSenderTightBurst = prev.senderId == nextMsg.senderId &&
            (nextMsg.timestampSeconds - prev.timestampSeconds) in 0..TIGHT_BURST_GAP_SECONDS
        val sizeExceeded = current.size >= effectiveMaxWindowSize ||
            (current.size >= effectiveWindowSize && !sameSenderTightBurst)
        return sizeExceeded || (currentChars + nextChars > DEFAULT_TRIAGE_WINDOW_MAX_CHARS)
    }

    private fun formatTranscriptForPrompt(messages: List<WeChatMessage>): String {
        return formatMessages(messages, includeIds = true, sanitizeForPrompt = true, includeAttachmentPreviews = true)
    }

    private fun formatSourceMessagesForPrompt(messages: List<WeChatMessage>): String {
        return formatMessages(messages, includeIds = false, sanitizeForPrompt = true, includeAttachmentPreviews = true)
    }

    private fun formatSourceMessages(messages: List<WeChatMessage>): String {
        return formatMessages(messages, includeIds = false, sanitizeForPrompt = false, includeAttachmentPreviews = false)
    }

    private fun formatMessages(
        messages: List<WeChatMessage>,
        includeIds: Boolean,
        sanitizeForPrompt: Boolean,
        includeAttachmentPreviews: Boolean = false
    ): String = PromptMessageFormatter.formatMessages(
        messages = messages,
        timeFormatter = timeFormatter,
        maxSingleMessageChars = MAX_SINGLE_MESSAGE_CHARS,
        includeIds = includeIds,
        sanitizeForPrompt = sanitizeForPrompt,
        includeAttachmentPreviews = includeAttachmentPreviews
    )

    private fun resolveMatchedMessages(
        rawIds: List<JsonPrimitive>,
        messagesById: Map<Long, WeChatMessage>,
        messageIndexById: Map<Long, Int>
    ): List<WeChatMessage> {
        return rawIds.asSequence()
            .mapNotNull { primitive ->
                val token = primitive.content.trim().removePrefix("[").removeSuffix("]").trim()
                val normalized = if (token.startsWith("id:", ignoreCase = true)) token.substring(3).trim() else token
                normalized.toLongOrNull()
            }
            .mapNotNull { messagesById[it] }
            .distinctBy { it.localId }
            .sortedBy { messageIndexById[it.localId] ?: Int.MAX_VALUE }
            .toList()
    }

    internal fun parseTriageResponseResult(rawJson: String, windowMessages: List<WeChatMessage>): Result<List<NoticeChunk>> =
        parseTriageResponseDetailed(rawJson, windowMessages, onRepaired = null, onLegacyFallback = null)

    private fun parseTriageResponseDetailed(
        rawJson: String,
        windowMessages: List<WeChatMessage>,
        onRepaired: (() -> Unit)?,
        onLegacyFallback: (() -> Unit)?
    ): Result<List<NoticeChunk>> {
        val rootObj = try {
            LlmJsonUtils.decodeWithRepair(
                raw = rawJson,
                onRepaired = { onRepaired?.invoke() }
            ) { jsonText ->
                LlmJsonUtils.sharedJson.parseToJsonElement(jsonText).jsonObject
            }
        } catch (e: Exception) {
            System.err.println("[EventExtractor] Failed to parse Pass 1 triage JSON: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        val messagesById = windowMessages.associateBy { it.localId }
        val messageIndexById = windowMessages.withIndex().associate { it.value.localId to it.index }

        if ("chunks" in rootObj) {
            return decodeTriageChunks(rootObj, windowMessages.size, messagesById, messageIndexById)
        }
        if ("events" in rootObj || "is_relevant" in rootObj) {
            onLegacyFallback?.invoke()
            return adaptLegacyTriageFallback(rootObj, messagesById, messageIndexById)
        }
        return Result.failure(IllegalStateException("Pass 1 triage JSON missing 'chunks' array"))
    }

    private fun decodeTriageChunks(
        rootObj: JsonObject,
        windowSize: Int,
        messagesById: Map<Long, WeChatMessage>,
        messageIndexById: Map<Long, Int>
    ): Result<List<NoticeChunk>> {
        val triage = try {
            LlmJsonUtils.sharedJson.decodeFromJsonElement<LlmTriageResponse>(rootObj)
        } catch (e: Exception) {
            System.err.println("[EventExtractor] Failed to decode triage chunks: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        val maxChunks = (windowSize * 2).coerceAtLeast(2)
        val chunks = triage.chunks.asSequence().take(maxChunks).mapNotNull { chunk ->
            val matched = resolveMatchedMessages(chunk.messageIds, messagesById, messageIndexById)
            if (matched.isEmpty()) return@mapNotNull null
            NoticeChunk(
                topic = ExtractionPrompts.sanitizeInline(chunk.topic, 120),
                messages = matched,
                categoryHint = chunk.category.trim().ifBlank { "NOTICE" },
                reason = ExtractionPrompts.sanitizeInline(chunk.reason, 200)
            )
        }.toList()
        return Result.success(chunks)
    }

    private fun adaptLegacyTriageFallback(
        rootObj: JsonObject,
        messagesById: Map<Long, WeChatMessage>,
        messageIndexById: Map<Long, Int>
    ): Result<List<NoticeChunk>> {
        val fallbackResponse = try {
            LlmJsonUtils.sharedJson.decodeFromJsonElement<LlmExtractionResponse>(rootObj)
        } catch (e: Exception) {
            System.err.println("[EventExtractor] Failed to decode legacy triage fallback: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        if (!fallbackResponse.isRelevant || fallbackResponse.events.isEmpty()) return Result.success(emptyList())
        val adapted = fallbackResponse.events.mapNotNull { ev ->
            val matched = resolveMatchedMessages(ev.sourceMessageIds, messagesById, messageIndexById)
            if (matched.isEmpty()) return@mapNotNull null
            NoticeChunk(
                topic = ExtractionPrompts.sanitizeInline(ev.title, 120),
                messages = matched,
                categoryHint = ev.category.trim().ifBlank { "NOTICE" }
            )
        }
        return Result.success(adapted)
    }

    internal fun consolidateTriageChunks(
        chunks: List<NoticeChunk>,
        recentRoomCards: List<EventCard>,
        messageIndexById: Map<Long, Int>
    ): List<NoticeChunk> {
        if (chunks.isEmpty()) return emptyList()
        val existingSourceSet = recentRoomCards
            .mapNotNull { card -> card.sourceMessages.trim().takeIf(String::isNotBlank)?.replace(Regex("\\s+"), " ") }
            .toSet()

        val seenIdSets = mutableSetOf<List<Long>>()
        val result = mutableListOf<NoticeChunk>()
        for (chunk in chunks) {
            val orderedMessages = chunk.messages.distinctBy { it.localId }.sortedBy { messageIndexById[it.localId] ?: Int.MAX_VALUE }
            if (orderedMessages.isEmpty()) continue
            if (!seenIdSets.add(orderedMessages.map { it.localId })) continue

            val normalizedSource = formatSourceMessages(orderedMessages).trim().replace(Regex("\\s+"), " ")
            if (normalizedSource in existingSourceSet) continue
            result.add(chunk.copy(messages = orderedMessages))
        }
        return result
    }

    internal fun parseChunkExtractionResult(
        rawJson: String,
        chunk: NoticeChunk,
        roomId: String,
        roomDisplayName: String = "",
        onRepaired: (() -> Unit)? = null
    ): Result<EventCard?> {
        val response = try {
            LlmJsonUtils.decodeWithRepair(
                raw = rawJson,
                onRepaired = { onRepaired?.invoke() }
            ) { jsonText ->
                LlmJsonUtils.sharedJson.decodeFromString<LlmExtractionResponse>(jsonText)
            }
        } catch (e: Exception) {
            System.err.println("[EventExtractor] Failed to parse Pass 3 JSON: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        if (!response.isRelevant || response.events.isEmpty()) return Result.success(null)

        val fallbackTitle = ExtractionPrompts.sanitizeInline(chunk.topic, 120).ifBlank { "Group Notice" }
        val card = ExtractionCardConsolidator.buildConsolidatedCard(
            extractedEvents = response.events,
            sourceMessagesText = formatSourceMessages(chunk.messages),
            eventTimestamp = chunk.messages.maxOf { it.timestampSeconds },
            eventSender = chunk.messages.last().senderDisplayName,
            roomId = roomId,
            roomDisplayName = roomDisplayName,
            fallbackTitle = fallbackTitle,
            joinDistinctTitles = false,
            sourceAttachments = chunk.messages.flatMap { it.attachments }
        )
        return Result.success(card)
    }

    internal fun parseExtractionResponse(
        rawJson: String,
        substantiveMessages: List<WeChatMessage>,
        roomId: String,
        roomDisplayName: String = ""
    ): List<EventCard> = parseExtractionResponseResult(rawJson, substantiveMessages, roomId, roomDisplayName).getOrElse { emptyList() }

    internal fun parseExtractionResponseResult(
        rawJson: String,
        substantiveMessages: List<WeChatMessage>,
        roomId: String,
        roomDisplayName: String = ""
    ): Result<List<EventCard>> {
        val response = try {
            LlmJsonUtils.decodeWithRepair(rawJson) { jsonText ->
                LlmJsonUtils.sharedJson.decodeFromString<LlmExtractionResponse>(jsonText)
            }
        } catch (e: Exception) {
            System.err.println("[EventExtractor] Failed to parse JSON response: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            return Result.failure(e)
        }
        if (!response.isRelevant || response.events.isEmpty()) return Result.success(emptyList())

        val fallbackTimestamp = substantiveMessages.maxOfOrNull { it.timestampSeconds } ?: Instant.now().epochSecond
        val fallbackSender = substantiveMessages.lastOrNull()?.senderDisplayName ?: "WeChat Group"
        val messagesById = substantiveMessages.associateBy { it.localId }
        val messageIndexById = substantiveMessages.withIndex().associate { it.value.localId to it.index }

        val resolved = response.events.map { extracted ->
            val matchedMessages = resolveMatchedMessages(extracted.sourceMessageIds, messagesById, messageIndexById)
            ExtractionCardConsolidator.ResolvedExtraction(
                extracted = extracted,
                matchedMessages = matchedMessages,
                matchedIds = matchedMessages.map { it.localId }.toSet()
            )
        }

        val cards = ExtractionCardConsolidator.groupOverlappingExtractions(resolved).map { group ->
            val allMatched = group.flatMap { it.matchedMessages }.distinctBy { it.localId }
                .sortedBy { messageIndexById[it.localId] ?: Int.MAX_VALUE }
            ExtractionCardConsolidator.buildConsolidatedCard(
                extractedEvents = group.map { it.extracted },
                sourceMessagesText = if (allMatched.isNotEmpty()) formatSourceMessages(allMatched) else "",
                eventTimestamp = allMatched.maxOfOrNull { it.timestampSeconds } ?: fallbackTimestamp,
                eventSender = allMatched.lastOrNull()?.senderDisplayName ?: fallbackSender,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                fallbackTitle = "Group Notice",
                joinDistinctTitles = true,
                sourceAttachments = allMatched.flatMap { it.attachments }
            )
        }
        return Result.success(cards)
    }

    internal fun buildTriageSystemPrompt(
        userPersona: String = "",
        targetLanguage: String = "English",
        customRules: String = "",
        recentRoomCards: List<EventCard> = emptyList()
    ): String = ExtractionPrompts.buildTriageSystemPrompt(userPersona, targetLanguage, customRules, recentRoomCards)

    internal fun buildGatekeeperSystemPrompt(
        userPersona: String = "",
        customRules: String = ""
    ): String = ExtractionPrompts.buildGatekeeperSystemPrompt(userPersona, customRules)

    internal fun buildSystemPrompt(
        userPersona: String,
        targetLanguage: String,
        customRules: String = ""
    ): String = ExtractionPrompts.buildSystemPrompt(userPersona, targetLanguage, customRules)
}
