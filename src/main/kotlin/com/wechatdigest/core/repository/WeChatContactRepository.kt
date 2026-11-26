package com.wechatdigest.core.repository

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.GroupMember
import java.io.File
import java.io.FileNotFoundException
import java.sql.Connection
import java.util.concurrent.CancellationException

class WeChatContactRepository(
    private val driver: WeChatDatabaseDriver,
    private val sessionContext: WeChatSessionContext? = null
) {

    private data class ChatRoomExtras(
        val memberCount: Int = 0,
        val ownerUsername: String = "",
        val announcement: String = ""
    )

    private data class SessionActivity(
        val lastTimestamp: Long = 0L,
        val unreadCount: Int = 0
    )

    private val contactDbFile: File by lazy {
        val nested = File(driver.baseDir, "contact/contact.db")
        if (nested.exists()) nested else {
            val flat = File(driver.baseDir, "contact.db")
            if (flat.exists()) flat else throw FileNotFoundException("Could not find contact.db in ${driver.baseDir}")
        }
    }

    private fun findOptionalSessionDbFile(): File? {
        val nested = File(driver.baseDir, "session/session.db")
        if (nested.exists() && nested.isFile) return nested
        val flat = File(driver.baseDir, "session.db")
        return flat.takeIf { it.exists() && it.isFile }
    }

    /**
     * Loads a map of `username -> displayName` for fast sender name resolution, merging non-friend
     * `stranger` rows before friend `contact` rows so saved remarks/nicknames always take precedence.
     * Uses [sessionContext] cache when attached to avoid re-reading `contact.db` per room.
     */
    fun loadContactsMap(): Map<String, String> {
        return sessionContext?.getOrLoadContactsMap { loadContactsMapDirect() } ?: loadContactsMapDirect()
    }

    private fun loadContactsMapDirect(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            val tables = loadExistingTables(conn)
            if ("stranger" in tables) {
                loadTableContactsInto(conn, "stranger", result)
            }
            if ("contact" in tables) {
                loadTableContactsInto(conn, "contact", result)
            }
        }
        return result
    }

    private fun loadTableContactsInto(
        conn: Connection,
        tableName: String,
        target: MutableMap<String, String>
    ) {
        if (!tableHasColumn(conn, tableName, "username")) return
        val hasNick = tableHasColumn(conn, tableName, "nick_name")
        val hasRemark = tableHasColumn(conn, tableName, "remark")
        val nickExpr = if (hasNick) "nick_name" else "'' AS nick_name"
        val remarkExpr = if (hasRemark) "remark" else "'' AS remark"
        val sql = "SELECT username, $nickExpr, $remarkExpr FROM $tableName"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val username = rs.getString("username")?.takeIf { it.isNotBlank() } ?: continue
                    val nickname = rs.getString("nick_name").orEmpty().trim()
                    val remark = rs.getString("remark").orEmpty().trim()
                    val displayName = remark.ifBlank { nickname.ifBlank { username } }
                    target[username] = displayName
                }
            }
        }
    }

    /** Loads `roomId -> ownerUsername` mapping from `contact.db`, cached when [sessionContext] is attached. */
    fun loadRoomOwnersMap(): Map<String, String> {
        return sessionContext?.getOrLoadRoomOwners { loadRoomOwnersDirect() } ?: loadRoomOwnersDirect()
    }

    private fun loadRoomOwnersDirect(): Map<String, String> {
        val owners = mutableMapOf<String, String>()
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            val tables = loadExistingTables(conn)
            if ("chat_room" !in tables ||
                !tableHasColumn(conn, "chat_room", "username") ||
                !tableHasColumn(conn, "chat_room", "owner")
            ) {
                return owners
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT username, owner FROM chat_room").use { rs ->
                    while (rs.next()) {
                        val room = rs.getString("username")?.takeIf { it.isNotBlank() } ?: continue
                        val owner = rs.getString("owner")?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        owners[room] = owner
                    }
                }
            }
        }
        return owners
    }

    /**
     * Finds a group chatroom by its display name (nickname or remark).
     */
    fun findGroupByName(groupName: String): GroupChat? {
        val sql = "SELECT username, nick_name, remark FROM contact WHERE nick_name = ? OR remark = ? LIMIT 1"
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, groupName)
                stmt.setString(2, groupName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val username = rs.getString("username")
                        val nickname = rs.getString("nick_name") ?: ""
                        val remark = rs.getString("remark") ?: ""
                        val displayName = remark.ifBlank { nickname.ifBlank { username } }
                        return GroupChat(roomId = username, displayName = displayName)
                    }
                }
            }
        }
        return null
    }

    /**
     * Lists all chatrooms found in `contact.db`, enriched with `chat_room` / `chatroom_member` counts,
     * owners, optional pinned announcements, and optional `session.db` activity timestamps/unread counts,
     * sorted by most recent activity first then display name.
     */
    fun listAllChatrooms(): List<GroupChat> {
        return sessionContext?.getOrLoadChatrooms { listAllChatroomsDirect() } ?: listAllChatroomsDirect()
    }

    private fun listAllChatroomsDirect(): List<GroupChat> {
        val rawRooms = mutableListOf<Pair<String, String>>() // username to displayName
        val extrasByRoom = mutableMapOf<String, ChatRoomExtras>()
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            val tables = loadExistingTables(conn)
            if ("contact" in tables) {
                val sql = "SELECT username, nick_name, remark FROM contact WHERE username LIKE '%@chatroom'"
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val username = rs.getString("username") ?: continue
                            val nickname = rs.getString("nick_name") ?: ""
                            val remark = rs.getString("remark") ?: ""
                            val displayName = remark.ifBlank { nickname.ifBlank { username } }
                            rawRooms.add(username to displayName)
                        }
                    }
                }
            }
            if ("chat_room" in tables) {
                loadChatRoomExtras(conn, tables, extrasByRoom)
            }
        }

        val sessionActivityByRoom = loadOptionalSessionActivity()

        return rawRooms.map { (roomId, displayName) ->
            val extra = extrasByRoom[roomId]
            val activity = sessionActivityByRoom[roomId]
            GroupChat(
                roomId = roomId,
                displayName = displayName,
                memberCount = extra?.memberCount ?: 0,
                lastActivityTimestamp = activity?.lastTimestamp ?: 0L,
                unreadCount = activity?.unreadCount ?: 0,
                announcement = extra?.announcement.orEmpty(),
                ownerUsername = extra?.ownerUsername.orEmpty()
            )
        }.sortedWith(
            compareByDescending<GroupChat> { it.lastActivityTimestamp }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }

    private fun loadChatRoomExtras(
        conn: Connection,
        tables: Set<String>,
        target: MutableMap<String, ChatRoomExtras>
    ) {
        if (!tableHasColumn(conn, "chat_room", "username")) return
        val hasOwner = tableHasColumn(conn, "chat_room", "owner")
        val announcementCol = listOf("announcement", "notice", "chatroom_notice", "announcement_content")
            .firstOrNull { tableHasColumn(conn, "chat_room", it) }

        val ownerSelect = if (hasOwner) "cr.owner AS owner" else "'' AS owner"
        val noticeSelect = if (announcementCol != null) "cr.$announcementCol AS announcement" else "'' AS announcement"
        val canCountMembers = "chatroom_member" in tables &&
            tableHasColumn(conn, "chatroom_member", "room_id") &&
            tableHasColumn(conn, "chatroom_member", "member_id")
        val crKeyCol = if (tableHasColumn(conn, "chat_room", "id")) "cr.id" else "cr.rowid"

        val sql = if (canCountMembers) {
            """
            SELECT cr.username AS username,
                   $ownerSelect,
                   $noticeSelect,
                   COUNT(cm.member_id) AS member_count
            FROM chat_room cr
            LEFT JOIN chatroom_member cm ON cm.room_id = $crKeyCol
            GROUP BY cr.username
            """.trimIndent()
        } else {
            """
            SELECT cr.username AS username,
                   $ownerSelect,
                   $noticeSelect,
                   0 AS member_count
            FROM chat_room cr
            """.trimIndent()
        }

        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    while (rs.next()) {
                        val username = rs.getString("username")?.takeIf { it.isNotBlank() } ?: continue
                        val owner = rs.getString("owner").orEmpty().trim()
                        val announcement = rs.getString("announcement").orEmpty().trim()
                        val count = rs.getInt("member_count").coerceAtLeast(0)
                        target[username] = ChatRoomExtras(
                            memberCount = count,
                            ownerUsername = owner,
                            announcement = announcement
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println(
                "[WeChatContactRepository] Optional chat_room metadata unavailable: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
        }
    }

    private fun loadOptionalSessionActivity(): Map<String, SessionActivity> {
        val sessionDb = findOptionalSessionDbFile() ?: return emptyMap()
        return try {
            val result = mutableMapOf<String, SessionActivity>()
            driver.openReadOnlyConnection(sessionDb, sessionContext).use { conn ->
                val tables = loadExistingTables(conn)
                val tableName = listOf("SessionTable", "session", "contact_session")
                    .firstOrNull { candidate -> candidate.lowercase() in tables }
                    ?: return emptyMap()
                val userCol = listOf("username", "user_name")
                    .firstOrNull { tableHasColumn(conn, tableName, it) }
                    ?: return emptyMap()
                val timeCol = listOf("last_timestamp", "sort_timestamp", "update_time", "last_msg_update_time")
                    .firstOrNull { tableHasColumn(conn, tableName, it) }
                val unreadCol = listOf("unread_count", "unread")
                    .firstOrNull { tableHasColumn(conn, tableName, it) }
                if (timeCol == null && unreadCol == null) return emptyMap()

                val timeSelect = timeCol?.let { "$it AS last_ts" } ?: "0 AS last_ts"
                val unreadSelect = unreadCol?.let { "$it AS unread_cnt" } ?: "0 AS unread_cnt"
                val sql = "SELECT $userCol AS username, $timeSelect, $unreadSelect FROM $tableName WHERE $userCol LIKE '%@chatroom'"
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val username = rs.getString("username")?.takeIf { it.isNotBlank() } ?: continue
                            val lastTs = rs.getLong("last_ts").coerceAtLeast(0L)
                            val unread = rs.getInt("unread_cnt").coerceAtLeast(0)
                            val prev = result[username]
                            if (prev == null || lastTs > prev.lastTimestamp) {
                                result[username] = SessionActivity(lastTimestamp = lastTs, unreadCount = unread)
                            }
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println(
                "[WeChatContactRepository] Optional session.db read skipped: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            emptyMap()
        }
    }

    /**
     * Lists resolved members of a group chatroom from `contact.db` (`chat_room`, `chatroom_member`,
     * `name2id`, `contact`, and optional `stranger`), sorting owners first then alphabetically.
     */
    fun listRoomMembers(roomId: String): List<GroupMember> {
        if (roomId.isBlank()) return emptyList()
        val members = linkedMapOf<String, GroupMember>()
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            val tables = loadExistingTables(conn)
            val hasName2Id = "name2id" in tables
            val hasContact = "contact" in tables
            val hasStranger = "stranger" in tables && tableHasColumn(conn, "stranger", "username")
            if ("chat_room" !in tables || "chatroom_member" !in tables || (!hasName2Id && !hasContact)) {
                return emptyList()
            }

            val crKeyCol = if (tableHasColumn(conn, "chat_room", "id")) "cr.id" else "cr.rowid"
            val hasOwnerCol = tableHasColumn(conn, "chat_room", "owner")
            val usernameExpr = if (hasName2Id) "n.username" else "c.username"
            val name2IdJoin = if (hasName2Id) "JOIN name2id n ON n.rowid = cm.member_id" else ""
            val contactJoin = when {
                hasName2Id && hasContact -> "LEFT JOIN contact c ON c.username = n.username"
                !hasName2Id && hasContact -> {
                    val contactKeyCol = if (tableHasColumn(conn, "contact", "id")) "c.id" else "c.rowid"
                    "LEFT JOIN contact c ON $contactKeyCol = cm.member_id"
                }
                else -> ""
            }
            val strangerJoin = if (hasStranger) {
                "LEFT JOIN stranger s ON s.username = $usernameExpr"
            } else {
                ""
            }
            val strangerHasRemark = hasStranger && tableHasColumn(conn, "stranger", "remark")
            val strangerHasNick = hasStranger && tableHasColumn(conn, "stranger", "nick_name")
            val displayParts = buildList {
                if (hasContact) {
                    if (tableHasColumn(conn, "contact", "remark")) add("NULLIF(TRIM(c.remark), '')")
                    if (tableHasColumn(conn, "contact", "nick_name")) add("NULLIF(TRIM(c.nick_name), '')")
                }
                if (strangerHasRemark) add("NULLIF(TRIM(s.remark), '')")
                if (strangerHasNick) add("NULLIF(TRIM(s.nick_name), '')")
                add(usernameExpr)
            }
            val displayExpr = if (displayParts.size == 1) displayParts.first() else "COALESCE(${displayParts.joinToString(", ")})"
            val ownerSelect = if (hasOwnerCol) "(cr.owner = $usernameExpr) AS is_owner" else "0 AS is_owner"

            val sql = """
                SELECT $usernameExpr AS username,
                       $displayExpr AS display_name,
                       $ownerSelect
                FROM chat_room cr
                JOIN chatroom_member cm ON cm.room_id = $crKeyCol
                $name2IdJoin
                $contactJoin
                $strangerJoin
                WHERE cr.username = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, roomId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val username = rs.getString("username")?.takeIf { it.isNotBlank() } ?: continue
                        val displayName = rs.getString("display_name")?.trim()?.takeIf { it.isNotBlank() } ?: username
                        val isOwner = rs.getInt("is_owner") == 1
                        members[username] = GroupMember(
                            username = username,
                            displayName = displayName,
                            isOwner = isOwner
                        )
                    }
                }
            }
        }
        return members.values.sortedWith(
            compareByDescending<GroupMember> { it.isOwner }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }

    private fun tableHasColumn(conn: Connection, tableName: String, columnName: String): Boolean {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(columnName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun loadExistingTables(conn: Connection): Set<String> {
        val tables = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                while (rs.next()) {
                    rs.getString(1)?.let { tables.add(it.lowercase()) }
                }
            }
        }
        return tables
    }
}
