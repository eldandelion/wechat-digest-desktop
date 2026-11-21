package com.wechatdigest.app

import com.wechatdigest.core.notification.NotificationDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    @Test
    fun `scheduler runs periodically and dispatches notifications when new events are found`() = runTest {
        var syncCount = 0
        var notificationSent = false

        val mockDispatcher = NotificationDispatcher(
            commandRunner = {
                notificationSent = true
            }
        )

        val scheduler = SyncScheduler(
            syncAction = {
                syncCount++
                SyncResult.Success(newEventsCount = 2, totalScannedMessages = 10)
            },
            notificationDispatcher = mockDispatcher,
            scope = backgroundScope
        )

        assertFalse(scheduler.isRunning)

        // Start scheduler with 15 minutes interval
        scheduler.start(intervalMinutes = 15, notifyOnNewEvents = true)
        assertTrue(scheduler.isRunning)

        // Advance 14 minutes -> no sync yet
        advanceTimeBy(14 * 60 * 1000L)
        runCurrent()
        assertEquals(0, syncCount)

        // Advance 1 more minute (total 15 mins) -> 1st sync triggers
        advanceTimeBy(1 * 60 * 1000L)
        runCurrent()
        assertEquals(1, syncCount)
        assertTrue(notificationSent)

        // Advance another 15 minutes -> 2nd sync triggers
        advanceTimeBy(15 * 60 * 1000L)
        runCurrent()
        assertEquals(2, syncCount)

        // Stop scheduler
        scheduler.stop()
        runCurrent()
        assertFalse(scheduler.isRunning)

        // Advance another 30 minutes -> no additional syncs
        advanceTimeBy(30 * 60 * 1000L)
        runCurrent()
        assertEquals(2, syncCount)
    }

    @Test
    fun `scheduler does not run when interval is 0 or negative`() = runTest {
        val scheduler = SyncScheduler(
            syncAction = { SyncResult.Success(0, 0) },
            scope = backgroundScope
        )

        scheduler.start(intervalMinutes = 0)
        assertFalse(scheduler.isRunning)

        scheduler.start(intervalMinutes = -5)
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `triggerOnceNow executes sync immediately`() = runTest {
        var called = false
        val scheduler = SyncScheduler(
            syncAction = {
                called = true
                SyncResult.Success(1, 5)
            },
            scope = backgroundScope
        )

        val result = scheduler.triggerOnceNow()
        assertTrue(called)
        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).newEventsCount)
    }
}

