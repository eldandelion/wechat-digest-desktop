package com.wechatdigest.core.notification

import com.wechatdigest.core.llm.LlmJsonUtils

class NotificationDispatcher(
    private val commandRunner: (String) -> Unit = { script ->
        AppleScriptRunner.runScriptViaStdin(script, timeoutSeconds = 3L)
    }
) {

    fun sendNotification(title: String, body: String, subtitle: String? = null): Boolean {
        return try {
            val script = buildAppleScript(title, body, subtitle)
            commandRunner(script)
            true
        } catch (e: Exception) {
            System.err.println(
                "[NotificationDispatcher] Failed to dispatch notification: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            false
        }
    }

    internal fun buildAppleScript(title: String, body: String, subtitle: String? = null): String {
        val cleanTitle = AppleScriptRunner.escapeString(
            AppleScriptRunner.truncateCodePointSafe(title, 120),
            replaceNewlinesWithSpace = true
        )
        val cleanBody = AppleScriptRunner.escapeString(
            AppleScriptRunner.truncateCodePointSafe(body, 500),
            replaceNewlinesWithSpace = true
        )
        val cleanSubtitle = subtitle?.let {
            AppleScriptRunner.escapeString(
                AppleScriptRunner.truncateCodePointSafe(it, 120),
                replaceNewlinesWithSpace = true
            )
        }

        return if (!cleanSubtitle.isNullOrBlank()) {
            "display notification \"$cleanBody\" with title \"$cleanTitle\" subtitle \"$cleanSubtitle\" sound name \"default\""
        } else {
            "display notification \"$cleanBody\" with title \"$cleanTitle\" sound name \"default\""
        }
    }
}
