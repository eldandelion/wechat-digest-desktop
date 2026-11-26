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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.atomic.AtomicInteger

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
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val observer: ExtractionObserver = ExtractionObserver.NoOp,
    private val enableFastPath: Boolean = true
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

        /** Maximum substantive messages eligible for 1-call fused fast-path synthesis. */
        const val FAST_PATH_MAX_MESSAGES = 3

        /** Maximum formatted prompt character count eligible for 1-call fused fast-path synthesis. */
        const val FAST_PATH_MAX_CHARS = 1_500

        private const val TIGHT_BURST_GAP_SECONDS = 300L

        private val ipv4LiteralRegex = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        private val ipv4ShorthandOrIntRegex = Regex("^(?:\\d{1,3}\\.\\d{1,9}|\\d+)$")

        /**
         * Returns a conservative concurrency cap for [config]: `1` for local Ollama or loopback/private
         * URLs (to prevent queue timeouts) and `4` for remote cloud OpenAI-compatible APIs.
         */
        fun resolveConcurrencyForConfig(config: LlmConfig): Int {
            if (config.providerType == LlmProviderType.OLLAMA ||
                config.providerType == LlmProviderType.BUILT_IN_LOCAL ||
                isLocalEndpoint(config.endpointUrl)
            ) {
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
            if ((ipv4LiteralRegex.matches(host) && host.startsWith("127.")) ||
                (ipv4ShorthandOrIntRegex.matches(host) && !ipv4LiteralRegex.matches(host))
            ) {
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
     * Extracts event cards using Pass 1 triage, Pass 2 batched gatekeeper, and per-chunk fused Pass 3
     * synthesis + conditional target-language repair, optionally streaming finalized cards via [onCardReady]
     * and reporting phase progress via [onProgress] while preserving ordered results and fail-closed errors.
     */
    suspend fun extractEventsResult(
        messages: List<WeChatMessage>,
        roomId: String,
        userPersona: String = "",
        targetLanguage: String = "English",
        customRules: String = "",
        roomDisplayName: String = "",
        prefilterConfig: PrefilterConfig = PrefilterConfig(),
        recentRoomCards: List<EventCard> = emptyList(),
        onCardReady: (suspend (EventCard) -> Unit)? = null,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)? = null
    ): Result<List<EventCard>> {
        val substantive = MessageSanitizer.filterSubstantiveMessages(messages, prefilterConfig)
        if (observer !== ExtractionObserver.NoOp) {
            observer.onPrefilterComplete(messages.size, substantive.size, substantive.map { it.localId })
        }
        if (substantive.isEmpty()) return Result.success(emptyList())

        val semaphore = Semaphore(effectiveMaxConcurrency)
        if (enableFastPath &&
            customRules.isBlank() &&
            substantive.size <= FAST_PATH_MAX_MESSAGES &&
            formatTranscriptForPrompt(substantive).length < FAST_PATH_MAX_CHARS
        ) {
            return extractSingleFastPath(
                substantive = substantive,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                userPersona = userPersona,
                targetLanguage = targetLanguage,
                customRules = customRules,
                recentRoomCards = recentRoomCards,
                semaphore = semaphore,
                onCardReady = onCardReady,
                onProgress = onProgress
            )
        }

        notifyProgress(onProgress, "Triaging messages...", 0, substantive.size)
            .getOrElse { return Result.failure(it) }
        val discovered = runPass1Triage(substantive, userPersona, targetLanguage, customRules, recentRoomCards, semaphore)
            .getOrElse { return Result.failure(it) }

        val messageIndexById = substantive.withIndex().associate { it.value.localId to it.index }
        val consolidated = consolidateTriageChunks(discovered, recentRoomCards, messageIndexById)
        if (observer !== ExtractionObserver.NoOp) {
            observer.onPass1Consolidated(discovered.size, consolidated.size, consolidated.map { it.topic })
        }
        if (consolidated.isEmpty()) return Result.success(emptyList())

        notifyProgress(onProgress, "Filtering ${consolidated.size} candidate notice(s)...", 0, consolidated.size)
            .getOrElse { return Result.failure(it) }
        val verified = runPass2Gatekeeper(consolidated, userPersona, customRules, semaphore)
            .getOrElse { return Result.failure(it) }
        if (verified.isEmpty()) return Result.success(emptyList())

        notifyProgress(onProgress, "Extracting ${verified.size} notice(s)...", 0, verified.size)
            .getOrElse { return Result.failure(it) }
        return runPass3AndTranslateStreaming(
            chunks = verified,
            roomId = roomId,
            roomDisplayName = roomDisplayName,
            userPersona = userPersona,
            targetLanguage = targetLanguage,
            customRules = customRules,
            semaphore = semaphore,
            onCardReady = onCardReady,
            onProgress = onProgress
        )
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

    private suspend fun extractSingleFastPath(
        substantive: List<WeChatMessage>,
        roomId: String,
        roomDisplayName: String,
        userPersona: String,
        targetLanguage: String,
        customRules: String,
        recentRoomCards: List<EventCard>,
        semaphore: Semaphore,
        onCardReady: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?
    ): Result<List<EventCard>> {
        val existingSourceSet = buildExistingSourceSet(recentRoomCards)
        val remaining = substantive.filterNot { msg ->
            normalizeSourceText(formatSourceMessages(listOf(msg))) in existingSourceSet
        }
        if (remaining.isEmpty()) return Result.success(emptyList())
        if (normalizeSourceText(formatSourceMessages(remaining)) in existingSourceSet) {
            return Result.success(emptyList())
        }

        notifyProgress(onProgress, "Extracting ${remaining.size} message(s)...", 0, 1)
            .getOrElse { return Result.failure(it) }

        val systemPrompt = buildSystemPrompt(userPersona, targetLanguage, customRules)
        val userPrompt = ExtractionPrompts.buildFastPathSynthesisUserPrompt(
            transcript = formatTranscriptForPrompt(remaining),
            recentRoomCards = recentRoomCards
        )

        val startMs = System.currentTimeMillis()
        val initialCards = runFastPathSynthesisWithRetry(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            remaining = remaining,
            roomId = roomId,
            roomDisplayName = roomDisplayName,
            semaphore = semaphore,
            startMs = startMs
        ).getOrElse { return Result.failure(it) }
        if (initialCards.isEmpty()) {
            notifyProgress(onProgress, "Extracting notices (1/1)...", 1, 1)
                .getOrElse { return Result.failure(it) }
            return Result.success(emptyList())
        }

        return translateAndEmitPhased(
            slotItems = initialCards.mapIndexed { idx, card -> idx to card },
            totalSlots = initialCards.size,
            targetLanguage = targetLanguage,
            translator = CardTranslator(llmClient),
            semaphore = semaphore,
            completedCount = AtomicInteger(0),
            progressMutex = Mutex(),
            onCardReady = onCardReady,
            onProgress = onProgress
        )
    }

    private suspend fun translateAndEmitPhased(
        slotItems: List<Pair<Int, EventCard>>,
        totalSlots: Int,
        targetLanguage: String,
        translator: CardTranslator,
        semaphore: Semaphore,
        completedCount: AtomicInteger,
        progressMutex: Mutex,
        onCardReady: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?
    ): Result<List<EventCard>> {
        val slots = arrayOfNulls<EventCard>(totalSlots)
        val pendingTranslations = mutableListOf<Triple<Int, EventCard, LanguageComplianceResult>>()

        for ((idx, card) in slotItems) {
            val initialCompliance = LanguageHelper.checkCardCompliance(card, targetLanguage)
            if (initialCompliance.isCompliant) {
                if (observer !== ExtractionObserver.NoOp) {
                    observer.onLanguageGateResult(card.title, initialCompliance, repairAttempts = 0, jsonRepairedCount = 0, finalCompliant = true)
                }
                slots[idx] = card
                if (onCardReady != null) {
                    try {
                        onCardReady.invoke(card)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        return Result.failure(e)
                    }
                }
                reportPass3Progress(progressMutex, onProgress, completedCount, totalSlots, incrementCompleted = true, translating = false)
                    .getOrElse { return Result.failure(it) }
            } else {
                pendingTranslations.add(Triple(idx, card, initialCompliance))
            }
        }

        for ((idx, card, initialCompliance) in pendingTranslations) {
            reportPass3Progress(progressMutex, onProgress, completedCount, totalSlots, incrementCompleted = false, translating = true)
                .getOrElse { return Result.failure(it) }
            val repaired = repairSingleCard(translator, card, initialCompliance, targetLanguage, semaphore)
                .getOrElse { err ->
                    if (err is CancellationException) throw err
                    return Result.failure(err)
                }
            slots[idx] = repaired
            if (onCardReady != null) {
                try {
                    onCardReady.invoke(repaired)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return Result.failure(e)
                }
            }
            reportPass3Progress(progressMutex, onProgress, completedCount, totalSlots, incrementCompleted = true, translating = false)
                .getOrElse { return Result.failure(it) }
        }

        return Result.success(slots.filterNotNull())
    }

    private suspend fun runFastPathSynthesisWithRetry(
        systemPrompt: String,
        userPrompt: String,
        remaining: List<WeChatMessage>,
        roomId: String,
        roomDisplayName: String,
        semaphore: Semaphore,
        startMs: Long
    ): Result<List<EventCard>> {
        var lastResult: Result<List<EventCard>> = Result.failure(IllegalStateException("Fast-path Pass 3 did not execute"))
        var attemptsUsed = 0
        var anyRepaired = false

        repeat(2) { attempt ->
            attemptsUsed = attempt + 1
            val rawResponse = semaphore.withPermit {
                queryLlm("Fast-path Pass 3 synthesis", systemPrompt, userPrompt, StageJsonSchemas.pass3SynthesisOptions())
            }.getOrElse { err ->
                lastResult = Result.failure(err)
                val retryable = attempt == 0 && LlmRetryPolicy.isRetryableError(err)
                if (!retryable) return lastResult
                return@repeat
            }

            var jsonRepaired = false
            val parsed = parseExtractionResponseResult(
                rawJson = rawResponse,
                substantiveMessages = remaining,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                singleMessageFallback = remaining.size == 1,
                onRepaired = { jsonRepaired = true }
            )
            if (jsonRepaired) anyRepaired = true
            if (parsed.isSuccess) {
                if (observer !== ExtractionObserver.NoOp) {
                    observer.onPass3ChunkResult(
                        chunkTopic = "Fast-path synthesis",
                        chunkMessageIds = remaining.map { it.localId },
                        attempts = attemptsUsed,
                        jsonRepaired = anyRepaired,
                        cardProduced = parsed.getOrThrow().isNotEmpty(),
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                }
                return parsed
            }
            lastResult = parsed
            val parseErr = parsed.exceptionOrNull()
            val retryableParse = attempt == 0 && parseErr != null && LlmRetryPolicy.isRetryableError(parseErr)
            if (!retryableParse) return lastResult
        }
        return lastResult
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
            var lastResult: Result<List<NoticeChunk>> = Result.failure(IllegalStateException("Pass 1 triage did not execute"))
            var anyRepaired = false
            var anyLegacyFallback = false

            repeat(2) { attempt ->
                val rawJson = queryLlm("Pass 1 triage", systemPrompt, userPrompt, StageJsonSchemas.pass1TriageOptions())
                    .getOrElse { err ->
                        lastResult = Result.failure(err)
                        val retryable = attempt == 0 && LlmRetryPolicy.isRetryableError(err)
                        if (!retryable) return@mapConcurrent lastResult
                        return@repeat
                    }
                var jsonRepaired = false
                var usedLegacyFallback = false
                val parsed = parseTriageResponseDetailed(
                    rawJson = rawJson,
                    windowMessages = window,
                    onRepaired = { jsonRepaired = true },
                    onLegacyFallback = { usedLegacyFallback = true }
                )
                if (jsonRepaired) anyRepaired = true
                if (usedLegacyFallback) anyLegacyFallback = true
                if (parsed.isSuccess) {
                    if (observer !== ExtractionObserver.NoOp) {
                        observer.onPass1WindowResult(
                            windowMessageIds = window.map { it.localId },
                            discoveredChunkCount = parsed.getOrNull()?.size ?: 0,
                            jsonRepaired = anyRepaired,
                            usedLegacyFallback = anyLegacyFallback,
                            latencyMs = System.currentTimeMillis() - startMs
                        )
                    }
                    return@mapConcurrent parsed
                }
                lastResult = parsed
                val parseErr = parsed.exceptionOrNull()
                val retryableParse = attempt == 0 && parseErr != null && LlmRetryPolicy.isRetryableError(parseErr)
                if (!retryableParse) return@mapConcurrent lastResult
            }
            lastResult
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
            val options = if (batch.size == 1) {
                StageJsonSchemas.pass2GatekeeperSingleOptions()
            } else {
                StageJsonSchemas.pass2GatekeeperBatchedOptions()
            }

            var lastResult: Result<List<NoticeChunk>> = Result.failure(IllegalStateException("Pass 2 gatekeeper did not execute"))
            repeat(2) { attempt ->
                val rawJson = queryLlm("Pass 2 gatekeeper", systemPrompt, userPrompt, options)
                    .getOrElse { err ->
                        lastResult = Result.failure(err)
                        val retryable = attempt == 0 && LlmRetryPolicy.isRetryableError(err)
                        if (!retryable) return@mapConcurrent lastResult
                        return@repeat
                    }
                val evaluation = Pass2Gatekeeper.evaluateBatchVerdictDetailed(batch, rawJson)
                if (evaluation.isSuccess) {
                    val detail = evaluation.getOrThrow()
                    if (observer !== ExtractionObserver.NoOp) {
                        observer.onPass2BatchResult(
                            verdicts = detail.traces,
                            jsonRepaired = detail.jsonRepaired,
                            latencyMs = System.currentTimeMillis() - startMs
                        )
                    }
                    return@mapConcurrent Result.success(detail.approvedChunks)
                }
                val evalErr = evaluation.exceptionOrNull()
                lastResult = Result.failure(evalErr ?: IllegalStateException("Pass 2 evaluation failed"))
                val retryableParse = attempt == 0 && evalErr != null && LlmRetryPolicy.isRetryableError(evalErr)
                if (!retryableParse) return@mapConcurrent lastResult
            }
            lastResult
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
                transcript = formatGatekeeperMessagesForPrompt(single.messages)
            )
        }
        val indexed = batch.mapIndexed { idx, chunk ->
            Triple(idx, chunk.topic, formatGatekeeperMessagesForPrompt(chunk.messages))
        }
        return batchedPrompt to ExtractionPrompts.buildBatchedGatekeeperUserPrompt(indexed)
    }

    private suspend fun runPass3AndTranslateStreaming(
        chunks: List<NoticeChunk>,
        roomId: String,
        roomDisplayName: String,
        userPersona: String,
        targetLanguage: String,
        customRules: String,
        semaphore: Semaphore,
        onCardReady: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?
    ): Result<List<EventCard>> {
        if (chunks.isEmpty()) return Result.success(emptyList())
        val systemPrompt = buildSystemPrompt(userPersona, targetLanguage, customRules)
        val translator = CardTranslator(llmClient)
        val completedCount = AtomicInteger(0)
        val progressMutex = Mutex()
        if (effectiveMaxConcurrency <= 1 && chunks.size > 1) {
            return runPass3PhasedLocal(
                chunks = chunks,
                systemPrompt = systemPrompt,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                targetLanguage = targetLanguage,
                translator = translator,
                semaphore = semaphore,
                completedCount = completedCount,
                progressMutex = progressMutex,
                onCardReady = onCardReady,
                onProgress = onProgress
            )
        }
        val runner: suspend (NoticeChunk) -> Result<EventCard?> = { chunk ->
            processChunkPass3AndTranslate(
                chunk = chunk,
                totalChunks = chunks.size,
                systemPrompt = systemPrompt,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                targetLanguage = targetLanguage,
                translator = translator,
                semaphore = semaphore,
                completedCount = completedCount,
                progressMutex = progressMutex,
                onCardReady = onCardReady,
                onProgress = onProgress
            )
        }
        if (effectiveMaxConcurrency <= 1 || chunks.size == 1) {
            return runPass3Sequential(chunks, runner)
        }
        return runPass3Concurrent(chunks, runner)
    }

    /**
     * For serial local inference (`concurrency == 1`), runs all Pass 3 synthesis calls before any
     * [CardTranslator] repair calls so the KV-cache prefix stays hot across chunks, while still
     * streaming already-compliant cards via [onCardReady] immediately.
     */
    private suspend fun runPass3PhasedLocal(
        chunks: List<NoticeChunk>,
        systemPrompt: String,
        roomId: String,
        roomDisplayName: String,
        targetLanguage: String,
        translator: CardTranslator,
        semaphore: Semaphore,
        completedCount: AtomicInteger,
        progressMutex: Mutex,
        onCardReady: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?
    ): Result<List<EventCard>> {
        val synthesizedSlots = mutableListOf<Pair<Int, EventCard>>()
        for ((index, chunk) in chunks.withIndex()) {
            val userPrompt = ExtractionPrompts.buildSynthesisUserPrompt(chunk.topic, formatTranscriptForPrompt(chunk.messages))
            val synthesized = synthesizeChunkWithRetry(systemPrompt, userPrompt, chunk, roomId, roomDisplayName, semaphore)
                .getOrElse { err ->
                    if (err is CancellationException) throw err
                    return Result.failure(err)
                }
            if (synthesized == null) {
                reportPass3Progress(progressMutex, onProgress, completedCount, chunks.size, incrementCompleted = true, translating = false)
                    .getOrElse { return Result.failure(it) }
                continue
            }
            synthesizedSlots.add(index to synthesized)
        }

        return translateAndEmitPhased(
            slotItems = synthesizedSlots,
            totalSlots = chunks.size,
            targetLanguage = targetLanguage,
            translator = translator,
            semaphore = semaphore,
            completedCount = completedCount,
            progressMutex = progressMutex,
            onCardReady = onCardReady,
            onProgress = onProgress
        )
    }

    private suspend fun runPass3Sequential(
        chunks: List<NoticeChunk>,
        runner: suspend (NoticeChunk) -> Result<EventCard?>
    ): Result<List<EventCard>> {
        val sequential = ArrayList<EventCard>(chunks.size)
        for (chunk in chunks) {
            val card = runner(chunk).getOrElse { err ->
                if (err is CancellationException) throw err
                return Result.failure(err)
            }
            if (card != null) sequential.add(card)
        }
        return Result.success(sequential)
    }

    private suspend fun runPass3Concurrent(
        chunks: List<NoticeChunk>,
        runner: suspend (NoticeChunk) -> Result<EventCard?>
    ): Result<List<EventCard>> {
        return try {
            val orderedCards = coroutineScope {
                chunks.map { chunk ->
                    async { runner(chunk).getOrElse { throw StageFailureException(it) } }
                }.awaitAll().filterNotNull()
            }
            Result.success(orderedCards)
        } catch (e: StageFailureException) {
            currentCoroutineContext().ensureActive()
            if (e.failureError is CancellationException) throw e.failureError
            Result.failure(e.failureError)
        }
    }

    private suspend fun processChunkPass3AndTranslate(
        chunk: NoticeChunk,
        totalChunks: Int,
        systemPrompt: String,
        roomId: String,
        roomDisplayName: String,
        targetLanguage: String,
        translator: CardTranslator,
        semaphore: Semaphore,
        completedCount: AtomicInteger,
        progressMutex: Mutex,
        onCardReady: (suspend (EventCard) -> Unit)?,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?
    ): Result<EventCard?> {
        val userPrompt = ExtractionPrompts.buildSynthesisUserPrompt(chunk.topic, formatTranscriptForPrompt(chunk.messages))
        val synthesized = synthesizeChunkWithRetry(systemPrompt, userPrompt, chunk, roomId, roomDisplayName, semaphore)
            .getOrElse { return Result.failure(it) }
        if (synthesized == null) {
            reportPass3Progress(progressMutex, onProgress, completedCount, totalChunks, incrementCompleted = true, translating = false)
                .getOrElse { return Result.failure(it) }
            return Result.success(null)
        }

        val initialCompliance = LanguageHelper.checkCardCompliance(synthesized, targetLanguage)
        val finalCard = if (initialCompliance.isCompliant) {
            if (observer !== ExtractionObserver.NoOp) {
                observer.onLanguageGateResult(synthesized.title, initialCompliance, repairAttempts = 0, jsonRepairedCount = 0, finalCompliant = true)
            }
            synthesized
        } else {
            reportPass3Progress(progressMutex, onProgress, completedCount, totalChunks, incrementCompleted = false, translating = true)
                .getOrElse { return Result.failure(it) }
            repairSingleCard(translator, synthesized, initialCompliance, targetLanguage, semaphore)
                .getOrElse { return Result.failure(it) }
        }

        if (onCardReady != null) {
            try {
                onCardReady.invoke(finalCard)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return Result.failure(e)
            }
        }
        reportPass3Progress(progressMutex, onProgress, completedCount, totalChunks, incrementCompleted = true, translating = false)
            .getOrElse { return Result.failure(it) }
        return Result.success(finalCard)
    }

    private suspend fun reportPass3Progress(
        progressMutex: Mutex,
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?,
        completedCount: AtomicInteger,
        totalChunks: Int,
        incrementCompleted: Boolean,
        translating: Boolean
    ): Result<Unit> {
        if (onProgress == null) {
            if (incrementCompleted) completedCount.incrementAndGet()
            return Result.success(Unit)
        }
        return progressMutex.withLock {
            val done = if (incrementCompleted) completedCount.incrementAndGet() else completedCount.get()
            val stage = if (translating) {
                "Translating notice ($done/$totalChunks)..."
            } else {
                "Extracting notices ($done/$totalChunks)..."
            }
            notifyProgress(onProgress, stage, done, totalChunks)
        }
    }

    private suspend fun notifyProgress(
        onProgress: (suspend (stage: String, completed: Int, total: Int) -> Unit)?,
        stage: String,
        completed: Int,
        total: Int
    ): Result<Unit> {
        if (onProgress == null) return Result.success(Unit)
        return try {
            onProgress.invoke(stage, completed, total)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun synthesizeChunkWithRetry(
        systemPrompt: String,
        userPrompt: String,
        chunk: NoticeChunk,
        roomId: String,
        roomDisplayName: String,
        semaphore: Semaphore
    ): Result<EventCard?> {
        val startMs = System.currentTimeMillis()
        var lastResult: Result<EventCard?> = Result.failure(IllegalStateException("Pass 3 did not execute"))
        var attemptsUsed = 0
        var anyRepaired = false

        repeat(2) { attempt ->
            attemptsUsed = attempt + 1
            val rawResponse = semaphore.withPermit {
                queryLlm("Pass 3 chunk synthesis", systemPrompt, userPrompt, StageJsonSchemas.pass3SynthesisOptions())
            }.getOrElse { err ->
                lastResult = Result.failure(err)
                val retryable = attempt == 0 && LlmRetryPolicy.isRetryableError(err)
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
            val retryableParse = attempt == 0 && parseErr != null && LlmRetryPolicy.isRetryableError(parseErr)
            if (!retryableParse) return lastResult
        }
        return lastResult
    }

    private suspend fun repairSingleCard(
        translator: CardTranslator,
        originalCard: EventCard,
        initialCheck: LanguageComplianceResult,
        targetLanguage: String,
        semaphore: Semaphore
    ): Result<EventCard> {
        var jsonRepairedCount = 0
        val firstAttempt = semaphore.withPermit {
            translator.translateCard(originalCard, targetLanguage) { jsonRepairedCount++ }
        }.getOrElse { return Result.failure(it) }
        val firstCheck = LanguageHelper.checkCardCompliance(firstAttempt, targetLanguage)
        if (firstCheck.isCompliant) {
            if (observer !== ExtractionObserver.NoOp) {
                observer.onLanguageGateResult(firstAttempt.title, initialCheck, repairAttempts = 1, jsonRepairedCount = jsonRepairedCount, finalCompliant = true)
            }
            return Result.success(firstAttempt)
        }

        val targetName = LanguageHelper.resolveTargetLanguageName(targetLanguage)
        val hint = "Your previous output still contained source-language text in fields: " +
            "${firstCheck.nonCompliantFields.joinToString()}. Translate ALL text strictly into $targetName."
        val secondAttempt = semaphore.withPermit {
            translator.translateCard(firstAttempt, targetLanguage, correctiveHint = hint) { jsonRepairedCount++ }
        }.getOrElse { return Result.failure(it) }
        val secondCheck = LanguageHelper.checkCardCompliance(secondAttempt, targetLanguage)
        if (secondCheck.isCompliant) {
            if (observer !== ExtractionObserver.NoOp) {
                observer.onLanguageGateResult(secondAttempt.title, initialCheck, repairAttempts = 2, jsonRepairedCount = jsonRepairedCount, finalCompliant = true)
            }
            return Result.success(secondAttempt)
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
        return Result.success(bestCandidate)
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

    private suspend fun queryLlm(
        stage: String,
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions = LlmCompletionOptions()
    ): Result<String> {
        return try {
            Result.success(llmClient.chatCompletion(systemPrompt, userPrompt, options))
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

    private fun formatGatekeeperMessagesForPrompt(messages: List<WeChatMessage>): String {
        return PromptMessageFormatter.formatMessages(
            messages = messages,
            timeFormatter = timeFormatter,
            maxSingleMessageChars = Pass2Gatekeeper.MAX_PREVIEW_CHARS_PER_MESSAGE,
            includeIds = false,
            sanitizeForPrompt = true,
            includeAttachmentPreviews = true,
            truncateWithEllipsis = true,
            maxTotalEntryChars = Pass2Gatekeeper.MAX_PREVIEW_CHARS_PER_MESSAGE
        )
    }

    private fun formatSourceMessagesForPrompt(messages: List<WeChatMessage>): String {
        return formatMessages(messages, includeIds = false, sanitizeForPrompt = true, includeAttachmentPreviews = true)
    }

    private fun formatSourceMessages(messages: List<WeChatMessage>): String {
        return formatMessages(messages, includeIds = false, sanitizeForPrompt = false, includeAttachmentPreviews = false)
    }

    private fun normalizeSourceText(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    private fun buildExistingSourceSet(recentRoomCards: List<EventCard>): Set<String> =
        recentRoomCards
            .mapNotNull { card -> card.sourceMessages.trim().takeIf(String::isNotBlank)?.let(::normalizeSourceText) }
            .toSet()

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
        val existingSourceSet = buildExistingSourceSet(recentRoomCards)

        val seenIdSets = mutableSetOf<List<Long>>()
        val result = mutableListOf<NoticeChunk>()
        for (chunk in chunks) {
            val orderedMessages = chunk.messages.distinctBy { it.localId }.sortedBy { messageIndexById[it.localId] ?: Int.MAX_VALUE }
            if (orderedMessages.isEmpty()) continue
            if (!seenIdSets.add(orderedMessages.map { it.localId })) continue

            val normalizedSource = normalizeSourceText(formatSourceMessages(orderedMessages))
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
            sourceAttachments = chunk.messages.flatMap { it.attachments },
            zoneId = zoneId
        )
        return Result.success(card)
    }

    internal fun parseExtractionResponse(
        rawJson: String,
        substantiveMessages: List<WeChatMessage>,
        roomId: String,
        roomDisplayName: String = ""
    ): List<EventCard> = parseExtractionResponseResult(
        rawJson = rawJson,
        substantiveMessages = substantiveMessages,
        roomId = roomId,
        roomDisplayName = roomDisplayName,
        singleMessageFallback = substantiveMessages.size == 1
    ).getOrElse { emptyList() }

    internal fun parseExtractionResponseResult(
        rawJson: String,
        substantiveMessages: List<WeChatMessage>,
        roomId: String,
        roomDisplayName: String = "",
        singleMessageFallback: Boolean = false,
        onRepaired: (() -> Unit)? = null
    ): Result<List<EventCard>> {
        val response = try {
            LlmJsonUtils.decodeWithRepair(
                raw = rawJson,
                onRepaired = { onRepaired?.invoke() }
            ) { jsonText ->
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
            val rawMatched = resolveMatchedMessages(extracted.sourceMessageIds, messagesById, messageIndexById)
            val matchedMessages = if (rawMatched.isEmpty() && singleMessageFallback && substantiveMessages.size == 1) {
                substantiveMessages
            } else {
                rawMatched
            }
            ExtractionCardConsolidator.ResolvedExtraction(
                extracted = extracted,
                matchedMessages = matchedMessages,
                matchedIds = matchedMessages.map { it.localId }.toSet()
            )
        }

        val cards = ExtractionCardConsolidator.groupOverlappingExtractions(resolved).mapNotNull { group ->
            val allMatched = group.flatMap { it.matchedMessages }.distinctBy { it.localId }
                .sortedBy { messageIndexById[it.localId] ?: Int.MAX_VALUE }
            if (allMatched.isEmpty()) return@mapNotNull null
            ExtractionCardConsolidator.buildConsolidatedCard(
                extractedEvents = group.map { it.extracted },
                sourceMessagesText = formatSourceMessages(allMatched),
                eventTimestamp = allMatched.maxOfOrNull { it.timestampSeconds } ?: fallbackTimestamp,
                eventSender = allMatched.lastOrNull()?.senderDisplayName ?: fallbackSender,
                roomId = roomId,
                roomDisplayName = roomDisplayName,
                fallbackTitle = "Group Notice",
                joinDistinctTitles = false,
                sourceAttachments = allMatched.flatMap { it.attachments },
                zoneId = zoneId
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
