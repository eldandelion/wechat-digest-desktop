package com.wechatdigest.app

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.notification.NotificationDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SyncScheduler(
    private val syncAction: suspend () -> SyncResult,
    private val notificationDispatcher: NotificationDispatcher? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(intervalMinutes: Int, notifyOnNewEvents: Boolean = true) {
        stop()
        if (intervalMinutes <= 0) return

        job = scope.launch {
            while (isActive) {
                delay(intervalMinutes * 60 * 1000L)
                if (!isActive) break

                try {
                    val result = syncAction()
                    if (result is SyncResult.Success && result.newEventsCount > 0 && notifyOnNewEvents) {
                        notificationDispatcher?.sendNotification(
                            title = "WeChat Digest",
                            body = "${result.newEventsCount} new notices extracted from your groups.",
                            subtitle = "Background Sync"
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    System.err.println(
                        "[SyncScheduler] Background sync error: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun triggerOnceNow(notifyOnNewEvents: Boolean = true): SyncResult {
        val result = syncAction()
        if (result is SyncResult.Success && result.newEventsCount > 0 && notifyOnNewEvents) {
            notificationDispatcher?.sendNotification(
                title = "WeChat Digest",
                body = "${result.newEventsCount} new notices extracted from your groups.",
                subtitle = "Sync Complete"
            )
        }
        return result
    }
}
