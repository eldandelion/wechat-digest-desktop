package com.wechatdigest.core.model

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadlineTriageTest {

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-27T10:00:00Z")

    @Test
    fun `evaluate classifies overdue, due today, tomorrow, upcoming, and future dates accurately`() {
        val overdueDate = DeadlineTriage.evaluate("2026-11-26", now, utc)
        assertEquals(DeadlineUrgency.OVERDUE, overdueDate?.urgency)
        assertEquals("Overdue", overdueDate?.label)

        val overdueTimeToday = DeadlineTriage.evaluate("2026-11-27T09:00", now, utc)
        assertEquals(DeadlineUrgency.OVERDUE, overdueTimeToday?.urgency)
        assertEquals("Overdue", overdueTimeToday?.label)

        val dueTodayDateOnly = DeadlineTriage.evaluate("2026-11-27", now, utc)
        assertEquals(DeadlineUrgency.DUE_TODAY, dueTodayDateOnly?.urgency)
        assertEquals("Due Today", dueTodayDateOnly?.label)

        val dueTodayLater = DeadlineTriage.evaluate("2026-11-27 18:30", now, utc)
        assertEquals(DeadlineUrgency.DUE_TODAY, dueTodayLater?.urgency)
        assertEquals("Due Today", dueTodayLater?.label)

        val tomorrow = DeadlineTriage.evaluate("2026-11-28", now, utc)
        assertEquals(DeadlineUrgency.UPCOMING, tomorrow?.urgency)
        assertEquals("Tomorrow", tomorrow?.label)

        val inThreeDays = DeadlineTriage.evaluate("2026-11-30", now, utc)
        assertEquals(DeadlineUrgency.UPCOMING, inThreeDays?.urgency)
        assertEquals("In 3d", inThreeDays?.label)

        val nextMonth = DeadlineTriage.evaluate("2026-12-15", now, utc)
        assertEquals(DeadlineUrgency.FUTURE, nextMonth?.urgency)
        assertEquals("Dec 15", nextMonth?.label)

        assertNull(DeadlineTriage.evaluate(null, now, utc))
        assertNull(DeadlineTriage.evaluate("sometime next month", now, utc))
    }

    @Test
    fun `deadlineSortEpoch prioritizes active dated cards before completed, cancelled, and undated cards`() {
        fun sample(
            id: String,
            date: String?,
            completed: Boolean = false,
            status: EventCardStatus = EventCardStatus.ACTIVE
        ) = EventCard(
            id = id,
            title = id,
            summary = id,
            translatedSummary = id,
            category = EventCategory.DEADLINE,
            priority = EventPriority.NORMAL,
            eventDate = date,
            originalSender = "Sender",
            sourceRoomId = "r@chatroom",
            timestampSeconds = 100L,
            isCompleted = completed,
            status = status
        )

        val datedSoon = sample("soon", "2026-11-28")
        val datedLater = sample("later", "2026-12-05")
        val completed = sample("done", "2026-11-27", completed = true)
        val cancelled = sample("cancelled", "2026-11-27", status = EventCardStatus.CANCELLED)
        val undated = sample("undated", null)

        val sorted = listOf(undated, completed, datedLater, cancelled, datedSoon)
            .sortedBy { DeadlineTriage.deadlineSortEpoch(it, utc) }

        assertEquals("soon", sorted[0].id)
        assertEquals("later", sorted[1].id)
        assertEquals(Long.MAX_VALUE - 1, DeadlineTriage.deadlineSortEpoch(completed, utc))
        assertEquals(Long.MAX_VALUE - 1, DeadlineTriage.deadlineSortEpoch(cancelled, utc))
        assertEquals(Long.MAX_VALUE, DeadlineTriage.deadlineSortEpoch(undated, utc))
    }

    @Test
    fun `formatRelativeSyncTime formats buckets cleanly`() {
        val base = 1_800_000_000L
        assertEquals("Never synced", DeadlineTriage.formatRelativeSyncTime(0L, base))
        assertEquals("Synced just now", DeadlineTriage.formatRelativeSyncTime(base - 20L, base))
        assertEquals("Synced 5m ago", DeadlineTriage.formatRelativeSyncTime(base - 305L, base))
        assertEquals("Synced 3h ago", DeadlineTriage.formatRelativeSyncTime(base - 3 * 3600L, base))
        assertEquals("Synced 2d ago", DeadlineTriage.formatRelativeSyncTime(base - 2 * 86_400L, base))
    }

    @Test
    fun `DetailChecklistKeys identifies checkable kinds and builds stable distinct keys`() {
        assertTrue(DetailChecklistKeys.isCheckable(EventDetailKind.ACTION))
        assertTrue(DetailChecklistKeys.isCheckable(EventDetailKind.REQUIREMENT))
        assertTrue(DetailChecklistKeys.isCheckable(EventDetailKind.DOCUMENT))
        assertFalse(DetailChecklistKeys.isCheckable(EventDetailKind.LOCATION))
        assertFalse(DetailChecklistKeys.isCheckable(EventDetailKind.LINK))

        val item = EventDetailItem(EventDetailKind.DOCUMENT, "Transcript", "Official PDF")
        val keyA = DetailChecklistKeys.keyFor(0, 0, item)
        val keyB = DetailChecklistKeys.keyFor(0, 1, item)
        assertTrue(keyA != keyB)
        assertTrue(keyA.startsWith("0:0:"))
    }

    @Test
    fun `parseTemporal preserves timezone offset on fractional-second timestamps and sanitizeCheckedItems bounds keys`() {
        val parsed = DeadlineTriage.parseTemporal("2026-11-27T01:30:00.123-05:00", utc)
        assertEquals(6, parsed?.localDateTime?.hour)
        assertEquals(30, parsed?.localDateTime?.minute)
        assertTrue(DeadlineTriage.isValidIsoTemporal("2026-11-27T01:30:00.123-05:00"))
        assertFalse(DeadlineTriage.isValidIsoTemporal("2026-11-27 (evening)"))

        val sec = EventSection(
            heading = "Checklist",
            items = listOf(EventDetailItem(EventDetailKind.ACTION, "Register", "On portal"))
        )
        val validKey = DetailChecklistKeys.keyFor(0, 0, sec.items.single())
        val sanitized = DetailChecklistKeys.sanitizeCheckedItems(
            listOf(validKey, "stale:99:ACTION:old", validKey, "   "),
            sections = listOf(sec)
        )
        assertEquals(listOf(validKey), sanitized)
    }
}
