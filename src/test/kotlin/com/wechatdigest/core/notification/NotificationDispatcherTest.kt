package com.wechatdigest.core.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDispatcherTest {

    @Test
    fun `buildAppleScript properly formats and escapes quotes and backslashes`() {
        val dispatcher = NotificationDispatcher()

        val scriptWithSubtitle = dispatcher.buildAppleScript(
            title = "WeChat Digest",
            body = "Important: \"Exam\" at room 301 \\ verify ID",
            subtitle = "CRITICAL NOTICE"
        )

        assertTrue(scriptWithSubtitle.contains("with title \"WeChat Digest\""))
        assertTrue(scriptWithSubtitle.contains("subtitle \"CRITICAL NOTICE\""))
        assertTrue(scriptWithSubtitle.contains("\\\"Exam\\\""))
        assertTrue(scriptWithSubtitle.contains("\\\\ verify ID"))

        val scriptWithoutSubtitle = dispatcher.buildAppleScript(
            title = "Notice",
            body = "General notification",
            subtitle = null
        )
        assertFalse(scriptWithoutSubtitle.contains("subtitle"))
    }

    @Test
    fun `sendNotification calls command runner and returns true on success`() {
        var executedScript = ""
        val dispatcher = NotificationDispatcher(
            commandRunner = { script ->
                executedScript = script
            }
        )

        val success = dispatcher.sendNotification(
            title = "WeChat Digest",
            body = "New notices detected",
            subtitle = "Group Update"
        )

        assertTrue(success)
        assertTrue(executedScript.contains("New notices detected"))
    }

    @Test
    fun `sendNotification returns false when command runner throws exception`() {
        val dispatcher = NotificationDispatcher(
            commandRunner = {
                throw RuntimeException("Command failed")
            }
        )

        val success = dispatcher.sendNotification(
            title = "Title",
            body = "Body"
        )

        assertFalse(success)
    }

    @Test
    fun `default NotificationDispatcher instantiates and can format notification`() {
        val dispatcher = NotificationDispatcher()
        val script = dispatcher.buildAppleScript("Title", "Body", "Subtitle")
        assertTrue(script.isNotBlank())
    }

    @Test
    fun `buildAppleScript strips NUL and control characters, normalizes bare CR, and respects bounds`() {
        val dispatcher = NotificationDispatcher()
        val rawBody = "Line1\rLine2\u0000\u0007!" + "A".repeat(600)
        val script = dispatcher.buildAppleScript("T\u0000itle", rawBody, "Sub\rtitle")
        assertFalse(script.contains('\u0000'))
        assertFalse(script.contains('\u0007'))
        assertFalse(script.contains('\r'))
        assertTrue(script.contains("Line1 Line2!"))
        assertTrue(script.length < 700)
    }
}

