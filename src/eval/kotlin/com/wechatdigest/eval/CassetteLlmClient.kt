package com.wechatdigest.eval

import com.wechatdigest.core.llm.ConcurrencyAwareLlmClient
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmCompletionOptions
import com.wechatdigest.core.llm.LlmTelemetryElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializable VCR cassette storing ordered responses per content-addressed prompt SHA-256 key.
 * Multiple occurrences of the same hash support automatic retry sequences cleanly.
 */
@Serializable
data class CassetteFile(
    val version: Int = 1,
    val scenarioId: String,
    val descriptions: Map<String, String> = emptyMap(),
    val entries: Map<String, List<String>> = emptyMap()
)

/** Raised by [ReplayLlmClient] when a test exercises an unrecorded prompt key (fail closed, zero network). */
class CassetteMissException(message: String) : IllegalStateException(message)

/** Content-addressed prompt classifier and SHA-256 key calculator for deterministic cassette matching. */
object CassetteKeyer {

    const val ERROR_SENTINEL_PREFIX = "__ERROR__:"

    /** Infers pipeline stage label from system prompt headers cleanly without brittle indices. */
    fun detectStage(systemPrompt: String): String = when {
        systemPrompt.contains("Semantic Filter & Topic Chunker") -> "pass1_triage"
        systemPrompt.contains("Rule & Substance Gatekeeper") -> "pass2_gatekeeper"
        systemPrompt.contains("Structured Event Card Translator") -> "card_translator"
        systemPrompt.contains("Bullet-to-Card Structurer") -> "pass4_bullet_cards"
        systemPrompt.contains("Grounded Card Summarizer") -> "pass3_synthesis"
        else -> "other"
    }

    /** Computes deterministic SHA-256 hex key over normalized line endings of stage + system + user prompts. */
    fun hashPrompts(stage: String, systemPrompt: String, userPrompt: String): String {
        val normSys = systemPrompt.replace("\r\n", "\n").replace('\r', '\n')
        val normUser = userPrompt.replace("\r\n", "\n").replace('\r', '\n')
        val input = "$stage\n---SYSTEM---\n$normSys\n---USER---\n$normUser"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Decorator capturing every [LlmClient.chatCompletion] invocation (stage, SHA-256 hash, latency, error)
 * while preserving the wrapped client's [ConcurrencyAwareLlmClient.recommendedConcurrency].
 */
class TracingLlmClient(
    private val delegate: LlmClient
) : ConcurrencyAwareLlmClient {

    override val recommendedConcurrency: Int
        get() = EventExtractor.resolveDefaultConcurrency(delegate)

    private val callCounter = AtomicInteger(0)
    private val recordedCalls = Collections.synchronizedList(mutableListOf<LlmCallRecord>())

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String =
        chatCompletion(systemPrompt, userPrompt, LlmCompletionOptions())

    override suspend fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        val index = callCounter.incrementAndGet()
        val stage = CassetteKeyer.detectStage(systemPrompt)
        val hash = CassetteKeyer.hashPrompts(stage, systemPrompt, userPrompt)
        val startMs = System.currentTimeMillis()
        val telemetryElement = LlmTelemetryElement()
        return try {
            val response = withContext(telemetryElement) {
                delegate.chatCompletion(systemPrompt, userPrompt, options)
            }
            val telemetry = telemetryElement.latest
            recordedCalls.add(
                LlmCallRecord(
                    callIndex = index,
                    stage = stage,
                    promptHash = hash,
                    latencyMs = System.currentTimeMillis() - startMs,
                    success = true,
                    responseChars = response.length,
                    promptTokens = telemetry?.promptTokens,
                    completionTokens = telemetry?.completionTokens,
                    finishReason = telemetry?.finishReason
                )
            )
            response
        } catch (e: Exception) {
            val telemetry = telemetryElement.latest
            recordedCalls.add(
                LlmCallRecord(
                    callIndex = index,
                    stage = stage,
                    promptHash = hash,
                    latencyMs = System.currentTimeMillis() - startMs,
                    success = false,
                    errorSummary = e.message?.take(180),
                    promptTokens = telemetry?.promptTokens,
                    completionTokens = telemetry?.completionTokens,
                    finishReason = telemetry?.finishReason
                )
            )
            throw e
        }
    }

    fun snapshotCalls(): List<LlmCallRecord> = synchronized(recordedCalls) {
        recordedCalls.sortedBy { it.callIndex }
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * Thread-safe recording decorator that logs real LLM responses (and transport errors as sentinels)
 * keyed by SHA-256 prompt hashes so concurrent runs produce valid deterministic [CassetteFile]s.
 */
class RecordingLlmClient(
    private val delegate: LlmClient,
    private val scenarioId: String
) : ConcurrencyAwareLlmClient {

    override val recommendedConcurrency: Int
        get() = EventExtractor.resolveDefaultConcurrency(delegate)

    private val entries = ConcurrentHashMap<String, MutableList<String>>()
    private val descriptions = ConcurrentHashMap<String, String>()

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String =
        chatCompletion(systemPrompt, userPrompt, LlmCompletionOptions())

    override suspend fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        val stage = CassetteKeyer.detectStage(systemPrompt)
        val hash = CassetteKeyer.hashPrompts(stage, systemPrompt, userPrompt)
        descriptions.putIfAbsent(hash, stage)
        val bucket = entries.computeIfAbsent(hash) { Collections.synchronizedList(mutableListOf()) }
        return try {
            val response = delegate.chatCompletion(systemPrompt, userPrompt, options)
            bucket.add(response)
            response
        } catch (e: Exception) {
            bucket.add("${CassetteKeyer.ERROR_SENTINEL_PREFIX}${e.message ?: e::class.simpleName}")
            throw e
        }
    }

    fun buildCassetteFile(): CassetteFile {
        val sortedEntries = entries.entries
            .sortedBy { it.key }
            .associate { (k, v) -> k to synchronized(v) { v.toList() } }
        val sortedDescriptions = descriptions.entries
            .sortedBy { it.key }
            .associate { it.key to it.value }
        return CassetteFile(
            version = 1,
            scenarioId = scenarioId,
            descriptions = sortedDescriptions,
            entries = sortedEntries
        )
    }

    fun saveToFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(CassetteCodec.prettyJson.encodeToString(buildCassetteFile()))
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * Offline fail-closed VCR replay client serving pre-recorded responses by SHA-256 prompt hash
 * and per-hash occurrence counter (immune to concurrent Pass 3 interleaving).
 */
class ReplayLlmClient(
    private val cassette: CassetteFile,
    override val recommendedConcurrency: Int = EventExtractor.DEFAULT_CLOUD_CONCURRENCY
) : ConcurrencyAwareLlmClient {

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String =
        chatCompletion(systemPrompt, userPrompt, LlmCompletionOptions())

    override suspend fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        val stage = CassetteKeyer.detectStage(systemPrompt)
        val hash = CassetteKeyer.hashPrompts(stage, systemPrompt, userPrompt)
        val list = cassette.entries[hash]
            ?: throw CassetteMissException(
                "Cassette miss for scenario '${cassette.scenarioId}' stage='$stage' hash='$hash'. " +
                    "Prompt preview: ${userPrompt.replace('\n', ' ').take(140)}"
            )
        val idx = counters.computeIfAbsent(hash) { AtomicInteger(0) }.getAndIncrement()
        if (idx >= list.size) {
            throw CassetteMissException(
                "Cassette exhausted ($idx >= ${list.size}) for scenario '${cassette.scenarioId}' stage='$stage' hash='$hash'."
            )
        }
        val payload = list[idx]
        if (payload.startsWith(CassetteKeyer.ERROR_SENTINEL_PREFIX)) {
            throw IllegalStateException(payload.removePrefix(CassetteKeyer.ERROR_SENTINEL_PREFIX))
        }
        return payload
    }

    override fun close() = Unit
}

internal object CassetteCodec {
    val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadCassette(file: File): CassetteFile =
        prettyJson.decodeFromString<CassetteFile>(file.readText())
}
