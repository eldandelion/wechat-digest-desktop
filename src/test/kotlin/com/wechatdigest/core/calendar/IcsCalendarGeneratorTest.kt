package com.wechatdigest.core.calendar

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IcsCalendarGeneratorTest {

    private val generator = IcsCalendarGenerator()

    @Test
    fun `generateIcs throws IllegalArgumentException if eventDate is null or blank`() {
        val card = EventCard(
            id = "test-1",
            title = "No Date",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            eventDate = null,
            originalSender = "Sender",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1731000000L
        )

        assertFailsWith<IllegalArgumentException> {
            generator.generateIcs(card)
        }
    }

    @Test
    fun `generateIcs generates valid all-day event for YYYY-MM-DD date`() {
        val card = EventCard(
            id = "exam-1",
            title = "Final Exam, Calculus",
            summary = "Bring pencils; calculator allowed.",
            translatedSummary = "微积分期末考试",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            eventDate = "2026-12-15",
            originalSender = "Prof. Gauss",
            sourceRoomId = "math@chatroom",
            timestampSeconds = 1731000000L
        )

        val ics = generator.generateIcs(card)
        assertNotNull(ics)

        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n") || ics.endsWith("END:VCALENDAR\n") || ics.endsWith("END:VCALENDAR"))
        assertTrue(ics.contains("UID:exam-1@wechatdigest"))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261215"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261216"))
        // Check escaping: comma should be escaped as \, and semicolon as \;
        assertTrue(ics.contains("SUMMARY:Final Exam\\, Calculus"))
        assertTrue(ics.contains("CATEGORIES:EXAM"))
        assertTrue(ics.contains("PRIORITY:1"))
        assertFalse(ics.contains("Action Items:"))
    }

    @Test
    fun `generateIcs generates valid timed event for ISO datetime`() {
        val card = EventCard(
            id = "meeting-1",
            title = "Thesis Defense",
            summary = "Room 302",
            translatedSummary = "论文答辩",
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            eventDate = "2026-12-15T14:30:00",
            originalSender = "Academic Office",
            sourceRoomId = "cs@chatroom",
            timestampSeconds = 1731000000L
        )

        val ics = generator.generateIcs(card)
        assertTrue(ics.contains("DTSTART:20261215T143000"))
        assertTrue(ics.contains("DTEND:20261215T153000"))
        assertTrue(ics.contains("PRIORITY:3"))
    }

    @Test
    fun `generateIcs handles 16-char datetime and millis datetime formats`() {
        val card16 = EventCard(
            id = "event-16",
            title = "Short DateTime",
            summary = "Summary",
            translatedSummary = "",
            category = EventCategory.ACTIVITY,
            priority = EventPriority.NORMAL,
            eventDate = "2026-12-15T14:30",
            originalSender = "Sender",
            sourceRoomId = "room",
            timestampSeconds = 100L
        )
        val ics16 = generator.generateIcs(card16)
        assertTrue(ics16.contains("DTSTART:20261215T143000"))
        assertTrue(ics16.contains("PRIORITY:5"))

        val cardMillis = card16.copy(
            id = "event-millis",
            priority = EventPriority.LOW,
            eventDate = "2026-12-15 14:30:00.123"
        )
        val icsMillis = generator.generateIcs(cardMillis)
        assertTrue(icsMillis.contains("DTSTART:20261215T143000"))
        assertTrue(icsMillis.contains("PRIORITY:9"))
    }

    @Test
    fun `generateIcs falls back to extracting first 10 characters for trailing garbage`() {
        val cardFallback = EventCard(
            id = "event-fb",
            title = "Fallback Date",
            summary = "Same",
            translatedSummary = "Same",
            category = EventCategory.OTHER,
            priority = EventPriority.NORMAL,
            eventDate = "2026-12-15 (afternoon)",
            originalSender = "Sender",
            sourceRoomId = "room",
            timestampSeconds = 100L
        )
        val ics = generator.generateIcs(cardFallback)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261215"))
    }

    @Test
    fun `generateIcs throws IllegalArgumentException for completely unparseable date`() {
        val invalidCard = EventCard(
            id = "event-invalid",
            title = "Invalid Date",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.OTHER,
            priority = EventPriority.NORMAL,
            eventDate = "next week Monday",
            originalSender = "Sender",
            sourceRoomId = "room",
            timestampSeconds = 100L
        )
        assertFailsWith<IllegalArgumentException> {
            generator.generateIcs(invalidCard)
        }
    }

    @Test
    fun `escapeText correctly escapes RFC 5545 reserved characters`() {
        val input = "Hello; world, this is a \\ test\nLine 2"
        val escaped = generator.escapeText(input)
        assertEquals("Hello\\; world\\, this is a \\\\ test\\nLine 2", escaped)
    }
}
