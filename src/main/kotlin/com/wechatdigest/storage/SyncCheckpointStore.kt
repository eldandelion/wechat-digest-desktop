package com.wechatdigest.storage

import com.wechatdigest.core.model.SyncCheckpoint

class SyncCheckpointStore(private val db: AppDatabase) {

    fun get(roomId: String): SyncCheckpoint? {
        val sql = "SELECT room_id, last_local_id, last_sync_timestamp, updated_at FROM sync_checkpoints WHERE room_id = ?"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, roomId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            return SyncCheckpoint(
                                roomId = rs.getString("room_id"),
                                lastLocalId = rs.getLong("last_local_id"),
                                lastSyncTimestamp = rs.getLong("last_sync_timestamp"),
                                updatedAt = rs.getLong("updated_at")
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[SyncCheckpointStore] Error getting checkpoint for $roomId: ${e.message}")
        }
        return null
    }

    fun getAll(): Map<String, SyncCheckpoint> {
        val sql = "SELECT room_id, last_local_id, last_sync_timestamp, updated_at FROM sync_checkpoints"
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
                                updatedAt = rs.getLong("updated_at")
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[SyncCheckpointStore] Error getting all checkpoints: ${e.message}")
        }
        return result
    }

    fun save(checkpoint: SyncCheckpoint) {
        val sql = """
            INSERT OR REPLACE INTO sync_checkpoints (room_id, last_local_id, last_sync_timestamp, updated_at)
            VALUES (?, ?, ?, ?)
        """.trimIndent()
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, checkpoint.roomId)
                    stmt.setLong(2, checkpoint.lastLocalId)
                    stmt.setLong(3, checkpoint.lastSyncTimestamp)
                    stmt.setLong(4, checkpoint.updatedAt)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[SyncCheckpointStore] Error saving checkpoint for ${checkpoint.roomId}: ${e.message}")
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
            System.err.println("[SyncCheckpointStore] Error resetting checkpoint for $roomId: ${e.message}")
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
            System.err.println("[SyncCheckpointStore] Error resetting all checkpoints: ${e.message}")
        }
    }
}
