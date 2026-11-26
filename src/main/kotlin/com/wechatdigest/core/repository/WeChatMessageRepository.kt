package com.wechatdigest.core.repository

import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.attachment.WeChatFileMessageParser
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.decompress.ZstdDecompressor
import com.wechatdigest.core.model.SyncCheckpoint
import com.wechatdigest.core.model.WeChatMessage
import java.io.File
import java.io.FileNotFoundException

class WeChatMessageRepository(
    private val driver: WeChatDatabaseDriver,
    private val contactRepository: WeChatContactRepository,
    private val attachmentResolver: WeChatAttachmentResolver? = null,
    private val sessionContext: WeChatSessionContext? = null
) {

    /** Result of an incremental checkpoint-aware message read across one or more message shards. */
    data class IncrementalReadResult(
        val messages: List<WeChatMessage>,
        val latestShardName: String,
        val latestLocalId: Long,
        val latestCreateTime: Long
    )

    private data class RoomShard(
        val shardFile: File,
        val bounds: WeChatSessionContext.RoomShardTableBounds,
        val name2IdMap: Map<Long, String>
    )

    private data class ShardMessage(
        val shardName: String,
        val message: WeChatMessage
    )

    private data class CrossShardFingerprint(
        val timestampSeconds: Long,
        val senderId: String,
        val rawContent: String
    )

    private val messageDir: File by lazy {
        val nested = File(driver.baseDir, "message")
        if (nested.exists() && nested.isDirectory) nested else {
            driver.baseDir
        }
    }

    private fun listCandidateShardFiles(): List<File> {
        val files = messageDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("message_") &&
                file.name.endsWith(".db") &&
                file.name != "message_fts.db"
        } ?: return emptyList()
        return files.sortedBy { it.name }
    }

    private fun scanAllShardsForIndex(): List<WeChatSessionContext.MessageShardIndexEntry> {
        val candidates = listCandidateShardFiles()
        if (candidates.isEmpty()) return emptyList()
        val entries = mutableListOf<WeChatSessionContext.MessageShardIndexEntry>()
        for (dbFile in candidates) {
            try {
                driver.openReadOnlyConnection(dbFile, sessionContext).use { conn ->
                    val name2Id = loadShardNameToIdMap(conn)
                    val msgTables = mutableListOf<String>()
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                            while (rs.next()) {
                                val name = rs.getString(1) ?: continue
                                if (MSG_TABLE_REGEX.matches(name)) {
                                    msgTables.add(name)
                                }
                            }
                        }
                    }
                    val boundsMap = HashMap<String, WeChatSessionContext.RoomShardTableBounds>(msgTables.size)
                    for (tableName in msgTables) {
                        val bounds = readTableBounds(conn, tableName) ?: continue
                        boundsMap[tableName] = bounds
                    }
                    entries.add(
                        WeChatSessionContext.MessageShardIndexEntry(
                            shardFile = dbFile,
                            name2IdMap = name2Id,
                            tableBounds = boundsMap
                        )
                    )
                }
            } catch (_: Exception) {
                // Skip unreadable/corrupt shard and continue scanning others
            }
        }
        return entries
    }

    private fun readTableBounds(
        conn: java.sql.Connection,
        tableName: String
    ): WeChatSessionContext.RoomShardTableBounds? {
        return try {
            val sql = "SELECT COALESCE(MIN(create_time), 0), COALESCE(MAX(create_time), 0), COALESCE(MAX(local_id), 0) FROM $tableName"
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    if (rs.next()) {
                        WeChatSessionContext.RoomShardTableBounds(
                            minCreateTime = rs.getLong(1),
                            maxCreateTime = rs.getLong(2),
                            maxLocalId = rs.getLong(3)
                        )
                    } else {
                        WeChatSessionContext.RoomShardTableBounds(0L, 0L, 0L)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findShardsForTable(tableName: String): List<RoomShard> {
        val index = sessionContext?.getOrLoadShardIndex { scanAllShardsForIndex() }
        if (index != null) {
            return index.mapNotNull { entry ->
                val bounds = entry.tableBounds[tableName] ?: return@mapNotNull null
                RoomShard(entry.shardFile, bounds, entry.name2IdMap)
            }
        }
        val candidates = listCandidateShardFiles()
        if (candidates.isEmpty()) return emptyList()
        val result = mutableListOf<RoomShard>()
        for (dbFile in candidates) {
            try {
                driver.openReadOnlyConnection(dbFile, sessionContext).use { conn ->
                    val existsSql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                    val hasTable = conn.prepareStatement(existsSql).use { stmt ->
                        stmt.setString(1, tableName)
                        stmt.executeQuery().use { rs -> rs.next() }
                    }
                    if (!hasTable) return@use
                    val bounds = readTableBounds(conn, tableName) ?: return@use
                    val name2Id = loadShardNameToIdMap(conn)
                    result.add(RoomShard(dbFile, bounds, name2Id))
                }
            } catch (_: Exception) {
                // Ignore unreadable shard
            }
        }
        return result
    }

    private fun newestShardComparator(): Comparator<RoomShard> {
        return compareByDescending<RoomShard> { it.bounds.maxCreateTime }
            .thenByDescending { it.bounds.maxLocalId }
            .thenByDescending { shardNumericOrder(it.shardFile.name) }
            .thenByDescending { it.shardFile.name }
    }

    private fun chronologicalShardComparator(): Comparator<RoomShard> {
        return compareBy<RoomShard> { it.bounds.minCreateTime }
            .thenBy { it.bounds.maxCreateTime }
            .thenBy { shardNumericOrder(it.shardFile.name) }
            .thenBy { it.shardFile.name }
    }

    /**
     * Scans message_*.db shards to find which database contains the chatroom's newest messages.
     */
    fun findDatabaseShardForRoom(roomId: String): File? {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shards = findShardsForTable(tableName)
        return shards.minWithOrNull(newestShardComparator())?.shardFile
    }

    /**
     * Fetches the latest messages for a chatroom and resolves sender names and decompresses content.
     */
    fun getRecentMessages(roomId: String, limit: Int = 30): List<WeChatMessage> {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shards = findShardsForTable(tableName)
        val shard = shards.minWithOrNull(newestShardComparator())
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val contactsMap = contactRepository.loadContactsMap()
        return readRecentFromShard(shard, tableName, contactsMap, limit)
    }

    /**
     * Fetches new messages strictly arriving after `sinceLocalId` in chronological order (oldest to newest).
     */
    fun getMessagesSince(roomId: String, sinceLocalId: Long, limit: Int = 50): List<WeChatMessage> {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shards = findShardsForTable(tableName)
        val shard = shards.minWithOrNull(newestShardComparator())
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val contactsMap = contactRepository.loadContactsMap()
        return readSinceLocalIdFromShard(shard, tableName, sinceLocalId, contactsMap, limit)
    }

    /**
     * Fetches incremental messages for [roomId] using shard-aware [checkpoint], draining any unread tail on
     * [SyncCheckpoint.lastShardName] and continuing chronologically across newer rolled-over shards up to [limit].
     * Advances cursor metadata strictly to the last message actually returned in this batch.
     */
    fun getMessagesSinceCheckpoint(
        roomId: String,
        checkpoint: SyncCheckpoint?,
        limit: Int = 50
    ): IncrementalReadResult {
        val safeLimit = limit.coerceAtLeast(1)
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shards = findShardsForTable(tableName)
        val newestShard = shards.minWithOrNull(newestShardComparator())
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val nonEmptyShards = shards.filter { it.bounds.maxLocalId > 0L }.sortedWith(chronologicalShardComparator())
        if (nonEmptyShards.isEmpty()) {
            return IncrementalReadResult(
                messages = emptyList(),
                latestShardName = newestShard.shardFile.name,
                latestLocalId = checkpoint?.lastLocalId ?: 0L,
                latestCreateTime = checkpoint?.lastCreateTime ?: 0L
            )
        }

        val contactsMap = contactRepository.loadContactsMap()

        if (checkpoint == null || checkpoint.lastLocalId <= 0L) {
            val recent = readRecentFromShard(newestShard, tableName, contactsMap, safeLimit)
            if (recent.isEmpty()) {
                return IncrementalReadResult(emptyList(), newestShard.shardFile.name, 0L, 0L)
            }
            return IncrementalReadResult(
                messages = recent,
                latestShardName = newestShard.shardFile.name,
                latestLocalId = recent.maxOf { it.localId },
                latestCreateTime = recent.maxOf { it.timestampSeconds }
            )
        }

        if (checkpoint.lastShardName.isNotBlank()) {
            val startIdx = nonEmptyShards.indexOfFirst { it.shardFile.name == checkpoint.lastShardName }
            if (startIdx >= 0) {
                return readAcrossShardsFromIndex(
                    orderedShards = nonEmptyShards,
                    startIdx = startIdx,
                    sinceLocalIdOnStart = checkpoint.lastLocalId,
                    tableName = tableName,
                    contactsMap = contactsMap,
                    limit = safeLimit,
                    fallbackCheckpoint = checkpoint
                )
            }
            // Saved shard is no longer present: advance safely by create_time across remaining shards
            val collected = mutableListOf<ShardMessage>()
            val seenFingerprints = HashSet<CrossShardFingerprint>()
            val candidateShards = if (checkpoint.lastCreateTime > 0L) {
                nonEmptyShards.filter { it.bounds.maxCreateTime > checkpoint.lastCreateTime }
            } else {
                nonEmptyShards
            }
            for (shard in candidateShards) {
                val remaining = safeLimit - collected.size
                if (remaining <= 0) break
                val batch = readSinceCreateTimeFromShard(shard, tableName, checkpoint.lastCreateTime, contactsMap, remaining)
                appendDeduplicatedShardMessages(shard.shardFile.name, batch, collected, seenFingerprints)
            }
            if (collected.isEmpty()) {
                return IncrementalReadResult(
                    messages = emptyList(),
                    latestShardName = checkpoint.lastShardName,
                    latestLocalId = checkpoint.lastLocalId,
                    latestCreateTime = checkpoint.lastCreateTime
                )
            }
            val last = collected.last()
            return IncrementalReadResult(
                messages = collected.map { it.message },
                latestShardName = last.shardName,
                latestLocalId = last.message.localId,
                latestCreateTime = last.message.timestampSeconds
            )
        }

        // Legacy v1.1 checkpoint without lastShardName
        if (newestShard.bounds.maxLocalId >= checkpoint.lastLocalId) {
            val msgs = readSinceLocalIdFromShard(newestShard, tableName, checkpoint.lastLocalId, contactsMap, safeLimit)
            if (msgs.isEmpty()) {
                return IncrementalReadResult(emptyList(), newestShard.shardFile.name, checkpoint.lastLocalId, 0L)
            }
            val last = msgs.last()
            return IncrementalReadResult(
                messages = msgs,
                latestShardName = newestShard.shardFile.name,
                latestLocalId = last.localId,
                latestCreateTime = last.timestampSeconds
            )
        }

        val legacyShardIdx = nonEmptyShards.indexOfLast { it.bounds.maxLocalId >= checkpoint.lastLocalId }
        if (legacyShardIdx >= 0) {
            return readAcrossShardsFromIndex(
                orderedShards = nonEmptyShards,
                startIdx = legacyShardIdx,
                sinceLocalIdOnStart = checkpoint.lastLocalId,
                tableName = tableName,
                contactsMap = contactsMap,
                limit = safeLimit,
                fallbackCheckpoint = checkpoint.copy(lastShardName = nonEmptyShards[legacyShardIdx].shardFile.name)
            )
        }

        return IncrementalReadResult(emptyList(), newestShard.shardFile.name, checkpoint.lastLocalId, 0L)
    }

    private fun readAcrossShardsFromIndex(
        orderedShards: List<RoomShard>,
        startIdx: Int,
        sinceLocalIdOnStart: Long,
        tableName: String,
        contactsMap: Map<String, String>,
        limit: Int,
        fallbackCheckpoint: SyncCheckpoint
    ): IncrementalReadResult {
        val collected = mutableListOf<ShardMessage>()
        val seenFingerprints = HashSet<CrossShardFingerprint>()

        val startShard = orderedShards[startIdx]
        val tailMessages = readSinceLocalIdFromShard(startShard, tableName, sinceLocalIdOnStart, contactsMap, limit)
        appendDeduplicatedShardMessages(startShard.shardFile.name, tailMessages, collected, seenFingerprints)

        var idx = startIdx + 1
        while (collected.size < limit && idx < orderedShards.size) {
            val nextShard = orderedShards[idx]
            val remaining = limit - collected.size
            val nextMessages = readAllChronologicalFromShard(nextShard, tableName, contactsMap, remaining)
            appendDeduplicatedShardMessages(nextShard.shardFile.name, nextMessages, collected, seenFingerprints)
            idx++
        }

        if (collected.isEmpty()) {
            return IncrementalReadResult(
                messages = emptyList(),
                latestShardName = fallbackCheckpoint.lastShardName,
                latestLocalId = fallbackCheckpoint.lastLocalId,
                latestCreateTime = fallbackCheckpoint.lastCreateTime
            )
        }

        val last = collected.last()
        return IncrementalReadResult(
            messages = collected.map { it.message },
            latestShardName = last.shardName,
            latestLocalId = last.message.localId,
            latestCreateTime = last.message.timestampSeconds
        )
    }

    private fun appendDeduplicatedShardMessages(
        shardName: String,
        batch: List<WeChatMessage>,
        collected: MutableList<ShardMessage>,
        seenCrossShardFingerprints: MutableSet<CrossShardFingerprint>
    ) {
        val batchFingerprints = ArrayList<CrossShardFingerprint>(batch.size)
        for (msg in batch) {
            val fp = CrossShardFingerprint(msg.timestampSeconds, msg.senderId, msg.rawContent)
            if (fp !in seenCrossShardFingerprints) {
                collected.add(ShardMessage(shardName, msg))
                batchFingerprints.add(fp)
            }
        }
        seenCrossShardFingerprints.addAll(batchFingerprints)
    }

    private fun readRecentFromShard(
        shard: RoomShard,
        tableName: String,
        contactsMap: Map<String, String>,
        limit: Int
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "",
            orderBy = "create_time DESC, local_id DESC",
            limit = limit
        ).reversed()
    }

    private fun readSinceLocalIdFromShard(
        shard: RoomShard,
        tableName: String,
        sinceLocalId: Long,
        contactsMap: Map<String, String>,
        limit: Int
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "WHERE local_id > ?",
            orderBy = "local_id ASC",
            limit = limit,
            bindCursorParam = sinceLocalId
        )
    }

    private fun readAllChronologicalFromShard(
        shard: RoomShard,
        tableName: String,
        contactsMap: Map<String, String>,
        limit: Int
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "",
            orderBy = "create_time ASC, local_id ASC",
            limit = limit
        )
    }

    private fun readSinceCreateTimeFromShard(
        shard: RoomShard,
        tableName: String,
        sinceCreateTime: Long,
        contactsMap: Map<String, String>,
        limit: Int
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "WHERE create_time > ?",
            orderBy = "create_time ASC, local_id ASC",
            limit = limit,
            bindCursorParam = sinceCreateTime
        )
    }

    private fun queryShardMessages(
        shard: RoomShard,
        tableName: String,
        contactsMap: Map<String, String>,
        whereClause: String,
        orderBy: String,
        limit: Int,
        bindCursorParam: Long? = null
    ): List<WeChatMessage> {
        val messages = mutableListOf<WeChatMessage>()
        driver.openReadOnlyConnection(shard.shardFile, sessionContext).use { conn ->
            val hasLocalType = hasColumn(conn, tableName, "local_type")
            val localTypeSelect = if (hasLocalType) "local_type" else "1 AS local_type"
            val sql = "SELECT local_id, create_time, real_sender_id, $localTypeSelect, message_content FROM $tableName $whereClause ORDER BY $orderBy LIMIT ?"
            conn.prepareStatement(sql).use { stmt ->
                var paramIdx = 1
                if (bindCursorParam != null) {
                    stmt.setLong(paramIdx++, bindCursorParam)
                }
                stmt.setInt(paramIdx, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(parseMessageRow(rs, contactsMap, shard.name2IdMap, hasLocalType))
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
        private val MSG_TABLE_REGEX = Regex("^Msg_[0-9a-fA-F]+$")

        private fun shardNumericOrder(fileName: String): Int {
            return fileName.removePrefix("message_").removeSuffix(".db").toIntOrNull() ?: Int.MAX_VALUE
        }
    }
}
