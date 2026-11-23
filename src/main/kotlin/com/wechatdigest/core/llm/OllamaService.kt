package com.wechatdigest.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class OllamaModelDetails(
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null
)

@Serializable
data class OllamaModelItem(
    val name: String,
    val model: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: OllamaModelDetails? = null
)

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaModelItem> = emptyList()
)

data class LocalOllamaModel(
    val name: String,
    val parameterSize: String? = null,
    val sizeBytes: Long? = null
) {
    val subtitle: String get() {
        val parts = mutableListOf<String>()
        parameterSize?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        sizeBytes?.takeIf { it > 0 }?.let { parts.add(formatBytes(it)) }
        return parts.joinToString(" • ")
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            val gb = bytes.toDouble() / (1024 * 1024 * 1024)
            return if (gb >= 1.0) {
                String.format(Locale.US, "%.1f GB", gb)
            } else {
                val mb = bytes.toDouble() / (1024 * 1024)
                String.format(Locale.US, "%.0f MB", mb)
            }
        }
    }
}

sealed class OllamaDiscoveryResult {
    data class Success(val endpointUrl: String, val models: List<LocalOllamaModel>) : OllamaDiscoveryResult()
    data class NoModels(val endpointUrl: String) : OllamaDiscoveryResult()
    data class Unreachable(val attemptedUrls: List<String>, val reason: String) : OllamaDiscoveryResult()
}

class OllamaService(
    engine: HttpClientEngine = CIO.create(),
    private val timeoutMillis: Long = 3000L
) : AutoCloseable {

    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = (timeoutMillis / 2).coerceAtLeast(1000L)
        }
    }

    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/api/chat") -> trimmed.removeSuffix("/api/chat").trimEnd('/')
            trimmed.endsWith("/api/tags") -> trimmed.removeSuffix("/api/tags").trimEnd('/')
            else -> trimmed
        }
    }

    suspend fun listModels(endpointUrl: String): List<LocalOllamaModel> {
        val normalized = normalizeBaseUrl(endpointUrl)
        val response = client.get("$normalized/api/tags")
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Ollama returned status ${response.status}")
        }
        val tags = response.body<OllamaTagsResponse>()
        return tags.models.map { item ->
            LocalOllamaModel(
                name = item.name,
                parameterSize = item.details?.parameterSize,
                sizeBytes = item.size
            )
        }
    }

    suspend fun detectLocalOllama(preferredUrl: String? = null): OllamaDiscoveryResult {
        val candidates = buildList {
            preferredUrl?.takeIf { it.isNotBlank() }?.let { add(normalizeBaseUrl(it)) }
            if (!contains("http://localhost:11434")) add("http://localhost:11434")
            if (!contains("http://127.0.0.1:11434")) add("http://127.0.0.1:11434")
        }

        val attempted = mutableListOf<String>()
        var lastError: String? = null

        for (candidate in candidates) {
            attempted.add(candidate)
            try {
                val models = listModels(candidate)
                return if (models.isEmpty()) {
                    OllamaDiscoveryResult.NoModels(endpointUrl = candidate)
                } else {
                    OllamaDiscoveryResult.Success(endpointUrl = candidate, models = models)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val sanitized = LlmJsonUtils.sanitizeLogMessage(e.message ?: e.javaClass.simpleName)
                lastError = sanitized
                System.err.println("[OllamaService] Probe failed: $sanitized")
            }
        }

        return OllamaDiscoveryResult.Unreachable(
            attemptedUrls = attempted,
            reason = lastError ?: "Connection refused or timed out"
        )
    }

    override fun close() {
        client.close()
    }
}
