package com.wechatdigest.storage

import com.wechatdigest.app.AppSettings
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.repository.WeChatMessageRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsStore(private val db: AppDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): AppSettings {
        val query = "SELECT value FROM settings WHERE key = ?"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(query).use { stmt ->
                    stmt.setString(1, SETTINGS_KEY)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        val raw = rs.getString("value")
                        if (!raw.isNullOrBlank()) {
                            val decoded = json.decodeFromString<AppSettings>(raw)
                            return decoded.copy(
                                messagesPerSync = decoded.messagesPerSync.coerceIn(
                                    WeChatMessageRepository.MIN_MESSAGES_PER_SYNC,
                                    WeChatMessageRepository.MAX_MESSAGES_PER_SYNC
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[SettingsStore] Failed to load settings: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
        return AppSettings()
    }

    fun save(settings: AppSettings) {
        val normalized = settings.copy(
            messagesPerSync = settings.messagesPerSync.coerceIn(
                WeChatMessageRepository.MIN_MESSAGES_PER_SYNC,
                WeChatMessageRepository.MAX_MESSAGES_PER_SYNC
            )
        )
        val raw = json.encodeToString(normalized)
        val sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, SETTINGS_KEY)
                    stmt.setString(2, raw)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[SettingsStore] Failed to save settings: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    fun clear() {
        val sql = "DELETE FROM settings"
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate(sql)
                }
            }
        } catch (e: Exception) {
            System.err.println("[SettingsStore] Failed to clear settings: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    companion object {
        private const val SETTINGS_KEY = "app_settings"
    }
}
