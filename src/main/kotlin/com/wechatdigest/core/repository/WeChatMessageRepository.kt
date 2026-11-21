package com.wechatdigest.core.repository

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.decompress.ZstdDecompressor
import com.wechatdigest.core.model.WeChatMessage
import java.io.File
import java.io.FileNotFoundException

class WeChatMessageRepository(
    private val driver: WeChatDatabaseDriver,
    private val contactRepository: WeChatContactRepository
) {

    private val messageDir: File by lazy {
        val nested = File(driver.baseDir, "message")
        if (nested.exists() && nested.isDirectory) nested else {
            driver.baseDir
        }
    }

    /**
     * Scans message_*.db shards to find which database contains the chatroom's table.
     */
    fun findDatabaseShardForRoom(roomId: String): File? {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val candidateFiles = messageDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("message_") &&
                file.name.endsWith(".db") &&
                file.name != "message_fts.db"
        } ?: return null

        var bestShard: File? = null
        var bestTimestamp = Long.MIN_VALUE
        var bestLocalId = Long.MIN_VALUE

        for (dbFile in candidateFiles.sortedByDescending { it.name }) {
            try {
                driver.openReadOnlyConnection(dbFile).use { conn ->
                    val existsSql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                    val hasTable = conn.prepareStatement(existsSql).use { stmt ->
                        stmt.setString(1, tableName)
                        stmt.executeQuery().use { rs -> rs.next() }
                    }
                    if (hasTable) {
                        val maxSql = "SELECT COALESCE(MAX(create_time), 0), COALESCE(MAX(local_id), 0) FROM $tableName"
                        val (maxTs, maxId) = conn.createStatement().use { stmt ->
                            stmt.executeQuery(maxSql).use { rs ->
                                if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L
                            }
                        }

                        if (bestShard == null ||
                            maxTs > bestTimestamp ||
                            (maxTs == bestTimestamp && maxId > bestLocalId)
                        ) {
                            bestShard = dbFile
                            bestTimestamp = maxTs
                            bestLocalId = maxId
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue scanning other shards if one fails
                continue
            }
        }
        return bestShard
    }

    /**
     * Fetches the latest messages for a chatroom and resolves sender names and decompresses content.
     */
    fun getRecentMessages(roomId: String, limit: Int = 30): List<WeChatMessage> {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shardFile = findDatabaseShardForRoom(roomId)
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val contactsMap = contactRepository.loadContactsMap()
        val messages = mutableListOf<WeChatMessage>()

        driver.openReadOnlyConnection(shardFile).use { conn ->
            val name2IdMap = loadShardNameToIdMap(conn)
            val sql = "SELECT local_id, create_time, real_sender_id, message_content FROM $tableName ORDER BY create_time DESC LIMIT ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(parseMessageRow(rs, contactsMap, name2IdMap))
                    }
                }
            }
        }

        // Return chronologically (oldest to newest)
        return messages.reversed()
    }

    /**
     * Fetches new messages strictly arriving after `sinceLocalId` in chronological order (oldest to newest).
     */
    fun getMessagesSince(roomId: String, sinceLocalId: Long, limit: Int = 50): List<WeChatMessage> {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shardFile = findDatabaseShardForRoom(roomId)
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val contactsMap = contactRepository.loadContactsMap()
        val messages = mutableListOf<WeChatMessage>()

        driver.openReadOnlyConnection(shardFile).use { conn ->
            val name2IdMap = loadShardNameToIdMap(conn)
            val sql = "SELECT local_id, create_time, real_sender_id, message_content FROM $tableName WHERE local_id > ? ORDER BY local_id ASC LIMIT ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, sinceLocalId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(parseMessageRow(rs, contactsMap, name2IdMap))
                    }
                }
            }
        }

        return messages
    }

    private fun loadShardNameToIdMap(conn: java.sql.Connection): Map<Long, String> {
        val hasName2Id = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='Name2Id'").use { rs -> rs.next() }
        }
        if (!hasName2Id) return emptyMap()
        val map = mutableMapOf<Long, String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT rowid, user_name FROM Name2Id").use { rs ->
                while (rs.next()) {
                    val userName = rs.getString("user_name")?.takeIf { it.isNotBlank() } ?: continue
                    map[rs.getLong("rowid")] = userName
                }
            }
        }
        return map
    }

    private fun parseMessageRow(
        rs: java.sql.ResultSet,
        contactsMap: Map<String, String>,
        name2IdMap: Map<Long, String>
    ): WeChatMessage {
        val localId = rs.getLong("local_id")
        val createTime = rs.getLong("create_time")
        val realSenderId = rs.getString("real_sender_id") ?: ""
        val contentBytes = rs.getBytes("message_content") ?: ByteArray(0)

        val isCompressed = ZstdDecompressor.isZstdCompressed(contentBytes)
        val decompressed = ZstdDecompressor.decompressToString(contentBytes)

        // Keep Name2Id sender authoritative when present; only parse inline "wxid:\n" headers when unmapped or matching.
        val mappedSenderId = realSenderId.toLongOrNull()?.let { name2IdMap[it] }
        var senderId = mappedSenderId ?: realSenderId
        var textContent = decompressed
        if (decompressed.isNotEmpty() && decompressed.take(40).contains(":\n")) {
            val parts = decompressed.split(":\n", limit = 2)
            val candidatePrefix = parts[0].trim()
            if (mappedSenderId != null) {
                if (candidatePrefix == mappedSenderId) {
                    textContent = parts.getOrNull(1) ?: ""
                }
            } else if (
                candidatePrefix in contactsMap ||
                candidatePrefix.startsWith("wxid_") ||
                (realSenderId.isBlank() && WECHAT_ID_REGEX.matches(candidatePrefix))
            ) {
                senderId = candidatePrefix
                textContent = parts.getOrNull(1) ?: ""
            }
        }

        val senderDisplayName = contactsMap[senderId] ?: senderId.ifBlank { "Unknown" }

        return WeChatMessage(
            localId = localId,
            timestampSeconds = createTime,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            rawContent = decompressed,
            textContent = textContent,
            isZstdCompressed = isCompressed
        )
    }

    companion object {
        private val WECHAT_ID_REGEX = Regex("^[A-Za-z0-9_@.-]{3,64}$")
    }
}
