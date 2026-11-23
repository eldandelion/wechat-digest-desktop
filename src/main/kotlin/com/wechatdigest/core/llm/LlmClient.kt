package com.wechatdigest.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Locale

// OpenAI Request / Response DTOs
@Serializable
internal data class OpenAiMessage(val role: String = "", val content: String = "")

@Serializable
internal data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double,
    @SerialName("response_format") val responseFormat: JsonObject? = null
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
    val format: String = "json",
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
}

/** Optional capability interface allowing decorated clients to propagate provider concurrency caps. */
interface ConcurrencyAwareLlmClient : LlmClient {
    val recommendedConcurrency: Int
}

class KtorLlmClient(
    val config: LlmConfig,
    engine: HttpClientEngine = CIO.create(),
    private val onTelemetry: ((LlmHttpTelemetry) -> Unit)? = null
) : ConcurrencyAwareLlmClient {

    override val recommendedConcurrency: Int
        get() = EventExtractor.resolveConcurrencyForConfig(config)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(json)
        }
        val effectiveTimeoutSeconds = if (config.providerType == LlmProviderType.OLLAMA) {
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

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String {
        return when (config.providerType) {
            LlmProviderType.OPENAI_COMPATIBLE -> executeOpenAi(systemPrompt, userPrompt)
            LlmProviderType.OLLAMA -> executeOllama(systemPrompt, userPrompt)
        }
    }

    private suspend fun executeOpenAi(systemPrompt: String, userPrompt: String): String {
        val baseUrl = config.endpointUrl.trim().trimEnd('/')
        validateTransportSecurity(baseUrl)
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val requestPayload = OpenAiRequest(
            model = config.modelName,
            messages = listOf(
                OpenAiMessage("system", systemPrompt),
                OpenAiMessage("user", userPrompt)
            ),
            temperature = config.temperature,
            responseFormat = buildJsonObject { put("type", "json_object") }
        )

        val startMs = System.currentTimeMillis()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${config.apiKey}")
            }
            setBody(requestPayload)
        }.body<OpenAiResponse>()
        val durationMs = System.currentTimeMillis() - startMs

        val firstChoice = response.choices.firstOrNull()
        onTelemetry?.invoke(
            LlmHttpTelemetry(
                providerType = LlmProviderType.OPENAI_COMPATIBLE,
                model = config.modelName,
                durationMs = durationMs,
                finishReason = firstChoice?.finishReason,
                promptTokens = response.usage?.promptTokens,
                completionTokens = response.usage?.completionTokens
            )
        )

        return firstChoice?.message?.content
            ?: throw IllegalStateException("Empty or missing choices in OpenAI response")
    }

    private suspend fun executeOllama(systemPrompt: String, userPrompt: String): String {
        val baseUrl = config.endpointUrl.trim().trimEnd('/')
        validateTransportSecurity(baseUrl)
        val url = if (baseUrl.endsWith("/api/chat")) baseUrl else "$baseUrl/api/chat"

        val requestPayload = OllamaRequest(
            model = config.modelName,
            messages = listOf(
                OpenAiMessage("system", systemPrompt),
                OpenAiMessage("user", userPrompt)
            ),
            stream = false,
            format = "json",
            options = OllamaOptions(
                temperature = config.temperature,
                // Provide enough context/output headroom for 15-message triage and detailed sections
                numCtx = 8192,
                numPredict = 4096
            )
        )

        val startMs = System.currentTimeMillis()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestPayload)
        }.body<OllamaResponse>()
        val durationMs = System.currentTimeMillis() - startMs

        onTelemetry?.invoke(
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

    private fun validateTransportSecurity(baseUrl: String) {
        val parsed = runCatching { URI(baseUrl) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || (scheme == "http" && EventExtractor.isLocalEndpoint(baseUrl))) {
            "Remote LLM endpoints must use https:// (cleartext http:// is permitted only for local loopback endpoints)."
        }
    }

    override fun close() {
        client.close()
    }
}
