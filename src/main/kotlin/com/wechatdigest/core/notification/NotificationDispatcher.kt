package com.wechatdigest.core.notification

class NotificationDispatcher(
    private val commandRunner: (String) -> Unit = { script ->
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        if (os.contains("mac")) {
            ProcessBuilder("osascript", "-e", script).start()
        }
    }
) {

    fun sendNotification(title: String, body: String, subtitle: String? = null): Boolean {
        return try {
            val script = buildAppleScript(title, body, subtitle)
            commandRunner(script)
            true
        } catch (e: Exception) {
            System.err.println("[NotificationDispatcher] Failed to dispatch notification: ${e.message}")
            false
        }
    }

    internal fun buildAppleScript(title: String, body: String, subtitle: String? = null): String {
        val cleanTitle = escapeAppleScript(title)
        val cleanBody = escapeAppleScript(body)
        val cleanSubtitle = subtitle?.let { escapeAppleScript(it) }

        return if (!cleanSubtitle.isNullOrBlank()) {
            "display notification \"$cleanBody\" with title \"$cleanTitle\" subtitle \"$cleanSubtitle\" sound name \"default\""
        } else {
            "display notification \"$cleanBody\" with title \"$cleanTitle\" sound name \"default\""
        }
    }

    private fun escapeAppleScript(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r\n", " ")
            .replace("\n", " ")
    }
}
