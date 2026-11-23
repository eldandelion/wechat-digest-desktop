package com.wechatdigest.core.repository

import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.attachment.WeChatFileMessageParser
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.decompress.ZstdDecompressor
import com.wechatdigest.core.model.WeChatMessage
import java.io.File
import java.io.FileNotFoundException

class WeChatMessageRepository(
    private val driver: WeChatDatabaseDriver,
    private val contactRepository: WeChatContactRepository,
    private val attachmentResolver: WeChatAttachmentResolver? = null
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
            val stats = readRoomShardBounds(dbFile, tableName) ?: continue
            val (maxTs, maxId) = stats
            val isNewer = bestShard == null ||
                maxTs > bestTimestamp ||
                (maxTs == bestTimestamp && maxId > bestLocalId)
            if (isNewer) {
                bestShard = dbFile
                bestTimestamp = maxTs
                bestLocalId = maxId
            }
        }
        return bestShard
    }

    private fun readRoomShardBounds(dbFile: File, tableName: String): Pair<Long, Long>? {
        return try {
            driver.openReadOnlyConnection(dbFile).use { conn ->
                val existsSql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                val hasTable = conn.prepareStatement(existsSql).use { stmt ->
                    stmt.setString(1, tableName)
                    stmt.executeQuery().use { rs -> rs.next() }
                }
                if (!hasTable) return null
                val maxSql = "SELECT COALESCE(MAX(create_time), 0), COALESCE(MAX(local_id), 0) FROM $tableName"
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(maxSql).use { rs ->
                        if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
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
            val hasLocalType = hasColumn(conn, tableName, "local_type")
            val localTypeSelect = if (hasLocalType) "local_type" else "1 AS local_type"
            val sql = "SELECT local_id, create_time, real_sender_id, $localTypeSelect, message_content FROM $tableName ORDER BY create_time DESC LIMIT ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(parseMessageRow(rs, contactsMap, name2IdMap, hasLocalType))
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
            val hasLocalType = hasColumn(conn, tableName, "local_type")
            val localTypeSelect = if (hasLocalType) "local_type" else "1 AS local_type"
            val sql = "SELECT local_id, create_time, real_sender_id, $localTypeSelect, message_content FROM $tableName WHERE local_id > ? ORDER BY local_id ASC LIMIT ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, sinceLocalId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(parseMessageRow(rs, contactsMap, name2IdMap, hasLocalType))
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

    private fun hasColumn(conn: java.sql.Connection, tableName: String, columnName: String): Boolean {
        return conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name").equals(columnName, ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                found
            }
        }
    }

    private fun parseMessageRow(
        rs: java.sql.ResultSet,
        contactsMap: Map<String, String>,
        name2IdMap: Map<Long, String>,
        hasLocalType: Boolean
    ): WeChatMessage {
        val localId = rs.getLong("local_id")
        val createTime = rs.getLong("create_time")
        val realSenderId = rs.getString("real_sender_id") ?: ""
        val localType = rs.getLong("local_type")
        val contentBytes = rs.getBytes("message_content") ?: ByteArray(0)

        val isCompressed = ZstdDecompressor.isZstdCompressed(contentBytes)
        val decompressed = ZstdDecompressor.decompressToString(contentBytes)
        val (senderId, initialText) = resolveSenderAndBody(realSenderId, decompressed, contactsMap, name2IdMap)
        val senderDisplayName = contactsMap[senderId] ?: senderId.ifBlank { "Unknown" }

        val attachments = extractRowAttachments(localType, initialText, createTime, hasLocalType)
        val textContent = if (attachments.isNotEmpty()) {
            WeChatFileMessageParser.formatAttachmentMarker(attachments.first())
        } else {
            initialText
        }

        return WeChatMessage(
            localId = localId,
            timestampSeconds = createTime,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            rawContent = decompressed,
            textContent = textContent,
            isZstdCompressed = isCompressed,
            localType = localType,
            attachments = attachments
        )
    }

    private fun resolveSenderAndBody(
        realSenderId: String,
        decompressed: String,
        contactsMap: Map<String, String>,
        name2IdMap: Map<Long, String>
    ): Pair<String, String> {
        val mappedSenderId = realSenderId.toLongOrNull()?.let { name2IdMap[it] }
        val baseSenderId = mappedSenderId ?: realSenderId
        if (decompressed.isEmpty() || !decompressed.take(40).contains(":\n")) {
            return baseSenderId to decompressed
        }
        val parts = decompressed.split(":\n", limit = 2)
        val candidatePrefix = parts[0].trim()
        val bodyWithoutPrefix = parts.getOrNull(1) ?: ""

        if (mappedSenderId != null) {
            return if (candidatePrefix == mappedSenderId) baseSenderId to bodyWithoutPrefix else baseSenderId to decompressed
        }
        if (candidatePrefix == realSenderId && realSenderId.isNotBlank()) {
            return baseSenderId to bodyWithoutPrefix
        }
        val canInferMissingSender = realSenderId.isBlank() || (realSenderId.toLongOrNull() != null && name2IdMap.isEmpty())
        val isKnownSenderPrefix = candidatePrefix in contactsMap ||
            candidatePrefix.startsWith("wxid_") ||
            WECHAT_ID_REGEX.matches(candidatePrefix)
        return if (canInferMissingSender && isKnownSenderPrefix) {
            candidatePrefix to bodyWithoutPrefix
        } else {
            baseSenderId to decompressed
        }
    }

    private fun extractRowAttachments(
        localType: Long,
        textContent: String,
        createTime: Long,
        hasLocalType: Boolean
    ): List<com.wechatdigest.core.model.EventAttachment> {
        val isCandidate = WeChatFileMessageParser.isCandidateFileMessage(
            localType = localType,
            content = textContent,
            allowLegacyXmlFallback = !hasLocalType
        )
        if (!isCandidate) return emptyList()
        val parsed = WeChatFileMessageParser.parseFileMessage(textContent, createTime) ?: return emptyList()
        return listOf(attachmentResolver?.resolve(parsed) ?: parsed)
    }

    companion object {
        private val WECHAT_ID_REGEX = Regex("^[A-Za-z0-9_@.-]{3,64}$")
    }
}
