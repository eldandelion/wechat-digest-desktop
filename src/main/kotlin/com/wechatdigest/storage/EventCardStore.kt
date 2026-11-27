package com.wechatdigest.storage

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.model.DetailChecklistKeys
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCardStatus
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.core.sanitizer.ContentFingerprint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet

/** Atomic persistence outcome reporting inserted/amended cards and superseded card IDs. */
data class ApplyCardsResult(
    val insertedCards: List<EventCard> = emptyList(),
    val supersededCardIds: List<String> = emptyList()
)

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
            System.err.println("[EventCardStore] Error upserting cards: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            0
        }
    }

    /**
     * Inserts [cards] in a single transaction, throwing on database failures so sync callers avoid advancing checkpoints.
     */
    fun upsertOrThrow(cards: List<EventCard>): Int {
        return applyUpdateOrInsertOrThrow(cards).insertedCards.size
    }

    private data class SupersededTarget(
        val id: String,
        val isStarred: Boolean,
        val isCompleted: Boolean,
        val checkedItems: List<String>,
        val isDismissed: Boolean,
        val originalSenderId: String
    )

    /**
     * Atomically inserts new cards and resolves/supersedes matching prior revisions (`ACTIVE` -> `UPDATED`/`CANCELLED`),
     * inheriting star state on superseded cards when not already starred.
     *
     * To guard against spoofed cancellations/amendments by unrelated group members, auto-dismissing an
     * existing card requires provenance match when both sender IDs are present (or [roomOwnerId] authority).
     */
    fun applyUpdateOrInsert(cards: List<EventCard>, roomOwnerId: String = ""): ApplyCardsResult {
        return try {
            applyUpdateOrInsertOrThrow(cards, roomOwnerId)
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error applying card batch: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            ApplyCardsResult()
        }
    }

    /**
     * Transactional variant of [applyUpdateOrInsert] throwing on database errors.
     */
    fun applyUpdateOrInsertOrThrow(cards: List<EventCard>, roomOwnerId: String = ""): ApplyCardsResult {
        if (cards.isEmpty()) return ApplyCardsResult()

        val insertSql = """
            INSERT OR IGNORE INTO event_cards (
                id, dedupe_key, title, summary, translated_summary, sections, category, priority,
                event_date, action_items, original_sender, source_room_id,
                source_room_name, source_messages, translated_source_messages, attachments,
                timestamp_seconds, is_read, is_starred, is_dismissed,
                status, supersedes_card_id, original_sender_id, content_fingerprint, source_local_ids,
                is_completed, checked_items, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val dismissSupersededSql = "UPDATE event_cards SET is_dismissed = 1 WHERE id = ? AND is_dismissed = 0"

        val insertedCards = mutableListOf<EventCard>()
        val supersededIds = mutableListOf<String>()
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(insertSql).use { insertStmt ->
                    conn.prepareStatement(dismissSupersededSql).use { dismissStmt ->
                        val now = System.currentTimeMillis() / 1000
                        for (card in cards) {
                            val isLifecycleAmendment = card.status != EventCardStatus.ACTIVE
                            val supersededMatch = if (isLifecycleAmendment) {
                                resolveSupersededTarget(conn, card.supersedesCardId, card.sourceRoomId)
                            } else {
                                null
                            }
                            val canAutoDismissSuperseded = supersededMatch != null && isAuthorizedToSupersede(
                                incomingSenderId = card.originalSenderId,
                                targetSenderId = supersededMatch.originalSenderId,
                                roomOwnerId = roomOwnerId
                            )
                            val resolvedSupersedesId = supersededMatch?.id?.takeIf { canAutoDismissSuperseded }
                            val effectiveStarred = card.isStarred || (canAutoDismissSuperseded && supersededMatch?.isStarred == true)
                            val effectiveCompleted = card.isCompleted || (canAutoDismissSuperseded && supersededMatch?.isCompleted == true)
                            val rawChecked = if (card.checkedItems.isNotEmpty()) {
                                card.checkedItems
                            } else if (canAutoDismissSuperseded) {
                                supersededMatch?.checkedItems.orEmpty()
                            } else {
                                emptyList()
                            }
                            val effectiveChecked = DetailChecklistKeys.sanitizeCheckedItems(rawChecked)
                            val resolvedFingerprint = card.contentFingerprint.ifBlank {
                                ContentFingerprint.compute(card.sourceMessages, card.attachments.map { it.md5 })
                            }
                            val resolvedCard = card.copy(
                                supersedesCardId = resolvedSupersedesId,
                                isStarred = effectiveStarred,
                                isCompleted = effectiveCompleted,
                                checkedItems = effectiveChecked,
                                contentFingerprint = resolvedFingerprint
                            )
                            val dedupeKey = computeDedupeKey(resolvedCard)
                            insertStmt.setString(1, resolvedCard.id)
                            insertStmt.setString(2, dedupeKey)
                            insertStmt.setString(3, resolvedCard.title)
                            insertStmt.setString(4, resolvedCard.summary)
                            insertStmt.setString(5, resolvedCard.translatedSummary)
                            insertStmt.setString(6, json.encodeToString(resolvedCard.sections))
                            insertStmt.setString(7, resolvedCard.category.name)
                            insertStmt.setString(8, resolvedCard.priority.name)
                            insertStmt.setString(9, resolvedCard.eventDate)
                            insertStmt.setString(10, "[]")
                            insertStmt.setString(11, resolvedCard.originalSender)
                            insertStmt.setString(12, resolvedCard.sourceRoomId)
                            insertStmt.setString(13, resolvedCard.sourceRoomName)
                            insertStmt.setString(14, resolvedCard.sourceMessages)
                            insertStmt.setString(15, resolvedCard.translatedSourceMessages)
                            insertStmt.setString(16, json.encodeToString(resolvedCard.attachments))
                            insertStmt.setLong(17, resolvedCard.timestampSeconds)
                            insertStmt.setInt(18, if (resolvedCard.isRead) 1 else 0)
                            insertStmt.setInt(19, if (resolvedCard.isStarred) 1 else 0)
                            insertStmt.setInt(20, if (resolvedCard.isDismissed) 1 else 0)
                            insertStmt.setString(21, resolvedCard.status.name)
                            insertStmt.setString(22, resolvedCard.supersedesCardId)
                            insertStmt.setString(23, resolvedCard.originalSenderId)
                            insertStmt.setString(24, resolvedCard.contentFingerprint)
                            insertStmt.setString(25, json.encodeToString(resolvedCard.sourceLocalIds))
                            insertStmt.setInt(26, if (resolvedCard.isCompleted) 1 else 0)
                            insertStmt.setString(27, json.encodeToString(resolvedCard.checkedItems))
                            insertStmt.setLong(28, now)

                            if (insertStmt.executeUpdate() > 0) {
                                insertedCards.add(resolvedCard)
                                if (canAutoDismissSuperseded &&
                                    supersededMatch?.isDismissed == false &&
                                    resolvedSupersedesId != null &&
                                    !resolvedCard.isDismissed
                                ) {
                                    dismissStmt.setString(1, resolvedSupersedesId)
                                    dismissStmt.executeUpdate()
                                    supersededIds.add(resolvedSupersedesId)
                                }
                            }
                        }
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                runCatching { conn.rollback() }.onFailure { rollbackErr -> e.addSuppressed(rollbackErr) }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
        return ApplyCardsResult(
            insertedCards = insertedCards.toList(),
            supersededCardIds = supersededIds.distinct()
        )
    }

    private fun isAuthorizedToSupersede(
        incomingSenderId: String,
        targetSenderId: String,
        roomOwnerId: String
    ): Boolean {
        val cleanIncoming = incomingSenderId.trim()
        val cleanTarget = targetSenderId.trim()
        val cleanOwner = roomOwnerId.trim()
        if (cleanIncoming.isNotBlank() && cleanOwner.isNotBlank() && cleanIncoming == cleanOwner) return true
        if (cleanTarget.isBlank()) return true
        if (cleanIncoming.isBlank()) return false
        return cleanIncoming == cleanTarget
    }

    private fun resolveSupersededTarget(
        conn: Connection,
        rawRef: String?,
        roomId: String
    ): SupersededTarget? {
        if (roomId.isBlank()) return null
        val cleaned = rawRef
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore("|")
            ?.trim()
            ?.let { token -> if (token.startsWith("card:", ignoreCase = true)) token.substring(5).trim() else token }
            ?.take(64)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Query across both active and already-superseded targets so re-applying the same amended card keeps dedupe_key identical.
        val exactSql = "SELECT id, is_starred, is_completed, checked_items, is_dismissed, original_sender_id FROM event_cards WHERE id = ? AND source_room_id = ? LIMIT 1"
        conn.prepareStatement(exactSql).use { stmt ->
            stmt.setString(1, cleaned)
            stmt.setString(2, roomId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return mapSupersededTarget(rs)
                }
            }
        }
        if (cleaned.length < 6) return null
        val escapedPrefix = cleaned
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val activePrefixSql = "SELECT id, is_starred, is_completed, checked_items, is_dismissed, original_sender_id FROM event_cards WHERE source_room_id = ? AND is_dismissed = 0 AND id LIKE ? ESCAPE '\\' LIMIT 2"
        findUniquePrefixMatch(conn, activePrefixSql, roomId, "$escapedPrefix%")?.let { return it }

        val anyPrefixSql = "SELECT id, is_starred, is_completed, checked_items, is_dismissed, original_sender_id FROM event_cards WHERE source_room_id = ? AND id LIKE ? ESCAPE '\\' LIMIT 2"
        return findUniquePrefixMatch(conn, anyPrefixSql, roomId, "$escapedPrefix%")
    }

    private fun decodeBoundedCheckedItems(rawChecked: String?): List<String> {
        if (rawChecked.isNullOrBlank() || rawChecked.length > DetailChecklistKeys.MAX_CHECKED_JSON_CHARS) {
            return emptyList()
        }
        val decoded = runCatching { json.decodeFromString<List<String>>(rawChecked) }.getOrDefault(emptyList())
        return DetailChecklistKeys.sanitizeCheckedItems(decoded)
    }

    private fun mapSupersededTarget(rs: ResultSet): SupersededTarget {
        return SupersededTarget(
            id = rs.getString("id"),
            isStarred = rs.getInt("is_starred") == 1,
            isCompleted = rs.getInt("is_completed") == 1,
            checkedItems = decodeBoundedCheckedItems(rs.getString("checked_items")),
            isDismissed = rs.getInt("is_dismissed") == 1,
            originalSenderId = rs.getString("original_sender_id") ?: ""
        )
    }

    private fun findUniquePrefixMatch(
        conn: Connection,
        sql: String,
        roomId: String,
        likePattern: String
    ): SupersededTarget? {
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, roomId)
            stmt.setString(2, likePattern)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val first = mapSupersededTarget(rs)
                // Fail closed when short ID prefix is ambiguous across multiple cards
                if (rs.next()) return null
                return first
            }
        }
    }

    fun listActive(): List<EventCard> {
        val sql = """
            SELECT $CARD_SELECT_COLUMNS
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
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToEventCard(rs))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error listing active cards: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
        return results
    }

    fun listRecentByRoom(roomId: String, limit: Int = 10): List<EventCard> {
        if (roomId.isBlank() || limit <= 0) return emptyList()
        val sql = """
            SELECT $CARD_SELECT_COLUMNS
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
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToEventCard(rs))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error listing recent room cards: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
        return results
    }

    /** Returns normalized SHA-256 content fingerprints for all active non-cancelled cards across rooms. */
    fun listActiveFingerprints(): Set<String> {
        val sql = "SELECT DISTINCT content_fingerprint FROM event_cards WHERE is_dismissed = 0 AND status != 'CANCELLED' AND content_fingerprint != ''"
        val results = HashSet<String>()
        try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val fp = rs.getString("content_fingerprint")?.trim()
                            if (!fp.isNullOrBlank()) results.add(fp)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error listing active fingerprints: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
        return results
    }

    /** Finds a card by its exact [id], returning null if absent. */
    fun findById(id: String): EventCard? {
        if (id.isBlank()) return null
        val sql = "SELECT $CARD_SELECT_COLUMNS FROM event_cards WHERE id = ? LIMIT 1"
        return try {
            db.getConnection().use { conn ->
                findByIdWithConn(conn, id, sql)
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error finding card ${LlmJsonUtils.sanitizeLogMessage(id)}: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            null
        }
    }

    private fun findByIdWithConn(conn: Connection, id: String, sql: String = "SELECT $CARD_SELECT_COLUMNS FROM event_cards WHERE id = ? LIMIT 1"): EventCard? {
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) mapRowToEventCard(rs) else null
            }
        }
    }

    private fun mapRowToEventCard(rs: ResultSet): EventCard {
        val rawSections = rs.getString("sections")
        val decodedSections = if (rawSections.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<EventSection>>(rawSections) }.getOrDefault(emptyList())
        }
        val rawAttachments = rs.getString("attachments")
        val decodedAttachments = if (rawAttachments.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<EventAttachment>>(rawAttachments) }.getOrDefault(emptyList())
        }
        val rawLocalIds = rs.getString("source_local_ids")
        val decodedLocalIds = if (rawLocalIds.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<Long>>(rawLocalIds) }.getOrDefault(emptyList())
        }
        val decodedChecked = decodeBoundedCheckedItems(rs.getString("checked_items"))
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
            attachments = decodedAttachments,
            timestampSeconds = rs.getLong("timestamp_seconds"),
            isRead = rs.getInt("is_read") == 1,
            isStarred = rs.getInt("is_starred") == 1,
            isDismissed = rs.getInt("is_dismissed") == 1,
            status = EventCardStatus.fromString(rs.getString("status")),
            supersedesCardId = rs.getString("supersedes_card_id")?.takeIf { it.isNotBlank() },
            originalSenderId = rs.getString("original_sender_id") ?: "",
            contentFingerprint = rs.getString("content_fingerprint") ?: "",
            sourceLocalIds = decodedLocalIds,
            isCompleted = rs.getInt("is_completed") == 1,
            checkedItems = decodedChecked
        )
    }

    /** Updates Quick-Edit fields for card [id] transactionally while preserving read/star/source attribution state. */
    fun updateCardFieldsOrThrow(
        id: String,
        title: String,
        category: EventCategory,
        priority: EventPriority,
        eventDate: String?
    ): EventCard? {
        val sql = "UPDATE event_cards SET title = ?, category = ?, priority = ?, event_date = ? WHERE id = ? AND is_dismissed = 0"
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, title)
                    stmt.setString(2, category.name)
                    stmt.setString(3, priority.name)
                    stmt.setString(4, eventDate)
                    stmt.setString(5, id)
                    if (stmt.executeUpdate() == 0) {
                        conn.rollback()
                        return null
                    }
                }
                val updated = findByIdWithConn(conn, id)
                conn.commit()
                return updated
            } catch (e: Exception) {
                runCatching { conn.rollback() }.onFailure { e.addSuppressed(it) }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    /**
     * Replaces extracted fields transactionally after single-card re-extraction while keeping ID/read/star state
     * and pruning any [EventCard.checkedItems] keys no longer present in replacement sections.
     */
    fun replaceExtractedCardOrThrow(existingId: String, replacement: EventCard): EventCard? {
        val sql = """
            UPDATE event_cards
            SET title = ?,
                summary = ?,
                translated_summary = ?,
                sections = ?,
                category = ?,
                priority = ?,
                event_date = ?,
                status = ?,
                supersedes_card_id = ?,
                content_fingerprint = ?,
                checked_items = ?
            WHERE id = ? AND is_dismissed = 0
        """.trimIndent()
        val resolvedFp = replacement.contentFingerprint.ifBlank {
            ContentFingerprint.compute(replacement.sourceMessages, replacement.attachments.map { it.md5 })
        }
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val existing = findByIdWithConn(conn, existingId)
                if (existing == null || existing.isDismissed) {
                    conn.rollback()
                    return null
                }
                val prunedChecked = DetailChecklistKeys.sanitizeCheckedItems(existing.checkedItems, replacement.sections)
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, replacement.title)
                    stmt.setString(2, replacement.summary)
                    stmt.setString(3, replacement.translatedSummary)
                    stmt.setString(4, json.encodeToString(replacement.sections))
                    stmt.setString(5, replacement.category.name)
                    stmt.setString(6, replacement.priority.name)
                    stmt.setString(7, replacement.eventDate)
                    stmt.setString(8, replacement.status.name)
                    stmt.setString(9, replacement.supersedesCardId)
                    stmt.setString(10, resolvedFp)
                    stmt.setString(11, json.encodeToString(prunedChecked))
                    stmt.setString(12, existingId)
                    if (stmt.executeUpdate() == 0) {
                        conn.rollback()
                        return null
                    }
                }
                val updated = findByIdWithConn(conn, existingId)
                conn.commit()
                return updated
            } catch (e: Exception) {
                runCatching { conn.rollback() }.onFailure { e.addSuppressed(it) }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    fun markRead(id: String) {
        executeUpdate("UPDATE event_cards SET is_read = 1 WHERE id = ?", id)
    }

    fun markUnread(id: String) {
        executeUpdate("UPDATE event_cards SET is_read = 0 WHERE id = ?", id)
    }

    /** Sets the `is_starred` flag for card [id] using a parameterized SQL update. */
    fun setStarred(id: String, starred: Boolean) {
        val value = if (starred) 1 else 0
        val sql = "UPDATE event_cards SET is_starred = ? WHERE id = ?"
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setInt(1, value)
                    stmt.setString(2, id)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error updating star state: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    /** Persists the decoupled `is_completed` ("Done") state for card [id], throwing on database error. */
    fun setCompletedOrThrow(id: String, completed: Boolean) {
        val value = if (completed) 1 else 0
        val sql = "UPDATE event_cards SET is_completed = ? WHERE id = ?"
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, value)
                stmt.setString(2, id)
                stmt.executeUpdate()
            }
        }
    }

    /** Sets the `is_completed` flag for card [id], logging and absorbing database errors. */
    fun setCompleted(id: String, completed: Boolean) {
        try {
            setCompletedOrThrow(id, completed)
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error updating completed state: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    /** Persists bounded, distinct checklist checked keys as JSON for card [id], throwing on database error. */
    fun setCheckedItemsOrThrow(id: String, checkedKeys: List<String>) {
        val sanitized = DetailChecklistKeys.sanitizeCheckedItems(checkedKeys)
        val sql = "UPDATE event_cards SET checked_items = ? WHERE id = ?"
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, json.encodeToString(sanitized))
                stmt.setString(2, id)
                stmt.executeUpdate()
            }
        }
    }

    /** Persists checklist checked keys for card [id], logging and absorbing database errors. */
    fun setCheckedItems(id: String, checkedKeys: List<String>) {
        try {
            setCheckedItemsOrThrow(id, checkedKeys)
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error updating checked items: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
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
            System.err.println(
                "[EventCardStore] Error updating translated source messages: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
        }
    }

    /**
     * Persists [attachments] as JSON for card [id], throwing on database errors.
     */
    fun updateAttachmentsOrThrow(id: String, attachments: List<EventAttachment>) {
        val sql = "UPDATE event_cards SET attachments = ? WHERE id = ?"
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, json.encodeToString(attachments))
                stmt.setString(2, id)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Persists [attachments] for card [id], logging and absorbing database errors.
     */
    fun updateAttachments(id: String, attachments: List<EventAttachment>) {
        try {
            updateAttachmentsOrThrow(id, attachments)
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error updating attachments: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    /**
     * Persists updated [sections] JSON and prunes stale [EventCard.checkedItems] transactionally, throwing on database errors.
     */
    fun updateSectionsOrThrow(id: String, sections: List<EventSection>) {
        val sql = "UPDATE event_cards SET sections = ?, checked_items = ? WHERE id = ?"
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val existing = findByIdWithConn(conn, id)
                val prunedChecked = existing?.let {
                    DetailChecklistKeys.sanitizeCheckedItems(it.checkedItems, sections)
                } ?: emptyList()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, json.encodeToString(sections))
                    stmt.setString(2, json.encodeToString(prunedChecked))
                    stmt.setString(3, id)
                    stmt.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                runCatching { conn.rollback() }.onFailure { e.addSuppressed(it) }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    /**
     * Persists updated [sections] JSON for card [id], logging and absorbing database errors.
     */
    fun updateSections(id: String, sections: List<EventSection>) {
        try {
            updateSectionsOrThrow(id, sections)
        } catch (e: Exception) {
            System.err.println("[EventCardStore] Error updating sections: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
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
            System.err.println("[EventCardStore] Error clearing all cards: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
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
            System.err.println("[EventCardStore] Error executing update: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    private fun computeDedupeKey(card: EventCard): String {
        val base = if (card.sourceMessages.isNotBlank()) {
            val normalizedSource = card.sourceMessages.trim().replace(Regex("\\s+"), " ")
            "${card.sourceRoomId}:src:$normalizedSource"
        } else {
            "${card.sourceRoomId}:${card.title}:${card.eventDate.orEmpty()}"
        }
        val raw = if (card.status != EventCardStatus.ACTIVE) {
            "$base:${card.status.name}:${card.supersedesCardId.orEmpty()}"
        } else {
            base
        }
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CARD_SELECT_COLUMNS = """
            id, title, summary, translated_summary, sections, category, priority,
            event_date, original_sender, source_room_id,
            source_room_name, source_messages, translated_source_messages, attachments,
            timestamp_seconds, is_read, is_starred, is_dismissed,
            status, supersedes_card_id, original_sender_id, content_fingerprint, source_local_ids,
            is_completed, checked_items
        """
    }
}
