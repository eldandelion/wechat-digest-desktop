package com.wechatdigest.core.llm

import kotlinx.serialization.Serializable

@Serializable
enum class LlmProviderType {
    OPENAI_COMPATIBLE,
    OLLAMA,
    BUILT_IN_LOCAL
}

@Serializable
data class LlmConfig(
    val providerType: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
    val endpointUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val timeoutSeconds: Long = 180,
    val localModelId: String = "",
    val localModelMirror: String = "HF_OFFICIAL",
    val localContextSize: Int = 8192,
    val localIdleUnloadMinutes: Int = 10
)
