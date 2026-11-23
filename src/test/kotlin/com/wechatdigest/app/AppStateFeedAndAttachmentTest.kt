package com.wechatdigest.app

import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventDetailItem
import com.wechatdigest.core.model.EventDetailKind
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.storage.AppDatabase
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.SettingsStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateFeedAndAttachmentTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var appDb: AppDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var eventCardStore: EventCardStore
    private lateinit var syncService: SyncService
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dbFile = File(tempDir, "test_app_feed.db")
        appDb = AppDatabase(dbFile)
        settingsStore = SettingsStore(appDb)
        eventCardStore = EventCardStore(appDb)
        syncService = SyncService(eventCardStore)
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        testScope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test
    fun `visibleEvents sorts by date newest-to-oldest and oldest-to-newest with deterministic priority tie-break`() = runBlocking {
        val oldest = EventCard("t1000", "Oldest Event", "First", "First", category = EventCategory.NOTICE, priority = EventPriority.NORMAL, originalSender = "Admin", sourceRoomId = "room@chatroom", timestampSeconds = 1000L)
        val tieLow = EventCard("t2000_low", "Tie Low", "Low", "Low", category = EventCategory.NOTICE, priority = EventPriority.LOW, originalSender = "Admin", sourceRoomId = "room@chatroom", timestampSeconds = 2000L)
        val tieCritical = EventCard("t2000_crit", "Tie Critical", "Crit", "Crit", category = EventCategory.EXAM, priority = EventPriority.CRITICAL, originalSender = "Admin", sourceRoomId = "room@chatroom", timestampSeconds = 2000L)
        val newest = EventCard("t3000", "Newest Event", "Latest", "Latest", category = EventCategory.DEADLINE, priority = EventPriority.IMPORTANT, originalSender = "Admin", sourceRoomId = "room@chatroom", timestampSeconds = 3000L)
        eventCardStore.upsert(listOf(oldest, tieLow, tieCritical, newest))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.loadEvents().join()
        assertEquals(4, withTimeout(2_000) { appState.visibleEvents.first { it.size == 4 } }.size)

        assertEquals(EventSortOrder.NEWEST_FIRST, appState.sortOrder.value)
        assertEquals(listOf("t3000", "t2000_crit", "t2000_low", "t1000"), appState.visibleEvents.value.map { it.id })

        appState.sortOrder.value = EventSortOrder.OLDEST_FIRST
        assertEquals(listOf("t1000", "t2000_crit", "t2000_low", "t3000"), appState.visibleEvents.value.map { it.id })
    }

    @Test
    fun `clearEventsAndCheckpoints and resetLocalAppDatabase clear stale groupFilter and reset sortOrder`() = runBlocking {
        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.priorityFilter.value = EventPriority.CRITICAL
        appState.groupFilter.value = "room1@chatroom"
        appState.starredOnlyFilter.value = true
        appState.unreadOnlyFilter.value = true
        appState.sortOrder.value = EventSortOrder.OLDEST_FIRST
        appState.searchQuery.value = "exam"
        appState.groupSearchQuery.value = "math"

        appState.clearEventsAndCheckpoints().join()
        assertNull(appState.groupFilter.value)
        assertFalse(appState.starredOnlyFilter.value)
        assertFalse(appState.unreadOnlyFilter.value)

        appState.groupFilter.value = "room2@chatroom"
        appState.starredOnlyFilter.value = true
        appState.unreadOnlyFilter.value = true
        appState.resetLocalAppDatabase().join()

        assertNull(appState.priorityFilter.value)
        assertNull(appState.groupFilter.value)
        assertFalse(appState.starredOnlyFilter.value)
        assertFalse(appState.unreadOnlyFilter.value)
        assertEquals(EventSortOrder.NEWEST_FIRST, appState.sortOrder.value)
        assertEquals("", appState.searchQuery.value)
        assertEquals("", appState.groupSearchQuery.value)
    }

    @Test
    fun `toggleStarred and starredOnlyFilter persist and filter starred events`() = runBlocking {
        val c1 = EventCard("s1", "Starred Candidate", "Save this", "Save this", category = EventCategory.NOTICE, priority = EventPriority.IMPORTANT, originalSender = "Admin", sourceRoomId = "r1@chatroom", timestampSeconds = 2000L)
        val c2 = EventCard("s2", "Regular Notice", "Unstarred", "Unstarred", category = EventCategory.NOTICE, priority = EventPriority.NORMAL, originalSender = "Admin", sourceRoomId = "r1@chatroom", timestampSeconds = 1000L)
        eventCardStore.upsert(listOf(c1, c2))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.loadEvents().join()

        appState.toggleStarred("s1").join()
        assertTrue(appState.events.value.first { it.id == "s1" }.isStarred)
        assertTrue(eventCardStore.listActive().first { it.id == "s1" }.isStarred)

        appState.starredOnlyFilter.value = true
        assertEquals(listOf("s1"), appState.visibleEvents.value.map { it.id })

        appState.toggleStarred("s1").join()
        assertTrue(appState.visibleEvents.value.isEmpty())
    }

    @Test
    fun `unreadOnlyFilter shows only unread events and combines with starredOnlyFilter`() = runBlocking {
        val unreadStarred = EventCard("u_s", "Unread Starred", "Body", "Body", category = EventCategory.EXAM, priority = EventPriority.CRITICAL, originalSender = "Admin", sourceRoomId = "r1@chatroom", timestampSeconds = 3000L, isRead = false, isStarred = true)
        val unreadPlain = EventCard("u_p", "Unread Plain", "Body", "Body", category = EventCategory.NOTICE, priority = EventPriority.NORMAL, originalSender = "Admin", sourceRoomId = "r1@chatroom", timestampSeconds = 2000L, isRead = false, isStarred = false)
        val readStarred = EventCard("r_s", "Read Starred", "Body", "Body", category = EventCategory.NOTICE, priority = EventPriority.NORMAL, originalSender = "Admin", sourceRoomId = "r1@chatroom", timestampSeconds = 1000L, isRead = true, isStarred = true)
        eventCardStore.upsert(listOf(unreadStarred, unreadPlain, readStarred))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.loadEvents().join()
        assertEquals(3, withTimeout(2_000) { appState.visibleEvents.first { it.size == 3 } }.size)

        appState.unreadOnlyFilter.value = true
        assertEquals(listOf("u_s", "u_p"), appState.visibleEvents.value.map { it.id })

        appState.starredOnlyFilter.value = true
        assertEquals(listOf("u_s"), appState.visibleEvents.value.map { it.id })

        appState.markRead("u_s").join()
        assertTrue(appState.visibleEvents.value.isEmpty())

        appState.markUnread("u_s").join()
        assertEquals(listOf("u_s"), appState.visibleEvents.value.map { it.id })
    }

    @Test
    fun `visibleEvents searchQuery matches text inside section detail items`() = runBlocking {
        val examCard = EventCard(
            id = "search-item-1",
            title = "Final Exam Notice",
            summary = "Important logistics.",
            translatedSummary = "Important logistics.",
            sections = listOf(
                EventSection(
                    heading = "Logistics",
                    items = listOf(
                        EventDetailItem(EventDetailKind.LOCATION, "Venue", "Auditorium 401")
                    )
                )
            ),
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof",
            sourceRoomId = "r1@chatroom",
            timestampSeconds = 2000L
        )
        eventCardStore.upsert(listOf(examCard))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.loadEvents().join()
        assertEquals(1, withTimeout(2_000) { appState.visibleEvents.first { it.size == 1 } }.size)

        appState.searchQuery.value = "Auditorium 401"
        assertEquals(listOf("search-item-1"), appState.visibleEvents.value.map { it.id })

        appState.searchQuery.value = "Venue"
        assertEquals(listOf("search-item-1"), appState.visibleEvents.value.map { it.id })

        appState.searchQuery.value = "Nonexistent Room"
        assertTrue(appState.visibleEvents.value.isEmpty())
    }

    @Test
    fun `selectEvent marks unread card as read and markUnread restores unread status`() = runBlocking {
        val unreadCard = EventCard("u1", "Unread Exam Notice", "Details", "Details", category = EventCategory.EXAM, priority = EventPriority.CRITICAL, originalSender = "Prof", sourceRoomId = "r1@chatroom", timestampSeconds = 3000L, isRead = false)
        eventCardStore.upsert(listOf(unreadCard))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.loadEvents().join()
        assertFalse(appState.events.value.single().isRead)

        appState.selectEvent("u1")
        assertEquals("u1", appState.selectedEventId.value)
        assertTrue(withTimeout(2_000) { appState.events.first { it.single().isRead } }.single().isRead)

        appState.markUnread("u1").join()
        assertFalse(appState.events.value.single().isRead)
        assertFalse(eventCardStore.listActive().single().isRead)

        appState.dismiss("u1").join()
        assertNull(appState.selectedEventId.value)
    }

    @Test
    fun `recheckCardAttachments discovers newly downloaded file and updates store and state`() = runBlocking {
        val accountDir = File(tempDir, "wxid_recheck_account").apply { mkdirs() }
        val dbDir = File(accountDir, "db_storage").apply { mkdirs() }
        val monthDir = File(accountDir, "msg/file/2026-11").apply { mkdirs() }
        val ts = 1795000000L
        val pendingAttachment = EventAttachment("seating_chart.xlsx", "xlsx", 128L, localPath = null, messageTimestampSeconds = ts)
        val card = EventCard("recheck-1", "Seating Chart Posted", "Check your seat.", "Check your seat.", category = EventCategory.EXAM, priority = EventPriority.IMPORTANT, originalSender = "Advisor", sourceRoomId = "r1@chatroom", attachments = listOf(pendingAttachment), timestampSeconds = ts)
        eventCardStore.upsert(listOf(card))
        settingsStore.save(AppSettings(wechatDataDir = dbDir.absolutePath))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        appState.loadEvents().join()
        assertFalse(appState.events.value.single().attachments.single().isLocalFilePresent())

        val downloaded = File(monthDir, "seating_chart.xlsx").apply { writeBytes(ByteArray(128) { 7 }) }
        val snackDeferred = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { appState.snackbarMessage.first() } }
        appState.recheckCardAttachments("recheck-1").join()
        val snack = snackDeferred.await()

        assertTrue(snack.contains("Located 1 downloaded attachment"))
        assertEquals(downloaded.canonicalPath, appState.events.value.single().attachments.single().localPath)
        assertEquals(downloaded.canonicalPath, eventCardStore.listActive().single().attachments.single().localPath)
    }

    @Test
    fun `openAttachment and revealAttachmentInFinder enforce missing path, allowedRoot, and safe extension snackbars`() = runBlocking {
        val accountDir = File(tempDir, "wxid_launcher_state").apply { mkdirs() }
        val dbDir = File(accountDir, "db_storage").apply { mkdirs() }
        val msgFileDir = File(accountDir, "msg/file/2026-11").apply { mkdirs() }
        val unsafeFile = File(msgFileDir, "script.command").apply { writeText("echo hi") }
        settingsStore.save(AppSettings(wechatDataDir = dbDir.absolutePath))

        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)

        val missingSnackDeferred = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { appState.snackbarMessage.first() } }
        appState.openAttachment(EventAttachment("pending.pdf", "pdf", localPath = null)).join()
        assertTrue(missingSnackDeferred.await().contains("not downloaded on disk yet"))

        val blockedSnackDeferred = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { appState.snackbarMessage.first() } }
        appState.openAttachment(EventAttachment("script.command", "command", localPath = unsafeFile.absolutePath)).join()
        assertTrue(blockedSnackDeferred.await().contains("blocked for safety"))

        // Unconfigured root prevents reveal in Finder cleanly
        appState.updateSettings(AppSettings(wechatDataDir = "")).join()
        val unverifiedDeferred = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { appState.snackbarMessage.first() } }
        appState.revealAttachmentInFinder(EventAttachment("script.command", "command", localPath = unsafeFile.absolutePath)).join()
        assertTrue(unverifiedDeferred.await().contains("could not be verified"))
    }

    @Test
    fun `concurrent toggleRoomMonitored preserves both updates atomically`() = runBlocking {
        val appState = AppState(settingsStore, eventCardStore, syncService, scope = testScope)
        val j1 = appState.toggleRoomMonitored("roomA@chatroom")
        val j2 = appState.toggleRoomMonitored("roomB@chatroom")
        j1.join()
        j2.join()
        assertEquals(setOf("roomA@chatroom", "roomB@chatroom"), appState.settings.value.monitoredRoomIds)
    }

    @Test
    fun `enrichSectionCards structures legacy bullets, persists to SQLite, and handles errors safely`() = runBlocking {
        val legacyCard = EventCard(
            id = "enrich-state-1",
            title = "Thesis Defense Submission",
            summary = "Prepare thesis defense documents.",
            translatedSummary = "Prepare thesis defense documents.",
            sections = listOf(
                EventSection(
                    heading = "Required Documents",
                    content = "- Bring three bound copies\n- Submit PDF on portal"
                )
            ),
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Advisor",
            sourceRoomId = "r1@chatroom",
            timestampSeconds = 2000L
        )
        eventCardStore.upsert(listOf(legacyCard))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = testScope,
            llmClientFactory = { cfg ->
                KtorLlmClient(
                    cfg.copy(apiKey = "test-key"),
                    MockEngine {
                        val content = """
                            {
                              "sections": [
                                {
                                  "section_index": 0,
                                  "items": [
                                    {"kind": "COPIES", "label": "Bound Copies", "value": "Bring three bound copies"},
                                    {"kind": "ACTION", "label": "Portal Upload", "value": "Submit PDF on portal"}
                                  ],
                                  "content": ""
                                }
                              ]
                            }
                        """.trimIndent()
                        val body = """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}}]}"""
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                    }
                )
            }
        )
        appState.loadEvents().join()
        assertEquals(1, withTimeout(2_000) { appState.events.first { it.size == 1 } }.size)

        appState.enrichSectionCards("enrich-state-1").join()

        assertTrue(appState.enrichingCardIds.value.isEmpty())
        val updatedSection = appState.events.value.single().sections.single()
        assertEquals(2, updatedSection.items.size)
        assertEquals("Bound Copies", updatedSection.items[0].label)
        assertEquals("", updatedSection.content)

        val persistedSection = eventCardStore.listActive().single().sections.single()
        assertEquals(2, persistedSection.items.size)
        assertEquals(EventDetailKind.COPIES, persistedSection.items[0].kind)
    }
}
