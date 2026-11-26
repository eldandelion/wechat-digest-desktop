package com.wechatdigest.storage

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.SyncCheckpoint

class SyncCheckpointStore(private val db: AppDatabase) {

    /** Returns the saved checkpoint for [roomId], or throws on SQLite error so sync callers fail closed. */
    fun getOrThrow(roomId: String): SyncCheckpoint? {
        val sql = """
            SELECT room_id, last_local_id, last_sync_timestamp, updated_at, last_shard_name, last_create_time
            FROM sync_checkpoints WHERE room_id = ?
        """.trimIndent()
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, roomId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return SyncCheckpoint(
                            roomId = rs.getString("room_id"),
                            lastLocalId = rs.getLong("last_local_id"),
                            lastSyncTimestamp = rs.getLong("last_sync_timestamp"),
                            updatedAt = rs.getLong("updated_at"),
                            lastShardName = rs.getString("last_shard_name") ?: "",
                            lastCreateTime = rs.getLong("last_create_time")
                        )
                    }
                }
            }
        }
        return null
    }

    fun get(roomId: String): SyncCheckpoint? {
        return try {
            getOrThrow(roomId)
        } catch (e: Exception) {
            System.err.println(
                "[SyncCheckpointStore] Error getting checkpoint for ${LlmJsonUtils.sanitizeLogMessage(roomId)}: " +
                    LlmJsonUtils.sanitizeLogMessage(e.message)
            )
            null
        }
    }

    fun getAll(): Map<String, SyncCheckpoint> {
        val sql = "SELECT room_id, last_local_id, last_sync_timestamp, updated_at, last_shard_name, last_create_time FROM sync_checkpoints"
        val result = mutableMapOf<String, SyncCheckpoint>()
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val roomId = rs.getString("room_id") ?: continue
                            result[roomId] = SyncCheckpoint(
                                roomId = roomId,
                                lastLocalId = rs.getLong("last_local_id"),
                                lastSyncTimestamp = rs.getLong("last_sync_timestamp"),
                                updatedAt = rs.getLong("updated_at"),
                                lastShardName = rs.getString("last_shard_name") ?: "",
                                lastCreateTime = rs.getLong("last_create_time")
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[SyncCheckpointStore] Error getting all checkpoints: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
        return result
    }

    /** Persists [checkpoint] in SQLite, throwing on error so callers avoid silent checkpoint drift. */
    fun saveOrThrow(checkpoint: SyncCheckpoint) {
        val sql = """
            INSERT OR REPLACE INTO sync_checkpoints (
                room_id, last_local_id, last_sync_timestamp, updated_at, last_shard_name, last_create_time
            )
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, checkpoint.roomId)
                stmt.setLong(2, checkpoint.lastLocalId)
                stmt.setLong(3, checkpoint.lastSyncTimestamp)
                stmt.setLong(4, checkpoint.updatedAt)
                stmt.setString(5, checkpoint.lastShardName)
                stmt.setLong(6, checkpoint.lastCreateTime)
                stmt.executeUpdate()
            }
        }
    }

    fun save(checkpoint: SyncCheckpoint) {
        try {
            saveOrThrow(checkpoint)
        } catch (e: Exception) {
            System.err.println(
                "[SyncCheckpointStore] Error saving checkpoint for ${LlmJsonUtils.sanitizeLogMessage(checkpoint.roomId)}: " +
                    LlmJsonUtils.sanitizeLogMessage(e.message)
            )
        }
    }

    fun reset(roomId: String) {
        val sql = "DELETE FROM sync_checkpoints WHERE room_id = ?"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, roomId)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println(
                "[SyncCheckpointStore] Error resetting checkpoint for ${LlmJsonUtils.sanitizeLogMessage(roomId)}: " +
                    LlmJsonUtils.sanitizeLogMessage(e.message)
            )
        }
    }

    fun resetAll() {
        val sql = "DELETE FROM sync_checkpoints"
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate(sql)
                }
            }
        } catch (e: Exception) {
            System.err.println("[SyncCheckpointStore] Error resetting all checkpoints: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }
}
