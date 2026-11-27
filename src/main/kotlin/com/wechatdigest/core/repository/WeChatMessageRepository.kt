package com.wechatdigest.core.repository

import com.wechatdigest.core.attachment.ParsedWeChatMessage
import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.attachment.WeChatFileMessageParser
import com.wechatdigest.core.attachment.WeChatXmlMessageParser
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.decompress.ZstdDecompressor
import com.wechatdigest.core.model.SyncCheckpoint
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.sanitizer.MessageSanitizer
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

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
        val latestCreateTime: Long,
        val hasMore: Boolean = false
    )

    /** Result of loading a bounded surrounding conversation window around an extracted card's messages. */
    data class ContextMessagesResult(
        val messages: List<WeChatMessage>,
        val anchorFingerprints: Set<String>
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
            file.isFile && SHARD_FILE_REGEX.matches(file.name)
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
        require(MSG_TABLE_REGEX.matches(tableName)) { "Invalid room message table name" }
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

    private fun loadRoomOwnerUsername(roomId: String): String {
        return try {
            contactRepository.loadRoomOwnersMap()[roomId].orEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ""
        }
    }

    /**
     * Fetches the latest messages for a chatroom and resolves sender names and decompresses content.
     */
    fun getRecentMessages(roomId: String, limit: Int = 30): List<WeChatMessage> {
        val safeLimit = limit.coerceIn(MIN_MESSAGES_PER_SYNC, MAX_MESSAGES_PER_SYNC)
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shards = findShardsForTable(tableName)
        val shard = shards.minWithOrNull(newestShardComparator())
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val contactsMap = contactRepository.loadContactsMap()
        val roomOwnerUsername = loadRoomOwnerUsername(roomId)
        return readRecentFromShard(shard, tableName, contactsMap, safeLimit, roomOwnerUsername)
    }

    /**
     * Fetches new messages strictly arriving after `sinceLocalId` in chronological order (oldest to newest).
     */
    fun getMessagesSince(roomId: String, sinceLocalId: Long, limit: Int = 50): List<WeChatMessage> {
        val safeLimit = limit.coerceIn(MIN_MESSAGES_PER_SYNC, MAX_MESSAGES_PER_SYNC)
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val shards = findShardsForTable(tableName)
        val shard = shards.minWithOrNull(newestShardComparator())
            ?: throw FileNotFoundException("Could not find database shard containing table $tableName for room $roomId")

        val contactsMap = contactRepository.loadContactsMap()
        val roomOwnerUsername = loadRoomOwnerUsername(roomId)
        return readSinceLocalIdFromShard(shard, tableName, sinceLocalId, contactsMap, safeLimit, roomOwnerUsername)
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
        val safeLimit = limit.coerceIn(MIN_MESSAGES_PER_SYNC, MAX_MESSAGES_PER_SYNC)
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
        val roomOwnerUsername = loadRoomOwnerUsername(roomId)

        if (checkpoint == null || checkpoint.lastLocalId <= 0L) {
            val recent = readRecentFromShard(newestShard, tableName, contactsMap, safeLimit, roomOwnerUsername)
            if (recent.isEmpty()) {
                return IncrementalReadResult(emptyList(), newestShard.shardFile.name, 0L, 0L, hasMore = false)
            }
            return IncrementalReadResult(
                messages = recent,
                latestShardName = newestShard.shardFile.name,
                latestLocalId = recent.maxOf { it.localId },
                latestCreateTime = recent.maxOf { it.timestampSeconds },
                hasMore = false
            )
        }

        val probeLimit = safeLimit + 1
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
                    fallbackCheckpoint = checkpoint,
                    roomOwnerUsername = roomOwnerUsername
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
            var advancedCursor: Triple<String, Long, Long>? = null
            for (shard in candidateShards) {
                if (collected.size >= probeLimit) break
                var cursorCreateTime = checkpoint.lastCreateTime
                var cursorLocalId = 0L
                while (collected.size < probeLimit) {
                    val remaining = probeLimit - collected.size
                    val batch = readSinceCreateTimeFromShard(shard, tableName, cursorCreateTime, cursorLocalId, contactsMap, remaining, roomOwnerUsername)
                    if (batch.isEmpty()) break
                    val added = appendDeduplicatedShardMessages(shard.shardFile.name, batch, collected, seenFingerprints)
                    val batchLast = batch.last()
                    cursorCreateTime = batchLast.timestampSeconds
                    cursorLocalId = batchLast.localId
                    if (added > 0 || batch.size < remaining) {
                        advancedCursor = Triple(shard.shardFile.name, batchLast.localId, batchLast.timestampSeconds)
                    }
                    if (batch.size < remaining) break
                }
            }
            if (collected.isEmpty()) {
                val cursor = advancedCursor
                return if (cursor != null) {
                    IncrementalReadResult(emptyList(), cursor.first, cursor.second, cursor.third, hasMore = false)
                } else {
                    IncrementalReadResult(
                        messages = emptyList(),
                        latestShardName = checkpoint.lastShardName,
                        latestLocalId = checkpoint.lastLocalId,
                        latestCreateTime = checkpoint.lastCreateTime,
                        hasMore = false
                    )
                }
            }
            val hasMore = collected.size > safeLimit
            val sliced = if (hasMore) collected.take(safeLimit) else collected
            val last = sliced.last()
            return IncrementalReadResult(
                messages = sliced.map { it.message },
                latestShardName = last.shardName,
                latestLocalId = last.message.localId,
                latestCreateTime = last.message.timestampSeconds,
                hasMore = hasMore
            )
        }

        // Legacy v1.1 checkpoint without lastShardName: avoid jumping blindly to newestShard when
        // older shards still exist and newer shards may have reset local_id back to 1.
        if (nonEmptyShards.size == 1) {
            val single = nonEmptyShards.single()
            if (single.bounds.maxLocalId >= checkpoint.lastLocalId) {
                val probed = readSinceLocalIdFromShard(single, tableName, checkpoint.lastLocalId, contactsMap, probeLimit, roomOwnerUsername)
                if (probed.isEmpty()) {
                    return IncrementalReadResult(emptyList(), single.shardFile.name, checkpoint.lastLocalId, checkpoint.lastSyncTimestamp, hasMore = false)
                }
                val hasMore = probed.size > safeLimit
                val sliced = if (hasMore) probed.take(safeLimit) else probed
                val last = sliced.last()
                return IncrementalReadResult(sliced, single.shardFile.name, last.localId, last.timestampSeconds, hasMore = hasMore)
            }
            return IncrementalReadResult(emptyList(), single.shardFile.name, checkpoint.lastLocalId, checkpoint.lastSyncTimestamp, hasMore = false)
        }

        val syncTs = checkpoint.lastSyncTimestamp
        val matchingIdx = if (syncTs > 0L) {
            val idxByTime = nonEmptyShards.indexOfFirst { it.bounds.maxCreateTime > syncTs }
            if (idxByTime >= 0) idxByTime else nonEmptyShards.indexOfLast { it.bounds.maxLocalId >= checkpoint.lastLocalId }
        } else {
            nonEmptyShards.indexOfFirst { it.bounds.maxLocalId >= checkpoint.lastLocalId }
        }
        if (matchingIdx >= 0) {
            val startShard = nonEmptyShards[matchingIdx]
            // If startShard rolled over strictly after the last recorded sync wall-clock time,
            // read from local_id = 0 instead of filtering against an older shard's local_id.
            val sinceOnStart = if (syncTs > 0L && startShard.bounds.minCreateTime > syncTs) {
                0L
            } else if (startShard.bounds.maxLocalId >= checkpoint.lastLocalId) {
                checkpoint.lastLocalId
            } else {
                0L
            }
            return readAcrossShardsFromIndex(
                orderedShards = nonEmptyShards,
                startIdx = matchingIdx,
                sinceLocalIdOnStart = sinceOnStart,
                tableName = tableName,
                contactsMap = contactsMap,
                limit = safeLimit,
                fallbackCheckpoint = checkpoint.copy(lastShardName = startShard.shardFile.name),
                roomOwnerUsername = roomOwnerUsername
            )
        }

        return IncrementalReadResult(emptyList(), newestShard.shardFile.name, checkpoint.lastLocalId, syncTs, hasMore = false)
    }

    private fun readAcrossShardsFromIndex(
        orderedShards: List<RoomShard>,
        startIdx: Int,
        sinceLocalIdOnStart: Long,
        tableName: String,
        contactsMap: Map<String, String>,
        limit: Int,
        fallbackCheckpoint: SyncCheckpoint,
        roomOwnerUsername: String = ""
    ): IncrementalReadResult {
        val safeLimit = limit.coerceIn(MIN_MESSAGES_PER_SYNC, MAX_MESSAGES_PER_SYNC)
        val probeLimit = safeLimit + 1
        val collected = mutableListOf<ShardMessage>()
        val seenFingerprints = HashSet<CrossShardFingerprint>()
        var exhaustedCursor: Triple<String, Long, Long>? = null

        val startShard = orderedShards[startIdx]
        val tailMessages = readSinceLocalIdFromShard(startShard, tableName, sinceLocalIdOnStart, contactsMap, probeLimit, roomOwnerUsername)
        appendDeduplicatedShardMessages(startShard.shardFile.name, tailMessages, collected, seenFingerprints)
        if (tailMessages.isNotEmpty()) {
            val tailLast = tailMessages.last()
            exhaustedCursor = Triple(startShard.shardFile.name, tailLast.localId, tailLast.timestampSeconds)
        }

        var idx = startIdx + 1
        while (collected.size < probeLimit && idx < orderedShards.size) {
            val nextShard = orderedShards[idx]
            var shardCursorLocalId = 0L
            while (collected.size < probeLimit) {
                val remaining = probeLimit - collected.size
                val batch = readSinceLocalIdFromShard(nextShard, tableName, shardCursorLocalId, contactsMap, remaining, roomOwnerUsername)
                if (batch.isEmpty()) break
                appendDeduplicatedShardMessages(nextShard.shardFile.name, batch, collected, seenFingerprints)
                val batchLast = batch.last()
                shardCursorLocalId = batchLast.localId
                if (batch.size < remaining) {
                    exhaustedCursor = Triple(nextShard.shardFile.name, batchLast.localId, batchLast.timestampSeconds)
                    break
                }
            }
            idx++
        }

        if (collected.isEmpty()) {
            val cursor = exhaustedCursor
            return if (cursor != null) {
                IncrementalReadResult(emptyList(), cursor.first, cursor.second, cursor.third, hasMore = false)
            } else {
                IncrementalReadResult(
                    messages = emptyList(),
                    latestShardName = fallbackCheckpoint.lastShardName,
                    latestLocalId = fallbackCheckpoint.lastLocalId,
                    latestCreateTime = fallbackCheckpoint.lastCreateTime,
                    hasMore = false
                )
            }
        }

        val hasMore = collected.size > limit
        val sliced = if (hasMore) collected.take(limit) else collected
        val last = sliced.last()
        return IncrementalReadResult(
            messages = sliced.map { it.message },
            latestShardName = last.shardName,
            latestLocalId = last.message.localId,
            latestCreateTime = last.message.timestampSeconds,
            hasMore = hasMore
        )
    }

    private fun appendDeduplicatedShardMessages(
        shardName: String,
        batch: List<WeChatMessage>,
        collected: MutableList<ShardMessage>,
        seenCrossShardFingerprints: MutableSet<CrossShardFingerprint>
    ): Int {
        var added = 0
        val batchFingerprints = ArrayList<CrossShardFingerprint>(batch.size)
        for (msg in batch) {
            val fp = CrossShardFingerprint(msg.timestampSeconds, msg.senderId, msg.rawContent)
            if (fp !in seenCrossShardFingerprints) {
                collected.add(ShardMessage(shardName, msg))
                batchFingerprints.add(fp)
                added++
            }
        }
        seenCrossShardFingerprints.addAll(batchFingerprints)
        return added
    }

    private fun readRecentFromShard(
        shard: RoomShard,
        tableName: String,
        contactsMap: Map<String, String>,
        limit: Int,
        roomOwnerUsername: String = ""
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "",
            orderBy = "create_time DESC, local_id DESC",
            limit = limit,
            roomOwnerUsername = roomOwnerUsername
        ).reversed()
    }

    private fun readSinceLocalIdFromShard(
        shard: RoomShard,
        tableName: String,
        sinceLocalId: Long,
        contactsMap: Map<String, String>,
        limit: Int,
        roomOwnerUsername: String = ""
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "WHERE local_id > ?",
            orderBy = "local_id ASC",
            limit = limit,
            bindParams = listOf(sinceLocalId),
            roomOwnerUsername = roomOwnerUsername
        )
    }

    private fun readSinceCreateTimeFromShard(
        shard: RoomShard,
        tableName: String,
        sinceCreateTime: Long,
        sinceLocalIdAtSameTime: Long,
        contactsMap: Map<String, String>,
        limit: Int,
        roomOwnerUsername: String = ""
    ): List<WeChatMessage> {
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "WHERE (create_time > ? OR (create_time = ? AND local_id > ?))",
            orderBy = "create_time ASC, local_id ASC",
            limit = limit,
            bindParams = listOf(sinceCreateTime, sinceCreateTime, sinceLocalIdAtSameTime),
            roomOwnerUsername = roomOwnerUsername
        )
    }

    private fun queryShardMessages(
        shard: RoomShard,
        tableName: String,
        contactsMap: Map<String, String>,
        whereClause: String,
        orderBy: String,
        limit: Int,
        bindParams: List<Long> = emptyList(),
        roomOwnerUsername: String = ""
    ): List<WeChatMessage> {
        require(MSG_TABLE_REGEX.matches(tableName)) { "Invalid room message table name" }
        val safeSqlLimit = limit.coerceIn(1, MAX_MESSAGES_PER_SYNC + 1)
        val messages = mutableListOf<WeChatMessage>()
        driver.openReadOnlyConnection(shard.shardFile, sessionContext).use { conn ->
            val hasLocalType = hasColumn(conn, tableName, "local_type")
            val hasServerId = hasColumn(conn, tableName, "server_id")
            val localTypeSelect = if (hasLocalType) "local_type" else "1 AS local_type"
            val serverIdSelect = if (hasServerId) "server_id" else "0 AS server_id"
            val sql = "SELECT local_id, create_time, real_sender_id, $localTypeSelect, $serverIdSelect, message_content FROM $tableName $whereClause ORDER BY $orderBy LIMIT ?"
            conn.prepareStatement(sql).use { stmt ->
                var paramIdx = 1
                for (param in bindParams) {
                    stmt.setLong(paramIdx++, param)
                }
                stmt.setInt(paramIdx, safeSqlLimit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(parseMessageRow(rs, contactsMap, shard.name2IdMap, hasLocalType, roomOwnerUsername))
                    }
                }
            }
        }
        return messages
    }

    /**
     * Loads up to [beforeCount] prior messages and [afterCount] subsequent messages surrounding an anchored event card
     * across chronological shards for [roomId], suppressing recalled/system rows while leaving casual chatter visible.
     */
    fun getContextMessages(
        roomId: String,
        anchorLocalIds: List<Long>,
        anchorTimestampSeconds: Long,
        beforeCount: Int = 5,
        afterCount: Int = 5
    ): ContextMessagesResult {
        val safeBefore = beforeCount.coerceIn(0, MAX_CONTEXT_WINDOW_COUNT)
        val safeAfter = afterCount.coerceIn(0, MAX_CONTEXT_WINDOW_COUNT)
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val nonEmptyShards = findShardsForTable(tableName)
            .filter { it.bounds.maxLocalId > 0L }
            .sortedWith(chronologicalShardComparator())
        if (nonEmptyShards.isEmpty()) return ContextMessagesResult(emptyList(), emptySet())

        val contactsMap = contactRepository.loadContactsMap()
        val roomOwnerUsername = loadRoomOwnerUsername(roomId)
        val (anchorShardIdx, rawAnchors) = locateAndResolveAnchorMessages(
            orderedShards = nonEmptyShards,
            tableName = tableName,
            anchorLocalIds = anchorLocalIds,
            anchorTimestampSeconds = anchorTimestampSeconds,
            contactsMap = contactsMap,
            roomOwnerUsername = roomOwnerUsername
        )
        val anchors = MessageSanitizer.suppressSystemAndRecalledMessages(rawAnchors)
        val anchorReference = rawAnchors.firstOrNull() ?: anchors.firstOrNull()
            ?: return ContextMessagesResult(emptyList(), emptySet())
        val anchorLast = rawAnchors.lastOrNull() ?: anchors.lastOrNull() ?: anchorReference

        val seen = HashSet<CrossShardFingerprint>()
        val anchorFps = HashSet<String>()
        anchors.forEach { msg ->
            seen.add(CrossShardFingerprint(msg.timestampSeconds, msg.senderId, msg.rawContent))
            anchorFps.add(contextKey(msg))
        }

        val beforeCollected = collectMessagesBackward(
            orderedShards = nonEmptyShards,
            startShardIdx = anchorShardIdx,
            startCreateTime = anchorReference.timestampSeconds,
            startLocalId = anchorReference.localId,
            tableName = tableName,
            contactsMap = contactsMap,
            roomOwnerUsername = roomOwnerUsername,
            neededCount = safeBefore,
            seen = seen
        )
        val afterCollected = collectMessagesForward(
            orderedShards = nonEmptyShards,
            startShardIdx = anchorShardIdx,
            startCreateTime = anchorLast.timestampSeconds,
            startLocalId = anchorLast.localId,
            tableName = tableName,
            contactsMap = contactsMap,
            roomOwnerUsername = roomOwnerUsername,
            neededCount = safeAfter,
            seen = seen
        )

        return ContextMessagesResult(
            messages = beforeCollected + anchors + afterCollected,
            anchorFingerprints = anchorFps
        )
    }

    /** Looks up anchored messages for single-card re-extraction using stored local IDs and timestamp bounds. */
    fun findAnchorMessages(
        roomId: String,
        anchorLocalIds: List<Long>,
        anchorTimestampSeconds: Long
    ): List<WeChatMessage> {
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        val nonEmptyShards = findShardsForTable(tableName)
            .filter { it.bounds.maxLocalId > 0L }
            .sortedWith(chronologicalShardComparator())
        if (nonEmptyShards.isEmpty()) return emptyList()
        val contactsMap = contactRepository.loadContactsMap()
        val roomOwnerUsername = loadRoomOwnerUsername(roomId)
        val (_, raw) = locateAndResolveAnchorMessages(
            orderedShards = nonEmptyShards,
            tableName = tableName,
            anchorLocalIds = anchorLocalIds,
            anchorTimestampSeconds = anchorTimestampSeconds,
            contactsMap = contactsMap,
            roomOwnerUsername = roomOwnerUsername
        )
        return MessageSanitizer.suppressSystemAndRecalledMessages(raw)
    }

    private fun locateAndResolveAnchorMessages(
        orderedShards: List<RoomShard>,
        tableName: String,
        anchorLocalIds: List<Long>,
        anchorTimestampSeconds: Long,
        contactsMap: Map<String, String>,
        roomOwnerUsername: String
    ): Pair<Int, List<WeChatMessage>> {
        if (anchorTimestampSeconds <= 0L) {
            val lastIdx = orderedShards.lastIndex
            return lastIdx to resolveAnchorMessagesInShard(
                shard = orderedShards[lastIdx],
                tableName = tableName,
                anchorLocalIds = anchorLocalIds,
                anchorTimestampSeconds = anchorTimestampSeconds,
                contactsMap = contactsMap,
                roomOwnerUsername = roomOwnerUsername
            )
        }
        val overlappingIndices = orderedShards.indices.filter { idx ->
            val bounds = orderedShards[idx].bounds
            anchorTimestampSeconds >= bounds.minCreateTime && anchorTimestampSeconds <= bounds.maxCreateTime
        }
        val candidateIndices = overlappingIndices.ifEmpty {
            val nextIdx = orderedShards.indexOfFirst { it.bounds.maxCreateTime >= anchorTimestampSeconds }
            listOf(if (nextIdx >= 0) nextIdx else orderedShards.lastIndex)
        }

        // First scan all candidate shards for an explicit (local_id, close-timestamp) hit on same-second rollovers/overlaps.
        val distinctIds = anchorLocalIds.distinct().take(20)
        if (distinctIds.isNotEmpty()) {
            var bestLocalIdHit: Pair<Int, List<WeChatMessage>>? = null
            var bestTimeDelta = Long.MAX_VALUE
            val placeholders = distinctIds.joinToString(",") { "?" }
            for (idx in candidateIndices) {
                val hits = queryShardMessages(
                    shard = orderedShards[idx],
                    tableName = tableName,
                    contactsMap = contactsMap,
                    whereClause = "WHERE local_id IN ($placeholders)",
                    orderBy = "create_time ASC, local_id ASC",
                    limit = distinctIds.size,
                    bindParams = distinctIds,
                    roomOwnerUsername = roomOwnerUsername
                ).filter { kotlin.math.abs(it.timestampSeconds - anchorTimestampSeconds) <= ANCHOR_LOCAL_ID_MAX_DRIFT_SECONDS }
                if (hits.isNotEmpty()) {
                    val delta = hits.minOf { kotlin.math.abs(it.timestampSeconds - anchorTimestampSeconds) }
                    if (delta < bestTimeDelta) {
                        bestTimeDelta = delta
                        bestLocalIdHit = idx to hits
                    }
                }
            }
            if (bestLocalIdHit != null) return bestLocalIdHit
        }

        var bestFallback: Pair<Int, List<WeChatMessage>>? = null
        var bestFallbackDelta = Long.MAX_VALUE
        for (idx in candidateIndices) {
            val msgs = resolveTimestampFallbackInShard(
                shard = orderedShards[idx],
                tableName = tableName,
                anchorTimestampSeconds = anchorTimestampSeconds,
                contactsMap = contactsMap,
                roomOwnerUsername = roomOwnerUsername
            )
            if (msgs.isNotEmpty()) {
                val delta = kotlin.math.abs(msgs.first().timestampSeconds - anchorTimestampSeconds)
                if (delta < bestFallbackDelta) {
                    bestFallbackDelta = delta
                    bestFallback = idx to msgs
                }
            }
        }
        return bestFallback ?: (candidateIndices.first() to emptyList())
    }

    private fun resolveAnchorMessagesInShard(
        shard: RoomShard,
        tableName: String,
        anchorLocalIds: List<Long>,
        anchorTimestampSeconds: Long,
        contactsMap: Map<String, String>,
        roomOwnerUsername: String
    ): List<WeChatMessage> {
        val distinctIds = anchorLocalIds.distinct().take(20)
        if (distinctIds.isNotEmpty()) {
            val placeholders = distinctIds.joinToString(",") { "?" }
            val byIds = queryShardMessages(
                shard = shard,
                tableName = tableName,
                contactsMap = contactsMap,
                whereClause = "WHERE local_id IN ($placeholders)",
                orderBy = "create_time ASC, local_id ASC",
                limit = distinctIds.size,
                bindParams = distinctIds,
                roomOwnerUsername = roomOwnerUsername
            )
            if (byIds.isNotEmpty()) return byIds
        }
        return resolveTimestampFallbackInShard(shard, tableName, anchorTimestampSeconds, contactsMap, roomOwnerUsername)
    }

    private fun resolveTimestampFallbackInShard(
        shard: RoomShard,
        tableName: String,
        anchorTimestampSeconds: Long,
        contactsMap: Map<String, String>,
        roomOwnerUsername: String
    ): List<WeChatMessage> {
        val atOrBefore = queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "WHERE create_time <= ?",
            orderBy = "create_time DESC, local_id DESC",
            limit = 1,
            bindParams = listOf(anchorTimestampSeconds),
            roomOwnerUsername = roomOwnerUsername
        )
        if (atOrBefore.isNotEmpty()) return atOrBefore
        return queryShardMessages(
            shard = shard,
            tableName = tableName,
            contactsMap = contactsMap,
            whereClause = "WHERE create_time >= ?",
            orderBy = "create_time ASC, local_id ASC",
            limit = 1,
            bindParams = listOf(anchorTimestampSeconds),
            roomOwnerUsername = roomOwnerUsername
        )
    }

    private fun collectMessagesBackward(
        orderedShards: List<RoomShard>,
        startShardIdx: Int,
        startCreateTime: Long,
        startLocalId: Long,
        tableName: String,
        contactsMap: Map<String, String>,
        roomOwnerUsername: String,
        neededCount: Int,
        seen: MutableSet<CrossShardFingerprint>
    ): List<WeChatMessage> {
        if (neededCount <= 0) return emptyList()
        val descendingCollected = mutableListOf<WeChatMessage>()
        var idx = startShardIdx
        var useCursor = true
        var curTime = startCreateTime
        var curLocalId = startLocalId
        while (idx >= 0 && descendingCollected.size < neededCount) {
            val shard = orderedShards[idx]
            while (descendingCollected.size < neededCount) {
                val batchLimit = (neededCount - descendingCollected.size) * 2 + 4
                val batchDesc = if (useCursor) {
                    queryShardMessages(
                        shard = shard,
                        tableName = tableName,
                        contactsMap = contactsMap,
                        whereClause = "WHERE (create_time < ? OR (create_time = ? AND local_id < ?))",
                        orderBy = "create_time DESC, local_id DESC",
                        limit = batchLimit,
                        bindParams = listOf(curTime, curTime, curLocalId),
                        roomOwnerUsername = roomOwnerUsername
                    )
                } else {
                    queryShardMessages(
                        shard = shard,
                        tableName = tableName,
                        contactsMap = contactsMap,
                        whereClause = "WHERE create_time <= ?",
                        orderBy = "create_time DESC, local_id DESC",
                        limit = batchLimit,
                        bindParams = listOf(curTime),
                        roomOwnerUsername = roomOwnerUsername
                    )
                }
                if (batchDesc.isEmpty()) break
                val cleanedDesc = MessageSanitizer.suppressSystemAndRecalledMessages(batchDesc.reversed()).reversed()
                for (msg in cleanedDesc) {
                    if (descendingCollected.size >= neededCount) break
                    val fp = CrossShardFingerprint(msg.timestampSeconds, msg.senderId, msg.rawContent)
                    if (seen.add(fp)) {
                        descendingCollected.add(msg)
                    }
                }
                val oldestInBatch = batchDesc.last()
                curTime = oldestInBatch.timestampSeconds
                curLocalId = oldestInBatch.localId
                useCursor = true
                if (batchDesc.size < batchLimit) break
            }
            useCursor = false
            idx--
        }
        return descendingCollected.reversed()
    }

    private fun collectMessagesForward(
        orderedShards: List<RoomShard>,
        startShardIdx: Int,
        startCreateTime: Long,
        startLocalId: Long,
        tableName: String,
        contactsMap: Map<String, String>,
        roomOwnerUsername: String,
        neededCount: Int,
        seen: MutableSet<CrossShardFingerprint>
    ): List<WeChatMessage> {
        if (neededCount <= 0) return emptyList()
        val collected = mutableListOf<WeChatMessage>()
        var idx = startShardIdx
        var useCursor = true
        var curTime = startCreateTime
        var curLocalId = startLocalId
        while (idx < orderedShards.size && collected.size < neededCount) {
            val shard = orderedShards[idx]
            while (collected.size < neededCount) {
                val batchLimit = (neededCount - collected.size) * 2 + 4
                val batch = if (useCursor) {
                    queryShardMessages(
                        shard = shard,
                        tableName = tableName,
                        contactsMap = contactsMap,
                        whereClause = "WHERE (create_time > ? OR (create_time = ? AND local_id > ?))",
                        orderBy = "create_time ASC, local_id ASC",
                        limit = batchLimit,
                        bindParams = listOf(curTime, curTime, curLocalId),
                        roomOwnerUsername = roomOwnerUsername
                    )
                } else {
                    queryShardMessages(
                        shard = shard,
                        tableName = tableName,
                        contactsMap = contactsMap,
                        whereClause = "WHERE create_time >= ?",
                        orderBy = "create_time ASC, local_id ASC",
                        limit = batchLimit,
                        bindParams = listOf(curTime),
                        roomOwnerUsername = roomOwnerUsername
                    )
                }
                if (batch.isEmpty()) break
                val cleaned = MessageSanitizer.suppressSystemAndRecalledMessages(batch)
                for (msg in cleaned) {
                    if (collected.size >= neededCount) break
                    val fp = CrossShardFingerprint(msg.timestampSeconds, msg.senderId, msg.rawContent)
                    if (seen.add(fp)) {
                        collected.add(msg)
                    }
                }
                val newestInBatch = batch.last()
                curTime = newestInBatch.timestampSeconds
                curLocalId = newestInBatch.localId
                useCursor = true
                if (batch.size < batchLimit) break
            }
            useCursor = false
            idx++
        }
        return collected
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
        require(MSG_TABLE_REGEX.matches(tableName)) { "Invalid room message table name" }
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
        hasLocalType: Boolean,
        roomOwnerUsername: String = ""
    ): WeChatMessage {
        val localId = rs.getLong("local_id")
        val createTime = rs.getLong("create_time")
        val realSenderId = rs.getString("real_sender_id") ?: ""
        val localType = rs.getLong("local_type")
        val serverId = rs.getLong("server_id")
        val contentBytes = rs.getBytes("message_content") ?: ByteArray(0)

        val isCompressed = ZstdDecompressor.isZstdCompressed(contentBytes)
        val decompressed = ZstdDecompressor.decompressToString(contentBytes)
        val (senderId, initialText) = resolveSenderAndBody(realSenderId, decompressed, contactsMap, name2IdMap)
        val senderDisplayName = contactsMap[senderId] ?: senderId.ifBlank { "Unknown" }
        val isOwner = roomOwnerUsername.isNotBlank() && senderId == roomOwnerUsername

        val parsedXml = WeChatXmlMessageParser.parseMessage(
            rawContent = initialText,
            localType = localType,
            timestampSeconds = createTime,
            allowLegacyXmlFallback = !hasLocalType
        )
        val fields = parsedXml?.toMessageFields(initialText)
            ?: ParsedWeChatMessage.ResolvedXmlFields(textContent = initialText)
        val resolvedAttachments = fields.attachments.map { attachmentResolver?.resolve(it) ?: it }

        return WeChatMessage(
            localId = localId,
            timestampSeconds = createTime,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            rawContent = decompressed,
            textContent = fields.textContent,
            isZstdCompressed = isCompressed,
            localType = localType,
            attachments = resolvedAttachments,
            serverId = serverId,
            isOwner = isOwner,
            isSystemMessage = fields.isSystemMessage,
            recalledServerId = fields.recalledServerId,
            quoteContext = fields.quoteContext,
            extractedUrls = fields.extractedUrls
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
        if (decompressed.isEmpty() || !decompressed.take(66).contains(":\n")) {
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

    companion object {
        const val MIN_MESSAGES_PER_SYNC = 1
        const val MAX_MESSAGES_PER_SYNC = 500
        private const val MAX_CONTEXT_WINDOW_COUNT = 200
        private const val ANCHOR_LOCAL_ID_MAX_DRIFT_SECONDS = 300L

        fun contextKey(msg: WeChatMessage): String =
            "${msg.timestampSeconds}:${msg.senderId}:${msg.rawContent.hashCode()}"

        private val WECHAT_ID_REGEX = Regex("^[A-Za-z0-9_@.-]{3,64}$")
        private val MSG_TABLE_REGEX = Regex("^Msg_[0-9a-fA-F]+$")
        private val SHARD_FILE_REGEX = Regex("^message_\\d+\\.db$")

        private fun shardNumericOrder(fileName: String): Int {
            return fileName.removePrefix("message_").removeSuffix(".db").toIntOrNull() ?: Int.MAX_VALUE
        }
    }
}
