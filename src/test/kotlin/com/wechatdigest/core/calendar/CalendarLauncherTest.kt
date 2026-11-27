package com.wechatdigest.core.calendar

import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarLauncherTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `exportIcsFile writes valid ics file to target directory`() {
        val launcher = CalendarLauncher(calendarDir = tempDir)
        val card = EventCard(
            id = "launcher-test",
            title = "Registration Deadline",
            summary = "Register for classes",
            translatedSummary = "课程注册截止",
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            eventDate = "2026-11-20",
            originalSender = "Registrar",
            sourceRoomId = "school@chatroom",
            timestampSeconds = 1731000000L
        )

        val file = launcher.exportIcsFile(card)
        assertTrue(file.exists())
        assertEquals("wechat_event_launcher-test.ics", file.name)
        val content = file.readText()
        assertTrue(content.contains("BEGIN:VCALENDAR"))
        assertTrue(content.contains("Registration Deadline"))
    }

    @Test
    fun `exportAndLaunch returns failure when eventDate is null`() {
        val launcher = CalendarLauncher(calendarDir = tempDir)
        val card = EventCard(
            id = "no-date",
            title = "No Date Notice",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.LOW,
            eventDate = null,
            originalSender = "Sender",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1731000000L
        )

        val result = launcher.exportAndLaunch(card)
        assertTrue(result.isFailure)
    }

    @Test
    fun `exportToReminders builds safely escaped AppleScript and handles runner failures`() {
        var capturedScript = ""
        val launcher = CalendarLauncher(
            calendarDir = tempDir,
            remindersRunner = { script -> capturedScript = script }
        )
        val card = EventCard(
            id = "rem-1",
            title = "Submit \"Thesis\" \\ Draft",
            summary = "Upload PDF\nBefore 5pm",
            translatedSummary = "Upload PDF\nBefore 5pm",
            category = EventCategory.DEADLINE,
            priority = EventPriority.CRITICAL,
            eventDate = "2026-12-01",
            originalSender = "Advisor",
            sourceRoomId = "lab@chatroom",
            timestampSeconds = 1731000000L
        )

        val ok = launcher.exportToReminders(card)
        assertTrue(ok.isSuccess)
        assertTrue(capturedScript.contains("""tell application "Reminders""""))
        assertTrue(capturedScript.contains("""Submit \"Thesis\" \\ Draft"""))
        assertTrue(capturedScript.contains("""Upload PDF\nBefore 5pm\nDue: 2026-12-01"""))

        val failing = CalendarLauncher(
            calendarDir = tempDir,
            remindersRunner = { throw IllegalStateException("osascript exited 1") }
        )
        assertTrue(failing.exportToReminders(card).isFailure)
    }
}
