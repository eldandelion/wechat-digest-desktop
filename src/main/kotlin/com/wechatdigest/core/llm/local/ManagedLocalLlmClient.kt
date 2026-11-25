package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.ConcurrencyAwareLlmClient
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/**
 * [LlmClient] implementation backed by the managed local `llama-server` sidecar.
 * Starts the server lazily on first completion request and reuses a shared CIO HTTP engine
 * across completions during its lifetime.
 */
class ManagedLocalLlmClient(
    private val config: LlmConfig,
    private val store: LocalModelStore,
    private val serverManager: LlamaServerManager,
    private val delegateFactory: ((LlmConfig) -> LlmClient)? = null
) : ConcurrencyAwareLlmClient {

    private val engineLock = Any()

    @Volatile
    private var sharedEngine: HttpClientEngine? = null

    @Volatile
    private var isClosed: Boolean = false

    override val recommendedConcurrency: Int = EventExtractor.DEFAULT_LOCAL_CONCURRENCY

    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String {
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
            val client = delegateFactory?.invoke(delegatedConfig)
                ?: KtorLlmClient(delegatedConfig, engine = getOrCreateEngine())
            client.use { it.chatCompletion(systemPrompt, userPrompt) }
        }
    }

    private fun getOrCreateEngine(): HttpClientEngine {
        synchronized(engineLock) {
            check(!isClosed) { "ManagedLocalLlmClient is already closed." }
            return sharedEngine ?: CIO.create().also { sharedEngine = it }
        }
    }

    override fun close() {
        val toClose = synchronized(engineLock) {
            isClosed = true
            sharedEngine.also { sharedEngine = null }
        }
        if (toClose != null) {
            runCatching { toClose.close() }
        }
    }
}
