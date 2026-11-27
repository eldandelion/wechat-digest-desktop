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

/** Controls which newly extracted event priorities trigger desktop system notifications. */
@Serializable
enum class NotificationPriorityThreshold(val displayName: String) {
    ALL("All events"),
    CRITICAL_AND_IMPORTANT("Critical & Important"),
    CRITICAL_ONLY("Critical only");

    fun matches(priority: com.wechatdigest.core.model.EventPriority): Boolean = when (this) {
        ALL -> true
        CRITICAL_AND_IMPORTANT -> priority == com.wechatdigest.core.model.EventPriority.CRITICAL ||
            priority == com.wechatdigest.core.model.EventPriority.IMPORTANT
        CRITICAL_ONLY -> priority == com.wechatdigest.core.model.EventPriority.CRITICAL
    }
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
    val notificationThreshold: NotificationPriorityThreshold = NotificationPriorityThreshold.CRITICAL_AND_IMPORTANT,
    val notifyOnManualSync: Boolean = false,
    val prefilter: PrefilterConfig = PrefilterConfig(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val parseAttachmentContents: Boolean = true,
    val enableVisualOcr: Boolean = true
)

