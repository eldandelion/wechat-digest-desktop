package com.wechatdigest.app

import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.sanitizer.PrefilterConfig
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val wechatDataDir: String = "",
    val keysPath: String = "",
    val llm: LlmConfig = LlmConfig(),
    val monitoredRoomIds: Set<String> = emptySet(),
    val userPersona: String = "international student studying at university",
    val targetLanguage: String = "en",
    val messagesPerSync: Int = 50,
    val autoSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 30,
    val notifyOnNewEvents: Boolean = true,
    val prefilter: PrefilterConfig = PrefilterConfig()
)

