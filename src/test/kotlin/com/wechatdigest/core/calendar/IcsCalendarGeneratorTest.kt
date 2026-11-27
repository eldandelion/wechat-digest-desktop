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

    @Test
    fun `generateIcs includes LOCATION, safe URL, checklist items, VALARM, and folds long lines`() {
        val docItem = com.wechatdigest.core.model.EventDetailItem(
            kind = com.wechatdigest.core.model.EventDetailKind.DOCUMENT,
            label = "Adviser Form",
            value = "Signed PDF"
        )
        val card = EventCard(
            id = "enriched-ics-1",
            title = "Comprehensive Graduate Research Symposium Registration and Orientation Session",
            summary = "Register online and bring your signed adviser approval sheet to Room 302.",
            translatedSummary = "Register online and bring your signed adviser approval sheet to Room 302.",
            category = EventCategory.ACTIVITY,
            priority = EventPriority.IMPORTANT,
            eventDate = "2026-12-18T09:30",
            originalSender = "Grad Office",
            sourceRoomId = "grad@chatroom",
            timestampSeconds = 1731000000L,
            checkedItems = listOf(com.wechatdigest.core.model.DetailChecklistKeys.keyFor(0, 2, docItem)),
            sections = listOf(
                com.wechatdigest.core.model.EventSection(
                    heading = "Logistics",
                    items = listOf(
                        com.wechatdigest.core.model.EventDetailItem(
                            kind = com.wechatdigest.core.model.EventDetailKind.LOCATION,
                            label = "Venue",
                            value = "Academic Building Room 302"
                        ),
                        com.wechatdigest.core.model.EventDetailItem(
                            kind = com.wechatdigest.core.model.EventDetailKind.LINK,
                            label = "Portal",
                            value = "https://portal.example.edu/register?session=2026"
                        ),
                        docItem
                    )
                )
            )
        )

        val ics = generator.generateIcs(card)
        val unfolded = ics.replace("\r\n ", "")
        assertTrue(unfolded.contains("LOCATION:Academic Building Room 302"))
        assertTrue(unfolded.contains("URL:https://portal.example.edu/register?session=2026"))
        assertTrue(unfolded.contains("Checklist:"))
        assertTrue(unfolded.contains("[x] Adviser Form: Signed PDF"))
        assertTrue(unfolded.contains("BEGIN:VALARM"))
        assertTrue(unfolded.contains("TRIGGER:-PT1H"))
        assertTrue(ics.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })

        // Unsafe URI schemes, userinfo, local/LAN endpoints, Bidi control chars, and trailing CJK punctuation are handled safely
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("javascript:alert(1)"))
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("file:///etc/passwd"))
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("https://university.edu@attacker.tld/login"))
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("http://127.0.0.1:8080/v1/chat"))
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("http://localhost:11434/api/tags"))
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("http://192.168.1.50/admin"))
        assertEquals(null, IcsCalendarGenerator.extractSafeHttpUrl("https://example.org/\u202Eabc"))
        assertEquals(
            "https://example.org/apply",
            IcsCalendarGenerator.extractSafeHttpUrl("请访问 https://example.org/apply，并填写表格")
        )
        assertEquals("https://example.org/docs", IcsCalendarGenerator.extractSafeHttpUrl("See https://example.org/docs."))
    }

    @Test
    fun `generateIcs omits raw sourceMessages URLs, sanitizes UID CRLF, and preserves timed OffsetDateTime`() {
        val card = EventCard(
            id = "bad\r\nid\nline",
            title = "Offset Notice",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            eventDate = "2026-12-15T14:30:00.123Z",
            originalSender = "Admin",
            sourceRoomId = "r@chatroom",
            sourceMessages = "See https://transcript-only.example.org/private?token=abc",
            timestampSeconds = 1731000000L
        )
        val ics = generator.generateIcs(card)
        assertFalse(ics.contains("URL:"))
        assertTrue(ics.contains("UID:bad__id_line@wechatdigest"))
        assertFalse(ics.contains("DTSTART;VALUE=DATE:"))
        assertTrue(ics.contains("DTSTART:"))
    }
}
