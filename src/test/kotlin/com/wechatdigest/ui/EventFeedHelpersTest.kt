package com.wechatdigest.ui

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.GroupChat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventFeedHelpersTest {

    @Test
    fun `isLongSourceMessage treats blank and short messages as inline`() {
        assertFalse(isLongSourceMessage(""))
        assertFalse(isLongSourceMessage("   \n  "))

        val shortNotice = "[2026-11-16 15:45:37] Advisor_Chen: Please email Attachment 19 by Nov 25.\nSubmit paper copies on Nov 26 at Room 422-1."
        assertFalse(isLongSourceMessage(shortNotice))
    }

    @Test
    fun `isLongSourceMessage enforces 10-line explicit newline boundary`() {
        val tenLines = (1..10).joinToString("\n") { "Line $it" }
        val elevenLines = (1..11).joinToString("\n") { "Line $it" }

        assertFalse(isLongSourceMessage(tenLines), "Exactly 10 short lines must render inline")
        assertTrue(isLongSourceMessage(elevenLines), "More than 10 lines must collapse to dropdown")
    }

    @Test
    fun `isLongSourceMessage estimates wrapped visual lines for long single-paragraph notices`() {
        val mediumParagraph = "A".repeat(200)
        val longParagraph = "B".repeat(800)

        assertFalse(isLongSourceMessage(mediumParagraph))
        assertTrue(isLongSourceMessage(longParagraph), "Long single-paragraph notice exceeding 10 visual lines must collapse to dropdown")
    }

    @Test
    fun `buildGroupFilterOptions resolves names from availableRooms then sourceRoomName then roomId and sorts alphabetically`() {
        val events = listOf(
            EventCard(
                id = "e1",
                title = "Event 1",
                summary = "S1",
                translatedSummary = "S1",
                category = EventCategory.NOTICE,
                priority = EventPriority.NORMAL,
                originalSender = "A",
                sourceRoomId = "r_zebra@chatroom",
                sourceRoomName = "Card Zebra Fallback",
                timestampSeconds = 100L
            ),
            EventCard(
                id = "e2",
                title = "Event 2",
                summary = "S2",
                translatedSummary = "S2",
                category = EventCategory.EXAM,
                priority = EventPriority.CRITICAL,
                originalSender = "B",
                sourceRoomId = "r_zebra@chatroom",
                sourceRoomName = "",
                timestampSeconds = 200L
            ),
            EventCard(
                id = "e3",
                title = "Event 3",
                summary = "S3",
                translatedSummary = "S3",
                category = EventCategory.DEADLINE,
                priority = EventPriority.IMPORTANT,
                originalSender = "C",
                sourceRoomId = "r_raw_only@chatroom",
                sourceRoomName = "",
                timestampSeconds = 300L
            )
        )

        // Startup before availableRooms is loaded
        val startupOptions = buildGroupFilterOptions(
            events = events,
            availableRooms = emptyList(),
            monitoredRoomIds = setOf("r_alpha@chatroom")
        )
        assertEquals(
            listOf(
                GroupFilterOption("r_zebra@chatroom", "Card Zebra Fallback"),
                GroupFilterOption("r_alpha@chatroom", "r_alpha@chatroom"),
                GroupFilterOption("r_raw_only@chatroom", "r_raw_only@chatroom")
            ),
            startupOptions
        )

        // Once availableRooms is populated, live display names win and sort alphabetically
        val liveOptions = buildGroupFilterOptions(
            events = events,
            availableRooms = listOf(
                GroupChat("r_zebra@chatroom", "Zebra Seminar"),
                GroupChat("r_alpha@chatroom", "alpha Study Club")
            ),
            monitoredRoomIds = setOf("r_alpha@chatroom")
        )
        assertEquals(
            listOf(
                GroupFilterOption("r_alpha@chatroom", "alpha Study Club"),
                GroupFilterOption("r_raw_only@chatroom", "r_raw_only@chatroom"),
                GroupFilterOption("r_zebra@chatroom", "Zebra Seminar")
            ),
            liveOptions
        )
    }

    @Test
    fun `formatPriorityLabel formats all EventPriority entries in title case`() {
        assertEquals("Critical", formatPriorityLabel(EventPriority.CRITICAL))
        assertEquals("Important", formatPriorityLabel(EventPriority.IMPORTANT))
        assertEquals("Normal", formatPriorityLabel(EventPriority.NORMAL))
        assertEquals("Low", formatPriorityLabel(EventPriority.LOW))
    }

    @Test
    fun `formatCategoryLabel formats all EventCategory entries with only first letter capitalized`() {
        assertEquals("Exam", formatCategoryLabel(EventCategory.EXAM))
        assertEquals("Deadline", formatCategoryLabel(EventCategory.DEADLINE))
        assertEquals("Notice", formatCategoryLabel(EventCategory.NOTICE))
        assertEquals("Activity", formatCategoryLabel(EventCategory.ACTIVITY))
        assertEquals("Promotion", formatCategoryLabel(EventCategory.PROMOTION))
        assertEquals("Other", formatCategoryLabel(EventCategory.OTHER))
    }

    @Test
    fun `computeSegmentedCornerRadii uses larger outer corners and smaller inner corners`() {
        assertEquals(SegmentedCornerRadii(16, 16, 16, 16), computeSegmentedCornerRadii(0, 1))
        assertEquals(SegmentedCornerRadii(16, 16, 4, 4), computeSegmentedCornerRadii(0, 3))
        assertEquals(SegmentedCornerRadii(4, 4, 4, 4), computeSegmentedCornerRadii(1, 3))
        assertEquals(SegmentedCornerRadii(4, 4, 16, 16), computeSegmentedCornerRadii(2, 3))
    }

    @Test
    fun `shouldExpandFab collapses on downward scroll and expands on upward scroll or top of list`() {
        // At top of list (index=0, offset=0) -> always expanded
        assertTrue(shouldExpandFab(0, 0, 0, 20, currentlyExpanded = false))

        // Scrolling down within same item past deadband -> collapses
        assertFalse(shouldExpandFab(0, 30, 0, 10, currentlyExpanded = true))

        // Tiny sub-deadband jitter -> preserves current state
        assertFalse(shouldExpandFab(0, 33, 0, 30, currentlyExpanded = false))
        assertTrue(shouldExpandFab(0, 28, 0, 30, currentlyExpanded = true))

        // Scrolling down to next item index -> collapses
        assertFalse(shouldExpandFab(2, 0, 1, 50, currentlyExpanded = true))

        // Scrolling up to earlier item index -> expands
        assertTrue(shouldExpandFab(1, 40, 2, 10, currentlyExpanded = false))

        // Scrolling up within same item past deadband -> expands
        assertTrue(shouldExpandFab(1, 10, 1, 40, currentlyExpanded = false))
    }
}
