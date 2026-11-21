package com.wechatdigest.storage

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.ResultSet

class EventCardStore(private val db: AppDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Inserts [cards] ignoring duplicates; catches database errors and returns inserted count.
     */
    fun upsert(cards: List<EventCard>): Int {
        return try {
            upsertOrThrow(cards)
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error upserting cards: ${e.message}")
            0
        }
    }

    /**
     * Inserts [cards] in a single transaction, throwing on database failures so sync callers avoid advancing checkpoints.
     */
    fun upsertOrThrow(cards: List<EventCard>): Int {
        if (cards.isEmpty()) return 0

        val sql = """
            INSERT OR IGNORE INTO event_cards (
                id, dedupe_key, title, summary, translated_summary, sections, category, priority,
                event_date, action_items, original_sender, source_room_id,
                source_room_name, source_messages, translated_source_messages,
                timestamp_seconds, is_read, is_starred, is_dismissed, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        var insertedCount = 0
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis() / 1000
                    for (card in cards) {
                        val dedupeKey = computeDedupeKey(card)
                        stmt.setString(1, card.id)
                        stmt.setString(2, dedupeKey)
                        stmt.setString(3, card.title)
                        stmt.setString(4, card.summary)
                        stmt.setString(5, card.translatedSummary)
                        stmt.setString(6, json.encodeToString(card.sections))
                        stmt.setString(7, card.category.name)
                        stmt.setString(8, card.priority.name)
                        stmt.setString(9, card.eventDate)
                        stmt.setString(10, "[]")
                        stmt.setString(11, card.originalSender)
                        stmt.setString(12, card.sourceRoomId)
                        stmt.setString(13, card.sourceRoomName)
                        stmt.setString(14, card.sourceMessages)
                        stmt.setString(15, card.translatedSourceMessages)
                        stmt.setLong(16, card.timestampSeconds)
                        stmt.setInt(17, if (card.isRead) 1 else 0)
                        stmt.setInt(18, if (card.isStarred) 1 else 0)
                        stmt.setInt(19, if (card.isDismissed) 1 else 0)
                        stmt.setLong(20, now)

                        if (stmt.executeUpdate() > 0) {
                            insertedCount++
                        }
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return insertedCount
    }

    fun listActive(): List<EventCard> {
        val sql = """
            SELECT id, title, summary, translated_summary, sections, category, priority,
                   event_date, original_sender, source_room_id,
                   source_room_name, source_messages, translated_source_messages,
                   timestamp_seconds, is_read, is_starred, is_dismissed
            FROM event_cards
            WHERE is_dismissed = 0
            ORDER BY timestamp_seconds DESC,
                     created_at DESC,
                     CASE priority
                         WHEN 'CRITICAL' THEN 1
                         WHEN 'IMPORTANT' THEN 2
                         WHEN 'NORMAL' THEN 3
                         WHEN 'LOW' THEN 4
                         ELSE 5
                     END ASC
        """.trimIndent()

        val results = mutableListOf<EventCard>()
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(sql)
                    while (rs.next()) {
                        results.add(mapRowToEventCard(rs))
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error listing active cards: ${e.message}")
        }
        return results
    }

    fun listRecentByRoom(roomId: String, limit: Int = 10): List<EventCard> {
        if (roomId.isBlank() || limit <= 0) return emptyList()
        val sql = """
            SELECT id, title, summary, translated_summary, sections, category, priority,
                   event_date, original_sender, source_room_id,
                   source_room_name, source_messages, translated_source_messages,
                   timestamp_seconds, is_read, is_starred, is_dismissed
            FROM event_cards
            WHERE is_dismissed = 0 AND source_room_id = ?
            ORDER BY timestamp_seconds DESC,
                     created_at DESC
            LIMIT ?
        """.trimIndent()

        val results = mutableListOf<EventCard>()
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, roomId)
                    stmt.setInt(2, limit)
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        results.add(mapRowToEventCard(rs))
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error listing recent room cards: ${e.message}")
        }
        return results
    }

    private fun mapRowToEventCard(rs: ResultSet): EventCard {
        val rawSections = rs.getString("sections")
        val decodedSections = if (rawSections.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<EventSection>>(rawSections) }.getOrDefault(emptyList())
        }
        return EventCard(
            id = rs.getString("id"),
            title = rs.getString("title"),
            summary = rs.getString("summary"),
            translatedSummary = rs.getString("translated_summary"),
            sections = decodedSections,
            category = EventCategory.fromString(rs.getString("category") ?: "OTHER"),
            priority = EventPriority.fromString(rs.getString("priority") ?: "LOW"),
            eventDate = rs.getString("event_date"),
            originalSender = rs.getString("original_sender") ?: "",
            sourceRoomId = rs.getString("source_room_id") ?: "",
            sourceRoomName = rs.getString("source_room_name") ?: "",
            sourceMessages = rs.getString("source_messages") ?: "",
            translatedSourceMessages = rs.getString("translated_source_messages") ?: "",
            timestampSeconds = rs.getLong("timestamp_seconds"),
            isRead = rs.getInt("is_read") == 1,
            isStarred = rs.getInt("is_starred") == 1,
            isDismissed = rs.getInt("is_dismissed") == 1
        )
    }

    fun markRead(id: String) {
        executeUpdate("UPDATE event_cards SET is_read = 1 WHERE id = ?", id)
    }

    fun markUnread(id: String) {
        executeUpdate("UPDATE event_cards SET is_read = 0 WHERE id = ?", id)
    }

    fun setStarred(id: String, starred: Boolean) {
        val value = if (starred) 1 else 0
        executeUpdate("UPDATE event_cards SET is_starred = $value WHERE id = ?", id)
    }

    fun dismiss(id: String) {
        executeUpdate("UPDATE event_cards SET is_dismissed = 1 WHERE id = ?", id)
    }

    fun updateTranslatedSourceMessages(id: String, translatedSourceMessages: String) {
        val sql = "UPDATE event_cards SET translated_source_messages = ? WHERE id = ?"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, translatedSourceMessages)
                    stmt.setString(2, id)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error updating translated source messages: ${e.message}")
        }
    }

    fun clearAll() {
        val sql = "DELETE FROM event_cards"
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate(sql)
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error clearing all cards: ${e.message}")
        }
    }

    private fun executeUpdate(sql: String, id: String) {
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, id)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error executing update: ${e.message}")
        }
    }

    private fun computeDedupeKey(card: EventCard): String {
        val raw = if (card.sourceMessages.isNotBlank()) {
            val normalizedSource = card.sourceMessages.trim().replace(Regex("\\s+"), " ")
            "${card.sourceRoomId}:src:$normalizedSource"
        } else {
            "${card.sourceRoomId}:${card.title}:${card.eventDate.orEmpty()}"
        }
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
