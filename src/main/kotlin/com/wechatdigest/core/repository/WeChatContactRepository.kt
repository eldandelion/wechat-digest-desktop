package com.wechatdigest.core.repository

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.GroupMember
import com.wechatdigest.core.model.WeChatContact
import java.io.File
import java.io.FileNotFoundException

class WeChatContactRepository(
    private val driver: WeChatDatabaseDriver,
    private val sessionContext: WeChatSessionContext? = null
) {

    private val contactDbFile: File by lazy {
        val nested = File(driver.baseDir, "contact/contact.db")
        if (nested.exists()) nested else {
            val flat = File(driver.baseDir, "contact.db")
            if (flat.exists()) flat else throw FileNotFoundException("Could not find contact.db in ${driver.baseDir}")
        }
    }

    /**
     * Loads a map of username -> display name for fast sender name resolution.
     * Uses [sessionContext] cache when attached to avoid re-reading `contact.db` per room.
     */
    fun loadContactsMap(): Map<String, String> {
        return sessionContext?.getOrLoadContactsMap { loadContactsMapDirect() } ?: loadContactsMapDirect()
    }

    private fun loadContactsMapDirect(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val sql = "SELECT username, nick_name, remark FROM contact"
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    while (rs.next()) {
                        val username = rs.getString("username") ?: continue
                        val nickname = rs.getString("nick_name") ?: ""
                        val remark = rs.getString("remark") ?: ""
                        val displayName = remark.ifBlank { nickname.ifBlank { username } }
                        result[username] = displayName
                    }
                }
            }
        }
        return result
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
     * Lists all chatrooms found in contact.db.
     */
    fun listAllChatrooms(): List<GroupChat> {
        val result = mutableListOf<GroupChat>()
        val sql = "SELECT username, nick_name, remark FROM contact WHERE username LIKE '%@chatroom'"
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    while (rs.next()) {
                        val username = rs.getString("username") ?: continue
                        val nickname = rs.getString("nick_name") ?: ""
                        val remark = rs.getString("remark") ?: ""
                        val displayName = remark.ifBlank { nickname.ifBlank { username } }
                        result.add(GroupChat(roomId = username, displayName = displayName))
                    }
                }
            }
        }
        return result
    }

    /**
     * Lists resolved members of a group chatroom from contact.db (`chat_room`, `chatroom_member`,
     * `name2id`, `contact`, and optional `stranger`), sorting owners first then alphabetically.
     */
    fun listRoomMembers(roomId: String): List<GroupMember> {
        if (roomId.isBlank()) return emptyList()
        val members = linkedMapOf<String, GroupMember>()
        driver.openReadOnlyConnection(contactDbFile, sessionContext).use { conn ->
            val tables = loadExistingTables(conn)
            val hasName2Id = "name2id" in tables
            val hasContact = "contact" in tables
            val hasStranger = "stranger" in tables
            if ("chat_room" !in tables || "chatroom_member" !in tables || (!hasName2Id && !hasContact)) {
                return emptyList()
            }

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
            val displayParts = buildList {
                if (hasContact) {
                    add("NULLIF(TRIM(c.remark), '')")
                    add("NULLIF(TRIM(c.nick_name), '')")
                }
                if (hasStranger) {
                    add("NULLIF(TRIM(s.remark), '')")
                    add("NULLIF(TRIM(s.nick_name), '')")
                }
                add(usernameExpr)
            }
            val displayExpr = if (displayParts.size == 1) displayParts.first() else "COALESCE(${displayParts.joinToString(", ")})"

            val sql = """
                SELECT $usernameExpr AS username,
                       $displayExpr AS display_name,
                       (cr.owner = $usernameExpr) AS is_owner
                FROM chat_room cr
                JOIN chatroom_member cm ON cm.room_id = cr.id
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

    private fun tableHasColumn(conn: java.sql.Connection, tableName: String, columnName: String): Boolean {
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

    private fun loadExistingTables(conn: java.sql.Connection): Set<String> {
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
