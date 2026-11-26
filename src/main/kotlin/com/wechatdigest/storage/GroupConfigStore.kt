package com.wechatdigest.storage

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.GroupConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

class GroupConfigStore(private val db: AppDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Loads config for [roomId], returning null cleanly if not found or on storage read errors. */
    fun get(roomId: String): GroupConfig? {
        return try {
            getOrThrow(roomId)
        } catch (e: Exception) {
            System.err.println("[GroupConfigStore] Error getting config for room: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            null
        }
    }

    /** Loads config for [roomId] or throws on SQLite or malformed allowlist JSON errors. */
    fun getOrThrow(roomId: String): GroupConfig? {
        val sql = "SELECT room_id, custom_prompt, custom_persona, allowed_sender_ids, muted_sender_ids, updated_at FROM group_configs WHERE room_id = ?"
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, roomId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val resolvedRoomId = rs.getString("room_id") ?: return null
                        return GroupConfig(
                            roomId = resolvedRoomId,
                            customPrompt = rs.getString("custom_prompt") ?: "",
                            customPersona = rs.getString("custom_persona") ?: "",
                            allowedSenderIds = decodeSenderIdsStrict(rs.getString("allowed_sender_ids")),
                            mutedSenderIds = decodeSenderIdsStrict(rs.getString("muted_sender_ids")),
                            updatedAt = rs.getLong("updated_at")
                        )
                    }
                }
            }
        }
        return null
    }

    fun getAll(): Map<String, GroupConfig> {
        val sql = "SELECT room_id, custom_prompt, custom_persona, allowed_sender_ids, muted_sender_ids, updated_at FROM group_configs"
        val result = mutableMapOf<String, GroupConfig>()
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val roomId = rs.getString("room_id") ?: continue
                            result[roomId] = GroupConfig(
                                roomId = roomId,
                                customPrompt = rs.getString("custom_prompt") ?: "",
                                customPersona = rs.getString("custom_persona") ?: "",
                                allowedSenderIds = decodeSenderIds(rs.getString("allowed_sender_ids")),
                                mutedSenderIds = decodeSenderIds(rs.getString("muted_sender_ids")),
                                updatedAt = rs.getLong("updated_at")
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[GroupConfigStore] Error getting all configs: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
        return result
    }

    /** Saves [config] or throws on SQLite errors. */
    fun saveOrThrow(config: GroupConfig) {
        val sql = """
            INSERT OR REPLACE INTO group_configs (room_id, custom_prompt, custom_persona, allowed_sender_ids, muted_sender_ids, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val normalizedAllowed = config.allowedSenderIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedMuted = config.mutedSenderIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, config.roomId)
                stmt.setString(2, config.customPrompt)
                stmt.setString(3, config.customPersona)
                stmt.setString(4, json.encodeToString(normalizedAllowed))
                stmt.setString(5, json.encodeToString(normalizedMuted))
                stmt.setLong(6, config.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun save(config: GroupConfig) {
        try {
            saveOrThrow(config)
        } catch (e: Exception) {
            System.err.println("[GroupConfigStore] Error saving group config: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    private fun decodeSenderIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { decodeSenderIdsStrict(raw) }.getOrDefault(emptyList())
    }

    private fun decodeSenderIdsStrict(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = json.parseToJsonElement(raw).jsonArray
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    fun delete(roomId: String) {
        val sql = "DELETE FROM group_configs WHERE room_id = ?"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, roomId)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[GroupConfigStore] Error deleting config: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    fun clearAll() {
        val sql = "DELETE FROM group_configs"
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate(sql)
                }
            }
        } catch (e: Exception) {
            System.err.println("[GroupConfigStore] Error clearing all configs: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }
}
