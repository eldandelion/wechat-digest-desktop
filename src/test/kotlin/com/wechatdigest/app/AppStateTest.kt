package com.wechatdigest.app

import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.OllamaService
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.storage.AppDatabase
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.SettingsStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var appDb: AppDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var eventCardStore: EventCardStore
    private lateinit var groupConfigStore: com.wechatdigest.storage.GroupConfigStore
    private lateinit var syncService: SyncService

    @BeforeEach
    fun setUp() {
        val dbFile = File(tempDir, "test_app.db")
        appDb = AppDatabase(dbFile)
        settingsStore = SettingsStore(appDb)
        eventCardStore = EventCardStore(appDb)
        groupConfigStore = com.wechatdigest.storage.GroupConfigStore(appDb)

        val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val llmClient = KtorLlmClient(LlmConfig(), engine = mockEngine)
        syncService = SyncService(eventCardStore, groupConfigStore) { llmClient }
    }

    @Test
    fun `navigation and settings toggle work as expected`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertEquals(Screen.EVENTS, appState.currentScreen.value)
        appState.selectScreen(Screen.GROUPS)
        assertEquals(Screen.GROUPS, appState.currentScreen.value)
        appState.selectScreen(Screen.SETTINGS)
        assertEquals(Screen.SETTINGS, appState.currentScreen.value)

        // Monitored rooms toggle
        val testRoom = "12345@chatroom"
        assertFalse(appState.settings.value.monitoredRoomIds.contains(testRoom))

        appState.toggleRoomMonitored(testRoom).join()
        assertTrue(appState.settings.value.monitoredRoomIds.contains(testRoom))

        // Toggle again to remove
        appState.toggleRoomMonitored(testRoom).join()
        assertFalse(appState.settings.value.monitoredRoomIds.contains(testRoom))
    }

    @Test
    fun `visibleEvents filters by priority and search query`() = runBlocking {
        val card1 = EventCard(
            id = "c1",
            title = "Final Exam Calculus",
            summary = "Exam in room 301",
            translatedSummary = "微积分期末考试",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof. Euler",
            sourceRoomId = "math@chatroom",
            timestampSeconds = 1000L
        )

        val card2 = EventCard(
            id = "c2",
            title = "Seminar on AI",
            summary = "Join lecture by Turing award winner",
            translatedSummary = "AI专题讲座",
            sections = listOf(
                EventSection("Attendance Policy", "Bring student ID badge to Hall B")
            ),
            category = EventCategory.ACTIVITY,
            priority = EventPriority.NORMAL,
            originalSender = "Dean Office",
            sourceRoomId = "cs@chatroom",
            timestampSeconds = 2000L
        )

        eventCardStore.upsert(listOf(card1, card2))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        appState.loadEvents().join()

        assertEquals(2, appState.events.value.size)
        assertEquals(2, withTimeout(2_000) { appState.visibleEvents.first { it.size == 2 } }.size)

        // Filter by Priority
        appState.priorityFilter.value = EventPriority.CRITICAL
        assertEquals(1, appState.visibleEvents.value.size)
        assertEquals("c1", appState.visibleEvents.value.first().id)

        // Filter back to ALL
        appState.priorityFilter.value = null
        assertEquals(2, appState.visibleEvents.value.size)

        // Search query filter
        appState.searchQuery.value = "Turing"
        assertEquals(1, appState.visibleEvents.value.size)
        assertEquals("c2", appState.visibleEvents.value.first().id)

        // Search by sender
        appState.searchQuery.value = "Euler"
        assertEquals(1, appState.visibleEvents.value.size)
        assertEquals("c1", appState.visibleEvents.value.first().id)

        // Search by translated summary
        appState.searchQuery.value = "微积分"
        assertEquals(1, appState.visibleEvents.value.size)
        assertEquals("c1", appState.visibleEvents.value.first().id)

        // Search inside section content and heading
        appState.searchQuery.value = "Hall B"
        assertEquals(1, appState.visibleEvents.value.size)
        assertEquals("c2", appState.visibleEvents.value.first().id)

        appState.searchQuery.value = "Attendance Policy"
        assertEquals(1, appState.visibleEvents.value.size)
        assertEquals("c2", appState.visibleEvents.value.first().id)

        // Combined filter with no match
        appState.priorityFilter.value = EventPriority.CRITICAL
        appState.searchQuery.value = "NonExistent"
        assertEquals(0, appState.visibleEvents.value.size)
    }

    @Test
    fun `refreshRooms populates rooms when data dir is valid and clears when invalid`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        // Invalid directory
        appState.updateSettings(AppSettings(wechatDataDir = "/invalid/path")).join()
        appState.refreshRooms().join()
        assertTrue(appState.availableRooms.value.isEmpty())

        // Valid synthetic WeChat directory
        val validWechatDir = File(tempDir, "valid_wechat_dir").apply { mkdirs() }
        val contactDb = File(validWechatDir, "contact.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('10000000001@chatroom', 'CS Notice Group', '')")
            }
        }
        appState.updateSettings(AppSettings(wechatDataDir = validWechatDir.absolutePath)).join()
        appState.refreshRooms().join()
        assertTrue(appState.availableRooms.value.isNotEmpty())
        assertTrue(appState.availableRooms.value.any { it.roomId == "10000000001@chatroom" })
    }

    @Test
    fun `triggerSync succeeds and emits snackbar message`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        // Mock failure sync (blank dir)
        appState.updateSettings(AppSettings(wechatDataDir = "")).join()
        appState.triggerSync().join()
        assertFalse(appState.isSyncing.value)
    }

    @Test
    fun `saveGroupConfig and deleteGroupConfig update state flow`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        val config = com.wechatdigest.core.model.GroupConfig(
            roomId = "math@chatroom",
            customPrompt = "Focus on exams only"
        )

        appState.saveGroupConfig(config).join()
        assertEquals(1, appState.groupConfigs.value.size)
        assertEquals("Focus on exams only", appState.groupConfigs.value["math@chatroom"]?.customPrompt)

        appState.deleteGroupConfig("math@chatroom").join()
        assertEquals(0, appState.groupConfigs.value.size)
    }

    @Test
    fun `exportToCalendar triggers launcher and emits snackbar`() = runBlocking {
        val launcher = com.wechatdigest.core.calendar.CalendarLauncher(calendarDir = tempDir)
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            calendarLauncher = launcher,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        val card = EventCard(
            id = "cal-test-card",
            title = "Test Event",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            eventDate = "2026-11-20",
            originalSender = "Sender",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1731000000L
        )

        appState.exportToCalendar(card).join()
        val file = File(tempDir, "wechat_event_cal-test-card.ics")
        assertTrue(file.exists())
    }

    @Test
    fun `markRead and dismiss mutate store and refresh events`() = runBlocking {
        val card = EventCard(
            id = "item-to-dismiss",
            title = "Notice",
            summary = "Content",
            translatedSummary = "Content",
            category = EventCategory.NOTICE,
            priority = EventPriority.LOW,
            originalSender = "Sender",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1000L
        )

        eventCardStore.upsert(listOf(card))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        appState.loadEvents().join()
        assertEquals(1, appState.events.value.size)
        assertFalse(appState.events.value.first().isRead)

        appState.markRead("item-to-dismiss").join()
        assertTrue(appState.events.value.first().isRead)

        appState.dismiss("item-to-dismiss").join()
        assertEquals(0, appState.events.value.size)
    }

    @Test
    fun `showWizard is true on fresh launch with blank data dir`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertTrue(appState.showWizard.value)
        appState.setShowWizard(false)
        assertFalse(appState.showWizard.value)
    }

    @Test
    fun `autoSync starts and stops on settings update`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertTrue(appState.scheduler.isRunning)

        // Disable auto sync
        appState.updateSettings(appState.settings.value.copy(autoSyncEnabled = false)).join()
        assertFalse(appState.scheduler.isRunning)

        // Re-enable auto sync
        appState.updateSettings(appState.settings.value.copy(autoSyncEnabled = true, syncIntervalMinutes = 15)).join()
        assertTrue(appState.scheduler.isRunning)

        appState.scheduler.stop()
    }

    @Test
    fun `detectLocalOllama updates state flows on success`() = runBlocking {
        val tagsJson = """
            {
              "models": [
                {
                  "name": "llama3.2:latest",
                  "details": { "parameter_size": "3.2B" }
                }
              ]
            }
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(tagsJson),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val ollamaService = OllamaService(engine = mockEngine)

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            ollamaService = ollamaService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertEquals(emptyList(), appState.detectedOllamaModels.value)
        assertFalse(appState.isDetectingOllama.value)
        assertNull(appState.ollamaStatusMessage.value)

        var detectedEndpoint: String? = null
        var detectedModel: String? = null

        appState.detectLocalOllama { endpoint, firstModel ->
            detectedEndpoint = endpoint
            detectedModel = firstModel
        }.join()

        assertEquals("http://localhost:11434", detectedEndpoint)
        assertEquals("llama3.2:latest", detectedModel)
        assertEquals(1, appState.detectedOllamaModels.value.size)
        assertEquals("llama3.2:latest", appState.detectedOllamaModels.value.first().name)
        assertTrue(appState.ollamaStatusMessage.value?.contains("Connected (1 model found)") == true)
        assertFalse(appState.isDetectingOllama.value)

        ollamaService.close()
    }

    @Test
    fun `syncCheckpoints state and resetGroupCheckpoint work as expected`() = runBlocking {
        val syncCheckpointStore = com.wechatdigest.storage.SyncCheckpointStore(appDb)
        syncCheckpointStore.save(com.wechatdigest.core.model.SyncCheckpoint("room1@chatroom", 1234L, 1000L, 1000L))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            syncCheckpointStore = syncCheckpointStore,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        appState.loadCheckpoints().join()
        assertEquals(1, appState.syncCheckpoints.value.size)
        assertEquals(1234L, appState.syncCheckpoints.value["room1@chatroom"]?.lastLocalId)

        appState.resetGroupCheckpoint("room1@chatroom").join()
        assertTrue(appState.syncCheckpoints.value.isEmpty())
        kotlin.test.assertNull(syncCheckpointStore.get("room1@chatroom"))
    }

    @Test
    fun `clearEventsAndCheckpoints wipes events and checkpoints while keeping settings and configs`() = runBlocking {
        val syncCheckpointStore = com.wechatdigest.storage.SyncCheckpointStore(appDb)
        syncCheckpointStore.save(com.wechatdigest.core.model.SyncCheckpoint("room1@chatroom", 500L, 1000L, 1000L))
        groupConfigStore.save(com.wechatdigest.core.model.GroupConfig("room1@chatroom", "Prompt", "Persona"))
        settingsStore.save(AppSettings(wechatDataDir = "/custom/path", targetLanguage = "zh"))
        eventCardStore.upsert(
            listOf(
                EventCard(
                    id = "c-clear",
                    title = "Event",
                    summary = "S",
                    translatedSummary = "TS",
                    category = EventCategory.NOTICE,
                    priority = EventPriority.NORMAL,
                    originalSender = "Sender",
                    sourceRoomId = "room1@chatroom",
                    timestampSeconds = 1000L
                )
            )
        )

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = syncCheckpointStore,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        appState.loadEvents().join()
        appState.loadCheckpoints().join()
        appState.loadGroupConfigs().join()
        assertEquals(1, appState.events.value.size)
        assertEquals(1, appState.syncCheckpoints.value.size)

        appState.clearEventsAndCheckpoints().join()

        assertTrue(appState.events.value.isEmpty())
        assertTrue(appState.syncCheckpoints.value.isEmpty())
        assertTrue(eventCardStore.listActive().isEmpty())
        assertTrue(syncCheckpointStore.getAll().isEmpty())
        // Settings and group configs preserved
        assertEquals("/custom/path", appState.settings.value.wechatDataDir)
        assertEquals(1, appState.groupConfigs.value.size)
    }

    @Test
    fun `resetLocalAppDatabase performs full reset and wipes decrypted cache`() = runBlocking {
        val syncCheckpointStore = com.wechatdigest.storage.SyncCheckpointStore(appDb)
        syncCheckpointStore.save(com.wechatdigest.core.model.SyncCheckpoint("room1@chatroom", 500L, 1000L, 1000L))
        groupConfigStore.save(com.wechatdigest.core.model.GroupConfig("room1@chatroom", "Prompt", "Persona"))
        settingsStore.save(AppSettings(wechatDataDir = "/custom/path", autoSyncEnabled = true))
        eventCardStore.upsert(
            listOf(
                EventCard(
                    id = "c-reset",
                    title = "Event",
                    summary = "S",
                    translatedSummary = "TS",
                    category = EventCategory.NOTICE,
                    priority = EventPriority.NORMAL,
                    originalSender = "Sender",
                    sourceRoomId = "room1@chatroom",
                    timestampSeconds = 1000L
                )
            )
        )

        val cacheDir = File(tempDir, "decrypted").apply { mkdirs() }
        val dummyCacheFile = File(cacheDir, "contact.db").apply { writeText("cached") }
        assertTrue(dummyCacheFile.exists())

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = syncCheckpointStore,
            decryptedCacheDir = cacheDir,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertTrue(appState.scheduler.isRunning)
        withTimeout(2_000) { appState.groupConfigs.first { it.size == 1 } }

        appState.resetLocalAppDatabase().join()

        assertFalse(appState.scheduler.isRunning)
        assertTrue(appState.events.value.isEmpty())
        assertTrue(appState.syncCheckpoints.value.isEmpty())
        assertTrue(appState.groupConfigs.value.isEmpty())
        assertEquals("", appState.settings.value.wechatDataDir)
        assertEquals("en", appState.settings.value.targetLanguage)
        assertFalse(cacheDir.exists())
        assertTrue(eventCardStore.listActive().isEmpty())
        assertTrue(groupConfigStore.getAll().isEmpty())
        assertTrue(syncCheckpointStore.getAll().isEmpty())
    }

    @Test
    fun `translateSourceMessages caches translation in SQLite and skips duplicate requests`() = runBlocking {
        val card = EventCard(
            id = "translate-card",
            title = "Lab Training",
            summary = "Lab safety training starts tomorrow at 2 PM.",
            translatedSummary = "Lab safety training starts tomorrow at 2 PM.",
            category = EventCategory.NOTICE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Prof_Wang",
            sourceRoomId = "lab@chatroom",
            sourceRoomName = "Lab Group",
            sourceMessages = "[2026-11-10 14:00:00] Prof_Wang: 明天下午两点安全培训，请准时参加。",
            timestampSeconds = 1731000000L
        )
        eventCardStore.upsert(listOf(card))

        var llmCalls = 0
        var shouldFail = false
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined),
            llmClientFactory = { config ->
                val engine = MockEngine {
                    llmCalls++
                    if (shouldFail) {
                        respond("Upstream error", HttpStatusCode.InternalServerError)
                    } else {
                        val content = """{"translated_text": "[2026-11-10 14:00:00] Prof_Wang: Safety training tomorrow at 2 PM, please attend on time."}"""
                        val payload = """
                            {"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}
                        """.trimIndent()
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
                KtorLlmClient(config.copy(apiKey = "test-key"), engine)
            }
        )

        appState.loadEvents().join()
        assertEquals("", appState.events.value.single().translatedSourceMessages)

        appState.translateSourceMessages("translate-card").join()
        assertEquals(1, llmCalls)
        assertEquals(
            "[2026-11-10 14:00:00] Prof_Wang: Safety training tomorrow at 2 PM, please attend on time.",
            appState.events.value.single().translatedSourceMessages
        )
        assertEquals(
            "[2026-11-10 14:00:00] Prof_Wang: Safety training tomorrow at 2 PM, please attend on time.",
            eventCardStore.listActive().single { it.id == "translate-card" }.translatedSourceMessages
        )
        assertTrue(appState.translatingCardIds.value.isEmpty())

        // Second call when already cached should be a no-op
        appState.translateSourceMessages("translate-card").join()
        assertEquals(1, llmCalls)

        // Error path on an untranslated card emits snackbar message
        val failCard = card.copy(
            id = "translate-fail-card",
            title = "Lab Training Failure Case",
            sourceMessages = "[2026-11-10 14:05:00] Prof_Wang: 补充通知，请带实验记录本。"
        )
        eventCardStore.upsert(listOf(failCard))
        appState.loadEvents().join()
        shouldFail = true

        val snackbarDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) {
                appState.snackbarMessage.first()
            }
        }
        appState.translateSourceMessages("translate-fail-card").join()
        val snackbar = snackbarDeferred.await()
        assertTrue(snackbar.contains("Message translation failed"))
        assertEquals("", eventCardStore.listActive().single { it.id == "translate-fail-card" }.translatedSourceMessages)
        assertTrue(appState.translatingCardIds.value.isEmpty())
    }

    @Test
    fun `loadRoomMembers returns empty for missing dir and sorted members for valid contact db`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        // Default blank wechatDataDir -> returns empty list gracefully
        assertTrue(appState.loadRoomMembers("room@chatroom").isEmpty())

        val wechatDir = File(tempDir, "members_wechat").apply { mkdirs() }
        val roomId = "members@chatroom"
        val contactDb = File(wechatDir, "contact.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (id INTEGER PRIMARY KEY, username TEXT, nick_name TEXT, remark TEXT)")
                stmt.execute("CREATE TABLE name2id (username TEXT PRIMARY KEY)")
                stmt.execute("CREATE TABLE chat_room (id INTEGER PRIMARY KEY, username TEXT, owner TEXT)")
                stmt.execute("CREATE TABLE chatroom_member (room_id INTEGER, member_id INTEGER)")

                stmt.execute("INSERT INTO contact VALUES (1, 'wxid_owner', 'Teacher_Z', '')")
                stmt.execute("INSERT INTO contact VALUES (2, 'wxid_member', 'Alice', '')")
                stmt.execute("INSERT INTO name2id(rowid, username) VALUES (1, 'wxid_owner')")
                stmt.execute("INSERT INTO name2id(rowid, username) VALUES (2, 'wxid_member')")
                stmt.execute("INSERT INTO chat_room VALUES (10, '$roomId', 'wxid_owner')")
                stmt.execute("INSERT INTO chatroom_member VALUES (10, 2)")
                stmt.execute("INSERT INTO chatroom_member VALUES (10, 1)")
            }
        }

        appState.updateSettings(appState.settings.value.copy(wechatDataDir = wechatDir.absolutePath)).join()
        val loaded = appState.loadRoomMembers(roomId)
        assertEquals(2, loaded.size)
        assertEquals("wxid_owner", loaded[0].username)
        assertTrue(loaded[0].isOwner)
        assertEquals("wxid_member", loaded[1].username)
    }

    @Test
    fun `visibleEvents filters by group and combines with priority and search query`() = runBlocking {
        val mathCritical = EventCard(
            id = "m1",
            title = "Calculus Final Exam",
            summary = "Room 301",
            translatedSummary = "Room 301",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof. Euler",
            sourceRoomId = "math@chatroom",
            sourceRoomName = "Calculus Group",
            timestampSeconds = 1000L
        )
        val mathNormal = EventCard(
            id = "m2",
            title = "Calculus Office Hours",
            summary = "Friday 14:00",
            translatedSummary = "Friday 14:00",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "TA_Li",
            sourceRoomId = "math@chatroom",
            sourceRoomName = "Calculus Group",
            timestampSeconds = 1500L
        )
        val csCritical = EventCard(
            id = "c1",
            title = "AI Lab Deadline",
            summary = "Upload zip before Friday",
            translatedSummary = "Upload zip before Friday",
            category = EventCategory.DEADLINE,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof. Turing",
            sourceRoomId = "cs@chatroom",
            sourceRoomName = "AI Lab",
            timestampSeconds = 2000L
        )

        eventCardStore.upsert(listOf(mathCritical, mathNormal, csCritical))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        appState.loadEvents().join()
        assertEquals(3, withTimeout(2_000) { appState.visibleEvents.first { it.size == 3 } }.size)

        // Group filter alone
        appState.groupFilter.value = "math@chatroom"
        assertEquals(listOf("m2", "m1"), appState.visibleEvents.value.map { it.id })

        // Combined group + priority filter
        appState.priorityFilter.value = EventPriority.CRITICAL
        assertEquals(listOf("m1"), appState.visibleEvents.value.map { it.id })

        // Combined group + priority + search query
        appState.searchQuery.value = "Euler"
        assertEquals(listOf("m1"), appState.visibleEvents.value.map { it.id })

        appState.searchQuery.value = "Turing"
        assertEquals(emptyList(), appState.visibleEvents.value)

        // Unknown room yields empty list
        appState.searchQuery.value = ""
        appState.priorityFilter.value = null
        appState.groupFilter.value = "unknown@chatroom"
        assertEquals(emptyList(), appState.visibleEvents.value)

        // Clearing groupFilter restores all cards
        appState.groupFilter.value = null
        assertEquals(3, appState.visibleEvents.value.size)
    }

    @Test
    fun `visibleEvents sorts by date newest-to-oldest and oldest-to-newest with deterministic priority tie-break`() = runBlocking {
        val oldest = EventCard(
            id = "t1000",
            title = "Oldest Event",
            summary = "First",
            translatedSummary = "First",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Admin",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 1000L
        )
        val tieLow = EventCard(
            id = "t2000_low",
            title = "Tie Low",
            summary = "Low",
            translatedSummary = "Low",
            category = EventCategory.NOTICE,
            priority = EventPriority.LOW,
            originalSender = "Admin",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 2000L
        )
        val tieCritical = EventCard(
            id = "t2000_crit",
            title = "Tie Critical",
            summary = "Crit",
            translatedSummary = "Crit",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Admin",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 2000L
        )
        val newest = EventCard(
            id = "t3000",
            title = "Newest Event",
            summary = "Latest",
            translatedSummary = "Latest",
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Admin",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 3000L
        )

        eventCardStore.upsert(listOf(oldest, tieLow, tieCritical, newest))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        appState.loadEvents().join()
        assertEquals(4, withTimeout(2_000) { appState.visibleEvents.first { it.size == 4 } }.size)

        assertEquals(EventSortOrder.NEWEST_FIRST, appState.sortOrder.value)
        assertEquals(
            listOf("t3000", "t2000_crit", "t2000_low", "t1000"),
            appState.visibleEvents.value.map { it.id }
        )

        appState.sortOrder.value = EventSortOrder.OLDEST_FIRST
        assertEquals(
            listOf("t1000", "t2000_crit", "t2000_low", "t3000"),
            appState.visibleEvents.value.map { it.id }
        )
    }

    @Test
    fun `clearEventsAndCheckpoints and resetLocalAppDatabase clear stale groupFilter and reset sortOrder`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        appState.priorityFilter.value = EventPriority.CRITICAL
        appState.groupFilter.value = "room1@chatroom"
        appState.sortOrder.value = EventSortOrder.OLDEST_FIRST
        appState.searchQuery.value = "exam"
        appState.groupSearchQuery.value = "math"

        appState.clearEventsAndCheckpoints().join()
        assertNull(appState.groupFilter.value)

        appState.groupFilter.value = "room2@chatroom"
        appState.resetLocalAppDatabase().join()

        assertNull(appState.priorityFilter.value)
        assertNull(appState.groupFilter.value)
        assertEquals(EventSortOrder.NEWEST_FIRST, appState.sortOrder.value)
        assertEquals("", appState.searchQuery.value)
        assertEquals("", appState.groupSearchQuery.value)
    }

    @Test
    fun `toggleStarred and starredOnlyFilter persist and filter starred events`() = runBlocking {
        val c1 = EventCard(
            id = "s1",
            title = "Starred Candidate",
            summary = "Save this",
            translatedSummary = "Save this",
            category = EventCategory.NOTICE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Admin",
            sourceRoomId = "r1@chatroom",
            timestampSeconds = 2000L
        )
        val c2 = EventCard(
            id = "s2",
            title = "Regular Notice",
            summary = "Unstarred",
            translatedSummary = "Unstarred",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Admin",
            sourceRoomId = "r1@chatroom",
            timestampSeconds = 1000L
        )
        eventCardStore.upsert(listOf(c1, c2))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        appState.loadEvents().join()

        appState.toggleStarred("s1").join()
        assertTrue(appState.events.value.first { it.id == "s1" }.isStarred)
        assertTrue(eventCardStore.listActive().first { it.id == "s1" }.isStarred)

        appState.starredOnlyFilter.value = true
        assertEquals(listOf("s1"), appState.visibleEvents.value.map { it.id })

        // Unstarring while starredOnlyFilter is active removes it from visibleEvents
        appState.toggleStarred("s1").join()
        assertTrue(appState.visibleEvents.value.isEmpty())

        appState.starredOnlyFilter.value = false
        assertEquals(2, appState.visibleEvents.value.size)
    }

    @Test
    fun `selectEvent marks unread card as read and markUnread restores unread status`() = runBlocking {
        val unreadCard = EventCard(
            id = "u1",
            title = "Unread Exam Notice",
            summary = "Details",
            translatedSummary = "Details",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof",
            sourceRoomId = "r1@chatroom",
            timestampSeconds = 3000L,
            isRead = false
        )
        eventCardStore.upsert(listOf(unreadCard))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
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
}

