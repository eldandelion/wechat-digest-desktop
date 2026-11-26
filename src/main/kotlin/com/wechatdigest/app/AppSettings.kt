package com.wechatdigest.app

import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.sanitizer.PrefilterConfig
import kotlinx.serialization.Serializable

/** User-selectable desktop theme mode persisted in settings. */
@Serializable
enum class AppThemeMode(val displayName: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Persistent application settings stored in [SettingsStore].
 *
 * @property themeMode Active desktop theme override (`SYSTEM`, `LIGHT`, or `DARK`).
 * @property parseAttachmentContents Whether bounded previews from downloaded attachments are shared with the LLM.
 */
@Serializable
data class AppSettings(
    val wechatDataDir: String = "",
    val keysPath: String = "",
    val wechatAttachmentRootDir: String = "",
    val llm: LlmConfig = LlmConfig(),
    val monitoredRoomIds: Set<String> = emptySet(),
    val userPersona: String = "international student studying at university",
    val targetLanguage: String = "en",
    val messagesPerSync: Int = 50,
    val autoSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 30,
    val notifyOnNewEvents: Boolean = true,
    val prefilter: PrefilterConfig = PrefilterConfig(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val parseAttachmentContents: Boolean = true
)

