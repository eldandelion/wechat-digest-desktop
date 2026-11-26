package com.wechatdigest.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-call structured output & token budget options passed to [LlmClient.chatCompletion].
 */
data class LlmCompletionOptions(
    val responseSchema: JsonObject? = null,
    val schemaName: String = "response",
    val maxTokens: Int? = null,
    val allowSchemaFallback: Boolean = true
)

/**
 * Shared retry classification for transient empty-response and malformed-JSON local LLM quirks.
 */
internal object LlmRetryPolicy {
    fun isRetryableError(error: Throwable): Boolean {
        if (error is SerializationException) return true
        val msg = error.message.orEmpty()
        return msg.contains("Empty or missing message", ignoreCase = true) ||
            msg.contains("Empty or missing choices", ignoreCase = true) ||
            msg.contains("returned no text", ignoreCase = true)
    }
}

/**
 * Coroutine-scoped telemetry capture element so decorators (e.g. `TracingLlmClient`) receive exact
 * per-request token & finish-reason metrics even under concurrent cloud execution.
 */
internal class LlmTelemetryElement : AbstractCoroutineContextElement(Key) {
    @Volatile
    var latest: LlmHttpTelemetry? = null

    fun record(telemetry: LlmHttpTelemetry) {
        latest = telemetry
    }

    companion object Key : CoroutineContext.Key<LlmTelemetryElement>
}

// OpenAI Request / Response DTOs
@Serializable
internal data class OpenAiMessage(val role: String = "", val content: String = "")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double,
    @SerialName("response_format") val responseFormat: JsonObject? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
internal data class OpenAiChoice(
    val message: OpenAiMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null
)

@Serializable
internal data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

// Ollama Request / Response DTOs
@Serializable
internal data class OllamaOptions(
    val temperature: Double = 0.2,
    @SerialName("num_ctx") val numCtx: Int = 8192,
    @SerialName("num_predict") val numPredict: Int = 4096
)

@Serializable
internal data class OllamaRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = false,
    val format: JsonElement = JsonPrimitive("json"),
    val options: OllamaOptions? = null
)

@Serializable
internal data class OllamaResponse(
    val message: OpenAiMessage? = null,
    val error: String? = null,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null
)

interface LlmClient : AutoCloseable {
    suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String

    suspend fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String = chatCompletion(systemPrompt, userPrompt)
}

/** Optional capability interface allowing decorated clients to propagate provider concurrency caps. */
interface ConcurrencyAwareLlmClient : LlmClient {
    val recommendedConcurrency: Int
}

class KtorLlmClient(
    val config: LlmConfig,
    engine: HttpClientEngine? = null,
    private val onTelemetry: ((LlmHttpTelemetry) -> Unit)? = null
) : ConcurrencyAwareLlmClient {

    private val ownsEngine = engine == null
    private val resolvedEngine: HttpClientEngine = engine ?: CIO.create()

    override val recommendedConcurrency: Int
        get() = EventExtractor.resolveConcurrencyForConfig(config)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(resolvedEngine) {
        followRedirects = false
        expectSuccess = true
        install(ContentNegotiation) {
            json(json)
        }
        val effectiveTimeoutSeconds = if (
            config.providerType == LlmProviderType.OLLAMA ||
            config.providerType == LlmProviderType.BUILT_IN_LOCAL
        ) {
            maxOf(config.timeoutSeconds, 180L)
        } else {
            maxOf(config.timeoutSeconds, 60L)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = effectiveTimeoutSeconds * 1000L
            connectTimeoutMillis = 15000L
            socketTimeoutMillis = effectiveTimeoutSeconds * 1000L
        }
    }

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String =
        chatCompletion(systemPrompt, userPrompt, LlmCompletionOptions())

    override suspend fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        return when (config.providerType) {
            LlmProviderType.OPENAI_COMPATIBLE -> executeOpenAi(systemPrompt, userPrompt, options)
            LlmProviderType.OLLAMA -> executeOllama(systemPrompt, userPrompt, options)
            LlmProviderType.BUILT_IN_LOCAL -> {
                val baseUrl = config.endpointUrl.trim().trimEnd('/')
                if (!isStrictLoopbackHttpUrl(baseUrl)) {
                    throw IllegalStateException(
                        "Built-in local model server is not active. Select or download a local model in Settings."
                    )
                }
                executeOpenAi(systemPrompt, userPrompt, options)
            }
        }
    }

    private suspend fun executeOpenAi(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        val baseUrl = config.endpointUrl.trim().trimEnd('/')
        validateTransportSecurity(baseUrl)
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val primaryPayload = buildOpenAiPayload(systemPrompt, userPrompt, options, useSchema = options.responseSchema != null)
        return try {
            executeOpenAiAttempt(url, primaryPayload)
        } catch (e: ClientRequestException) {
            if (shouldFallbackFromSchema(e, options, isOllama = false)) {
                val fallbackPayload = buildOpenAiPayload(systemPrompt, userPrompt, options, useSchema = false)
                executeOpenAiAttempt(url, fallbackPayload)
            } else {
                throw e
            }
        }
    }

    private fun buildOpenAiPayload(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions,
        useSchema: Boolean
    ): OpenAiRequest {
        val responseFormat = if (useSchema && options.responseSchema != null) {
            buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", options.schemaName)
                    put("strict", true)
                    put("schema", options.responseSchema)
                })
            }
        } else {
            buildJsonObject { put("type", "json_object") }
        }
        return OpenAiRequest(
            model = config.modelName,
            messages = listOf(
                OpenAiMessage("system", systemPrompt),
                OpenAiMessage("user", userPrompt)
            ),
            temperature = config.temperature,
            responseFormat = responseFormat,
            maxTokens = options.maxTokens
        )
    }

    private suspend fun executeOpenAiAttempt(url: String, requestPayload: OpenAiRequest): String {
        val startMs = System.currentTimeMillis()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            if (config.providerType != LlmProviderType.BUILT_IN_LOCAL && config.apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${config.apiKey}")
            }
            setBody(requestPayload)
        }.body<OpenAiResponse>()
        val durationMs = System.currentTimeMillis() - startMs

        val firstChoice = response.choices.firstOrNull()
        emitTelemetry(
            LlmHttpTelemetry(
                providerType = config.providerType,
                model = config.modelName,
                durationMs = durationMs,
                finishReason = firstChoice?.finishReason,
                promptTokens = response.usage?.promptTokens,
                completionTokens = response.usage?.completionTokens
            )
        )

        if (firstChoice == null) {
            throw IllegalStateException("Empty or missing choices in OpenAI response")
        }
        return firstChoice.message.content.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Empty or missing message in OpenAI response")
    }

    private suspend fun executeOllama(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        val baseUrl = config.endpointUrl.trim().trimEnd('/')
        validateTransportSecurity(baseUrl)
        val url = if (baseUrl.endsWith("/api/chat")) baseUrl else "$baseUrl/api/chat"

        val primaryPayload = buildOllamaPayload(systemPrompt, userPrompt, options, useSchema = options.responseSchema != null)
        return try {
            executeOllamaAttempt(url, primaryPayload)
        } catch (e: ClientRequestException) {
            if (shouldFallbackFromSchema(e, options, isOllama = true)) {
                val fallbackPayload = buildOllamaPayload(systemPrompt, userPrompt, options, useSchema = false)
                executeOllamaAttempt(url, fallbackPayload)
            } else {
                throw e
            }
        }
    }

    private fun buildOllamaPayload(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions,
        useSchema: Boolean
    ): OllamaRequest {
        val formatElement: JsonElement = if (useSchema && options.responseSchema != null) {
            options.responseSchema
        } else {
            JsonPrimitive("json")
        }
        return OllamaRequest(
            model = config.modelName,
            messages = listOf(
                OpenAiMessage("system", systemPrompt),
                OpenAiMessage("user", userPrompt)
            ),
            stream = false,
            format = formatElement,
            options = OllamaOptions(
                temperature = config.temperature,
                numCtx = config.localContextSize.coerceIn(2048, 32768),
                numPredict = options.maxTokens ?: 4096
            )
        )
    }

    private suspend fun executeOllamaAttempt(url: String, requestPayload: OllamaRequest): String {
        val startMs = System.currentTimeMillis()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestPayload)
        }.body<OllamaResponse>()
        val durationMs = System.currentTimeMillis() - startMs

        emitTelemetry(
            LlmHttpTelemetry(
                providerType = LlmProviderType.OLLAMA,
                model = config.modelName,
                durationMs = durationMs,
                finishReason = response.doneReason,
                promptTokens = response.promptEvalCount,
                completionTokens = response.evalCount
            )
        )

        if (!response.error.isNullOrBlank()) {
            throw IllegalStateException("Ollama error: ${response.error}")
        }
        if (!response.doneReason.isNullOrBlank() && response.doneReason != "stop") {
            System.err.println("[KtorLlmClient] Ollama finished with done_reason=${response.doneReason}")
        }
        return response.message?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Empty or missing message in Ollama response")
    }

    private suspend fun shouldFallbackFromSchema(
        error: ClientRequestException,
        options: LlmCompletionOptions,
        isOllama: Boolean
    ): Boolean {
        if (options.responseSchema == null || !options.allowSchemaFallback) return false
        val status = error.response.status
        if (status != HttpStatusCode.BadRequest && status != HttpStatusCode.UnprocessableEntity) return false
        val bodyText = try {
            error.response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val matchesSchemaError = bodyText.contains("response_format", ignoreCase = true) ||
            bodyText.contains("json_schema", ignoreCase = true) ||
            bodyText.contains("schema", ignoreCase = true) ||
            bodyText.contains("grammar", ignoreCase = true) ||
            bodyText.contains("structured", ignoreCase = true) ||
            bodyText.contains("unsupported", ignoreCase = true)
        return matchesSchemaError || (isOllama && bodyText.contains("format", ignoreCase = true))
    }

    private suspend fun emitTelemetry(telemetry: LlmHttpTelemetry) {
        onTelemetry?.invoke(telemetry)
        currentCoroutineContext()[LlmTelemetryElement]?.record(telemetry)
    }

    private fun validateTransportSecurity(baseUrl: String) {
        validateEndpointTransportSecurity(baseUrl)
    }

    override fun close() {
        client.close()
        if (ownsEngine) {
            runCatching { resolvedEngine.close() }
        }
    }

    companion object {
        internal fun validateEndpointTransportSecurity(baseUrl: String) {
            val parsed = runCatching { URI(baseUrl.trim()) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
            val hasUserInfo = !parsed?.userInfo.isNullOrBlank()
            require(!hasUserInfo && (scheme == "https" || (scheme == "http" && isStrictLoopbackHttpUrl(baseUrl)))) {
                "Remote LLM endpoints must use https:// (cleartext http:// is permitted only for local loopback endpoints)."
            }
        }

        internal fun isStrictLoopbackHttpUrl(baseUrl: String): Boolean {
            val parsed = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" || !parsed.userInfo.isNullOrBlank()) return false
            val rawHost = parsed.host?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase(Locale.ROOT)
                ?: return false
            if (rawHost == "localhost" || rawHost.endsWith(".localhost") || rawHost == "127.0.0.1" || rawHost == "::1") {
                return true
            }
            val looksLikeIpLiteral = rawHost.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || ':' in rawHost
            if (!looksLikeIpLiteral) return false
            return runCatching { InetAddress.getByName(rawHost).isLoopbackAddress }.getOrDefault(false)
        }
    }
}
