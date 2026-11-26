package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.ConcurrencyAwareLlmClient
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmCompletionOptions
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmHttpTelemetry
import com.wechatdigest.core.llm.LlmProviderType
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/**
 * [LlmClient] implementation backed by the managed local `llama-server` sidecar.
 * Starts the server lazily on first completion request and reuses both a cached [KtorLlmClient]
 * and a shared CIO HTTP engine across completions during its lifetime.
 */
class ManagedLocalLlmClient(
    private val config: LlmConfig,
    private val store: LocalModelStore,
    private val serverManager: LlamaServerManager,
    private val onTelemetry: ((LlmHttpTelemetry) -> Unit)? = null,
    private val delegateFactory: ((LlmConfig) -> LlmClient)? = null
) : ConcurrencyAwareLlmClient {

    private data class CachedDelegate(
        val cacheKey: String,
        val client: LlmClient
    )

    private val clientLock = Any()

    @Volatile
    private var sharedEngine: HttpClientEngine? = null

    @Volatile
    private var cachedDelegate: CachedDelegate? = null

    @Volatile
    private var isClosed: Boolean = false

    override val recommendedConcurrency: Int = EventExtractor.DEFAULT_LOCAL_CONCURRENCY

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String =
        chatCompletion(systemPrompt, userPrompt, LlmCompletionOptions())

    override suspend fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        options: LlmCompletionOptions
    ): String {
        val modelId = config.localModelId.trim()
        if (modelId.isBlank()) {
            throw IllegalStateException("No built-in local model selected. Download or import a GGUF model in Settings.")
        }
        val resolved = store.resolveModel(modelId)
            ?: throw IllegalStateException("Selected local model is not available on disk. Download or re-select it in Settings.")

        return serverManager.withRunningServer(resolved, config) { baseUrl ->
            val delegatedConfig = config.copy(
                providerType = LlmProviderType.BUILT_IN_LOCAL,
                endpointUrl = "$baseUrl/v1",
                apiKey = "",
                modelName = resolved.displayName
            )
            val client = getOrCreateClient(delegatedConfig)
            client.chatCompletion(systemPrompt, userPrompt, options)
        }
    }

    private fun getOrCreateClient(delegatedConfig: LlmConfig): LlmClient {
        val key = "${delegatedConfig.endpointUrl}|${delegatedConfig.modelName}|${delegatedConfig.temperature}|${delegatedConfig.localContextSize}|${delegatedConfig.timeoutSeconds}"
        synchronized(clientLock) {
            check(!isClosed) { "ManagedLocalLlmClient is already closed." }
            val existing = cachedDelegate
            if (existing != null && existing.cacheKey == key) {
                return existing.client
            }
            val engine = sharedEngine ?: CIO.create().also { sharedEngine = it }
            val newClient = delegateFactory?.invoke(delegatedConfig)
                ?: KtorLlmClient(delegatedConfig, engine = engine, onTelemetry = onTelemetry)
            cachedDelegate = CachedDelegate(key, newClient)
            if (existing != null) {
                runCatching { existing.client.close() }
            }
            return newClient
        }
    }

    override fun close() {
        val (clientToClose, engineToClose) = synchronized(clientLock) {
            isClosed = true
            val c = cachedDelegate?.client.also { cachedDelegate = null }
            val e = sharedEngine.also { sharedEngine = null }
            c to e
        }
        if (clientToClose != null) {
            runCatching { clientToClose.close() }
        }
        if (engineToClose != null) {
            runCatching { engineToClose.close() }
        }
    }
}
