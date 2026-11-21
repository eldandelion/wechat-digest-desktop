package com.wechatdigest.storage

import com.wechatdigest.app.AppSettings
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
                            return json.decodeFromString<AppSettings>(raw)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[SettingsStore] Failed to load settings: ${e.message}")
        }
        return AppSettings()
    }

    fun save(settings: AppSettings) {
        val raw = json.encodeToString(settings)
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
            System.err.println("[SettingsStore] Failed to save settings: ${e.message}")
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
            System.err.println("[SettingsStore] Failed to clear settings: ${e.message}")
        }
    }

    companion object {
        private const val SETTINGS_KEY = "app_settings"
    }
}
