package com.wechatdigest.core.llm

import kotlinx.serialization.Serializable

@Serializable
enum class LlmProviderType {
    OPENAI_COMPATIBLE,
    OLLAMA
}

@Serializable
data class LlmConfig(
    val providerType: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
    val endpointUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val timeoutSeconds: Long = 180
)
