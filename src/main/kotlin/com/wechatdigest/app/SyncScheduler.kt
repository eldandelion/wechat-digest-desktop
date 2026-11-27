package com.wechatdigest.app

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.notification.NotificationDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext

class SyncScheduler(
    private val syncAction: suspend () -> SyncResult,
    private val notificationDispatcher: NotificationDispatcher? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val thresholdProvider: () -> NotificationPriorityThreshold = { NotificationPriorityThreshold.ALL },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
                    dispatchNotificationIfEligible(
                        result = result,
                        notifyEnabled = notifyOnNewEvents,
                        threshold = thresholdProvider(),
                        dispatcher = notificationDispatcher,
                        subtitle = "Background Sync",
                        ioDispatcher = ioDispatcher
                    )
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
        dispatchNotificationIfEligible(
            result = result,
            notifyEnabled = notifyOnNewEvents,
            threshold = thresholdProvider(),
            dispatcher = notificationDispatcher,
            subtitle = "Sync Complete",
            ioDispatcher = ioDispatcher
        )
        return result
    }

    companion object {
        /** Sends a system notification on [ioDispatcher] when [notifyEnabled] is true and at least one card meets [threshold]. */
        suspend fun dispatchNotificationIfEligible(
            result: SyncResult,
            notifyEnabled: Boolean,
            threshold: NotificationPriorityThreshold,
            dispatcher: NotificationDispatcher?,
            subtitle: String,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ) {
            if (!notifyEnabled || dispatcher == null || result !is SyncResult.Success || result.newEventsCount <= 0) {
                return
            }
            val matchCount = if (result.insertedCards.isNotEmpty()) {
                result.insertedCards.count { threshold.matches(it.priority) }
            } else {
                result.newEventsCount
            }
            if (matchCount <= 0) return
            withContext(ioDispatcher) {
                dispatcher.sendNotification(
                    title = "WeChat Digest",
                    body = "$matchCount new notice${if (matchCount == 1) "" else "s"} extracted from your groups.",
                    subtitle = subtitle
                )
            }
        }
    }
}
