package com.wechatdigest.app

import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.OllamaService
import com.wechatdigest.core.llm.local.LlamaRuntimeManager
import com.wechatdigest.core.llm.local.LlamaServerManager
import com.wechatdigest.core.llm.local.LocalModelDownloadManager
import com.wechatdigest.core.llm.local.LocalModelMirror
import com.wechatdigest.core.llm.local.LocalModelPaths
import com.wechatdigest.core.llm.local.LocalModelStore
import com.wechatdigest.core.llm.local.LocalServerState
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
class AppStateTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var appDb: AppDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var eventCardStore: EventCardStore
    private lateinit var groupConfigStore: com.wechatdigest.storage.GroupConfigStore
    private lateinit var syncService: SyncService
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dbFile = File(tempDir, "test_app.db")
        appDb = AppDatabase(dbFile)
        settingsStore = SettingsStore(appDb)
        eventCardStore = EventCardStore(appDb)
        groupConfigStore = com.wechatdigest.storage.GroupConfigStore(appDb)

        val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val llmClient = KtorLlmClient(LlmConfig(), engine = mockEngine)
        syncService = SyncService(eventCardStore, groupConfigStore) { llmClient }
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        // Ensure background init/IO coroutines finish touching SQLite before JUnit deletes @TempDir.
        testScope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test
    fun `navigation and settings toggle work as expected`() = runBlocking {
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope
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
            scope = testScope,
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
            scope = testScope
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
            scope = testScope
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
    fun `built-in local model selection, import, and factory reset keep model files intact`() = runBlocking {
        val localPaths = LocalModelPaths(File(tempDir, "app-support"))
        LocalModelPaths.ensureOwnerOnlyDirectory(localPaths.modelsDir)
        val externalGguf = File(tempDir, "external-local.gguf").apply {
            writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0) + ByteArray(64))
        }
        val localStore = LocalModelStore(localPaths)
        val downloadManager = LocalModelDownloadManager(localStore, engine = MockEngine { respond("", HttpStatusCode.OK) })
        val runtimeManager = LlamaRuntimeManager(localPaths, engine = MockEngine { respond("", HttpStatusCode.OK) })
        val serverManager = LlamaServerManager(runtimeManager, scope = testScope, healthEngine = MockEngine { respond("", HttpStatusCode.OK) })

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            scope = testScope,
            localModelStore = localStore,
            localModelDownloadManager = downloadManager,
            llamaRuntimeManager = runtimeManager,
            llamaServerManager = serverManager
        )

        appState.selectLocalMirror(LocalModelMirror.HF_MIRROR).join()
        assertEquals("HF_MIRROR", appState.settings.value.llm.localModelMirror)

        var importedId: String? = null
        appState.importLocalGgufFile(externalGguf) { importedId = it.id }.join()
        assertTrue(!importedId.isNullOrBlank())
        assertEquals(LlmProviderType.BUILT_IN_LOCAL, appState.settings.value.llm.providerType)
        assertEquals(importedId, appState.settings.value.llm.localModelId)
        assertEquals(1, appState.importedLocalModels.value.size)

        // Reset local DB resets settings to defaults without deleting imported GGUF file on disk.
        appState.resetLocalAppDatabase().join()
        assertEquals(AppSettings(), appState.settings.value)
        assertTrue(externalGguf.exists())
        assertTrue(appState.localServerState.value is LocalServerState.Stopped)

        appState.close()
    }

    @Test
    fun `trust loop quickEdit, reextract, loadContextMessages, and dismiss-to-filter flows work end to end`() = runBlocking {
        val wechatDir = File(tempDir, "trust_wechat").apply { mkdirs() }
        val room = "trust@chatroom"
        val table = com.wechatdigest.core.db.WeChatDatabaseDriver.calculateRoomTableName(room)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(wechatDir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.executeUpdate("INSERT INTO contact VALUES ('wxid_teacher', 'Prof_Lin', '')")
                stmt.executeUpdate("INSERT INTO contact VALUES ('wxid_stu', 'StudentA', '')")
            }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(wechatDir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $table VALUES (1, 1794800000, 'wxid_stu', CAST('Hi earlier' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $table VALUES (2, 1794800100, 'wxid_teacher', CAST('Project presentation moved to Room C404 on Dec 18.' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $table VALUES (3, 1794800200, 'wxid_stu', CAST('Got it thanks!' AS BLOB))")
            }
        }

        val card = EventCard(
            id = "trust-card-1",
            title = "Presentation",
            summary = "Old summary",
            translatedSummary = "Old summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Prof_Lin",
            originalSenderId = "wxid_teacher",
            sourceRoomId = room,
            sourceMessages = "[2026-11-26 10:00:00] Prof_Lin: Project presentation moved to Room C404 on Dec 18.",
            sourceLocalIds = listOf(2L),
            timestampSeconds = 1794800100L,
            isRead = true,
            isStarred = true
        )
        val muteTargetCard = card.copy(
            id = "trust-card-2",
            title = "Noisy Forward",
            originalSender = "StudentA",
            originalSenderId = "wxid_stu",
            timestampSeconds = 1794800300L
        )
        val filterTargetCard = card.copy(
            id = "trust-card-3",
            title = "Campus Promo",
            timestampSeconds = 1794800400L
        )
        eventCardStore.upsert(listOf(card, muteTargetCard, filterTargetCard))

        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            scope = testScope,
            llmClientFactory = { cfg ->
                val engine = MockEngine {
                    val content = """{"is_relevant":true,"events":[{"title":"Presentation Room C404","summary":"Project presentation moved to Room C404 on Dec 18.","translated_summary":"Project presentation moved to Room C404 on Dec 18.","category":"DEADLINE","priority":"IMPORTANT","event_date":"2026-12-18","source_message_ids":[2]}]}"""
                    respond(
                        """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                KtorLlmClient(cfg.copy(apiKey = "test-key"), engine)
            }
        )
        appState.updateSettings(appState.settings.value.copy(wechatDataDir = wechatDir.absolutePath)).join()
        appState.loadEvents().join()

        // Quick edit preserves read/starred
        appState.quickEditCard("trust-card-1", "Edited Title", EventCategory.EXAM, EventPriority.CRITICAL, "2026-12-19").join()
        val edited = appState.events.value.first { it.id == "trust-card-1" }
        assertEquals("Edited Title", edited.title)
        assertEquals(EventCategory.EXAM, edited.category)
        assertTrue(edited.isRead && edited.isStarred)

        // Single-card re-extract hits MockEngine using DB anchor message
        appState.reextractCard("trust-card-1").join()
        val reextracted = appState.events.value.first { it.id == "trust-card-1" }
        assertEquals("Presentation Room C404", reextracted.title)
        assertEquals(EventPriority.IMPORTANT, reextracted.priority)
        assertTrue(reextracted.isStarred)

        // Context jump returns ±5 surrounding messages including casual chatter
        val ctxResult = appState.loadContextMessages(reextracted, beforeCount = 5, afterCount = 5)
        assertTrue(ctxResult.isSuccess)
        assertEquals(listOf("Hi earlier", "Project presentation moved to Room C404 on Dec 18.", "Got it thanks!"), ctxResult.getOrThrow().messages.map { it.textContent })

        // Dismiss & Mute Sender adds wxid_stu to mutedSenderIds
        appState.dismissAndMuteSender(muteTargetCard).join()
        assertTrue(appState.events.value.none { it.id == "trust-card-2" })
        assertEquals(listOf("wxid_stu"), groupConfigStore.get(room)?.mutedSenderIds)

        // Dismiss & Add Prefilter Rule saves new static rule
        appState.dismissAndAddPrefilterRule(filterTargetCard, "Campus Promo", com.wechatdigest.core.sanitizer.PrefilterMatchType.CONTAINS).join()
        assertTrue(appState.events.value.none { it.id == "trust-card-3" })
        assertTrue(appState.settings.value.prefilter.rules.any { it.pattern == "Campus Promo" })

        // Security regression: regex with trailing escaped space fails trim-before-validate without dismissing or saving bad rule
        val rulesCountBefore = appState.settings.value.prefilter.rules.size
        appState.dismissAndAddPrefilterRule(card, "bad\\ ", com.wechatdigest.core.sanitizer.PrefilterMatchType.REGEX).join()
        assertEquals(rulesCountBefore, appState.settings.value.prefilter.rules.size)
        assertTrue(appState.events.value.any { it.id == card.id })

        // Validation guards: invalid quickEdit date and blank title do not alter store
        appState.quickEditCard("trust-card-1", "   ", EventCategory.EXAM, EventPriority.CRITICAL, "2026-12-19").join()
        appState.quickEditCard("trust-card-1", "Valid Title", EventCategory.EXAM, EventPriority.CRITICAL, "TBD").join()
        assertEquals("Presentation Room C404", appState.events.value.first { it.id == "trust-card-1" }.title)

        // Fallback reextractCard when WeChat DB is unavailable + blank originalSenderId mute fallback
        appState.updateSettings(appState.settings.value.copy(wechatDataDir = "")).join()
        val ctxFail = appState.loadContextMessages(card)
        assertTrue(ctxFail.isFailure)

        val priorCard = EventCard(
            id = "prior-id-1",
            title = "Initial Schedule",
            summary = "Initial",
            translatedSummary = "Initial",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Prof",
            sourceRoomId = room,
            sourceMessages = "[2026-11-25 10:00:00] Prof: Initial presentation notice.",
            timestampSeconds = 1794700000L
        )
        val amendedCard = EventCard(
            id = "fallback-reextract-card",
            title = "Legacy Card",
            summary = "Old",
            translatedSummary = "Old",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Prof",
            originalSenderId = "",
            sourceRoomId = room,
            sourceMessages = "[2026-11-26 10:00:00] Prof: Project presentation moved to Room C404 on Dec 18.",
            timestampSeconds = 1794800500L,
            status = com.wechatdigest.core.model.EventCardStatus.UPDATED,
            supersedesCardId = priorCard.id
        )
        eventCardStore.upsert(listOf(priorCard, amendedCard))
        appState.loadEvents().join()
        appState.reextractCard(amendedCard.id).join()
        val postFallback = appState.events.value.first { it.id == amendedCard.id }
        assertEquals("Presentation Room C404", postFallback.title)
        assertEquals(com.wechatdigest.core.model.EventCardStatus.UPDATED, postFallback.status, "Single-card re-extract must preserve amendment status")
        assertEquals("prior-id-1", postFallback.supersedesCardId)

        appState.dismissAndMuteSender(postFallback).join()
        assertTrue(appState.events.value.none { it.id == amendedCard.id })

        appState.close()
    }

    @Test
    fun `Phase 4 triage filters, full-corpus search, completion toggles, test probes, and sync cancellation work end to end`() = runBlocking {
        val syncCheckpointStore = com.wechatdigest.storage.SyncCheckpointStore(appDb)
        val syncService = SyncService(
            eventCardStore = eventCardStore,
            syncCheckpointStore = syncCheckpointStore,
            llmClientFactory = { cfg ->
                val slowEngine = MockEngine {
                    kotlinx.coroutines.delay(5_000)
                    respond(
                        """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                KtorLlmClient(cfg, slowEngine)
            }
        )

        var notifyCalls = 0
        val dispatcher = com.wechatdigest.core.notification.NotificationDispatcher(commandRunner = { notifyCalls++ })
        var llmCalls = 0
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            syncCheckpointStore = syncCheckpointStore,
            notificationDispatcher = dispatcher,
            scope = testScope,
            llmClientFactory = { cfg ->
                val engine = MockEngine {
                    llmCalls++
                    respond(
                        """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                KtorLlmClient(cfg.copy(apiKey = "test-key"), engine)
            }
        )

        val soonCard = EventCard(
            id = "p4-soon",
            title = "Scholarship Essay",
            summary = "Submit personal statement",
            translatedSummary = "Submit personal statement",
            category = EventCategory.DEADLINE,
            priority = EventPriority.CRITICAL,
            eventDate = "2026-11-28",
            originalSender = "Advisor",
            sourceRoomId = "r1@chatroom",
            sourceRoomName = "Fellowships",
            sourceMessages = "[2026-11-26] Advisor: UniquePhraseInSourceMsg",
            timestampSeconds = 100L,
            attachments = listOf(
                com.wechatdigest.core.model.EventAttachment(
                    fileName = "Fellowship_Form_2026.pdf",
                    fileExtension = "pdf",
                    fileSizeBytes = 1024L
                )
            )
        )
        val laterCard = soonCard.copy(
            id = "p4-later",
            title = "Club Fair",
            summary = "Join student clubs",
            translatedSummary = "Join student clubs",
            category = EventCategory.ACTIVITY,
            priority = EventPriority.NORMAL,
            eventDate = "2026-12-20",
            sourceRoomName = "Campus Life",
            sourceMessages = "[2026-11-26] Lead: Welcome everyone",
            timestampSeconds = 200L,
            attachments = emptyList()
        )
        eventCardStore.upsert(listOf(soonCard, laterCard))
        appState.loadEvents().join()

        // Sort DEADLINE_SOONEST orders 2026-11-28 before 2026-12-20 even though laterCard has newer timestampSeconds
        appState.sortOrder.value = EventSortOrder.DEADLINE_SOONEST
        withTimeout(2_000) { appState.visibleEvents.first { it.map { c -> c.id } == listOf("p4-soon", "p4-later") } }

        // Category filter
        appState.categoryFilter.value = EventCategory.ACTIVITY
        withTimeout(2_000) { appState.visibleEvents.first { it.map { c -> c.id } == listOf("p4-later") } }
        appState.categoryFilter.value = null

        // Full-corpus search matches sourceMessages and attachment fileName
        appState.searchQuery.value = "UniquePhraseInSourceMsg"
        withTimeout(2_000) { appState.visibleEvents.first { it.map { c -> c.id } == listOf("p4-soon") } }
        appState.searchQuery.value = "Fellowship_Form_2026"
        withTimeout(2_000) { appState.visibleEvents.first { it.map { c -> c.id } == listOf("p4-soon") } }
        appState.searchQuery.value = ""

        // Toggle completed & hideCompletedFilter
        appState.toggleCompleted("p4-soon").join()
        assertTrue(appState.events.value.first { it.id == "p4-soon" }.isCompleted)
        assertTrue(eventCardStore.listActive().first { it.id == "p4-soon" }.isCompleted)
        appState.hideCompletedFilter.value = true
        withTimeout(2_000) { appState.visibleEvents.first { it.map { c -> c.id } == listOf("p4-later") } }
        appState.hideCompletedFilter.value = false

        // Toggle checklist item persists when sections allow or legacy empty; invalid key against structured section is ignored
        appState.toggleCheckedItem("p4-soon", "0:0:999").join()
        assertEquals(listOf("0:0:999"), appState.events.value.first { it.id == "p4-soon" }.checkedItems)
        appState.toggleCheckedItem("p4-later", "invalid:key").join()

        // Test connection & test notification probes
        appState.testLlmConnection().join()
        assertEquals(1, llmCalls)
        assertTrue(appState.llmConnectionStatusMessage.value?.contains("Connected") == true)

        // Built-in local connection probe is blocked cleanly while sync is active
        val roomDir = File(tempDir, "cancel_wechat").apply { mkdirs() }
        val cancelTable = com.wechatdigest.core.db.WeChatDatabaseDriver.calculateRoomTableName("r1@chatroom")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(roomDir, "contact.db").absolutePath}").use { c ->
            c.createStatement().execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(roomDir, "message_0.db").absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE $cancelTable (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                s.execute("INSERT INTO $cancelTable VALUES (1, 1794800000, 'u1', CAST('Urgent deadline tomorrow morning in Room 101.' AS BLOB))")
            }
        }
        appState.updateSettings(appState.settings.value.copy(wechatDataDir = roomDir.absolutePath, monitoredRoomIds = setOf("r1@chatroom"))).join()
        val syncJob = appState.triggerSync()
        withTimeout(2_000) { appState.isSyncing.first { it } }
        appState.testLlmConnection(
            appState.settings.value.llm.copy(providerType = com.wechatdigest.core.llm.LlmProviderType.BUILT_IN_LOCAL)
        ).join()
        assertTrue(appState.llmConnectionStatusMessage.value?.contains("Wait for active sync") == true)
        appState.cancelSync().join()
        syncJob.join()
        assertFalse(appState.isSyncing.value)

        appState.testNotification().join()
        assertEquals(1, notifyCalls)

        // Secret redaction regression in LlmJsonUtils
        val rawErr = "HTTP 401 Basic dXNlcjpwYXNz with AIzaSyDummyKey1234567890123 and gsk_secret_token_999 and x-api-key: secret_val_42"
        val redacted = com.wechatdigest.core.llm.LlmJsonUtils.sanitizeLogMessage(rawErr)
        assertFalse(redacted.contains("dXNlcjpwYXNz"))
        assertFalse(redacted.contains("AIzaSyDummyKey"))
        assertFalse(redacted.contains("gsk_secret"))
        assertFalse(redacted.contains("secret_val_42"))

        appState.close()
    }

    @Test
    fun `AppState key verification and guided extraction persist keysPath and update diagnostics`() = runBlocking {
        val dir = File(tempDir, "key_flow_wechat").apply { mkdirs() }
        val contactDir = File(dir, "contact").apply { mkdirs() }
        val encKey = ByteArray(32) { (it + 5).toByte() }
        val salt = ByteArray(16) { (it + 11).toByte() }

        val page1 = ByteArray(com.wechatdigest.core.db.WcdbDecryptor.PAGE_SIZE)
        System.arraycopy(salt, 0, page1, 0, 16)
        val macSalt = ByteArray(16) { idx -> (salt[idx].toInt() xor 0x3A).toByte() }
        val macKey = com.wechatdigest.core.db.WcdbDecryptor.pbkdf2HmacSha512(encKey, macSalt, 2, 32)
        val mac = javax.crypto.Mac.getInstance("HmacSHA512").apply {
            init(javax.crypto.spec.SecretKeySpec(macKey, "HmacSHA512"))
        }
        mac.update(
            page1,
            com.wechatdigest.core.db.WcdbDecryptor.SALT_SIZE,
            com.wechatdigest.core.db.WcdbDecryptor.PAGE_SIZE -
                com.wechatdigest.core.db.WcdbDecryptor.SALT_SIZE -
                com.wechatdigest.core.db.WcdbDecryptor.RESERVE_SIZE +
                com.wechatdigest.core.db.WcdbDecryptor.IV_SIZE
        )
        mac.update(byteArrayOf(1, 0, 0, 0))
        System.arraycopy(mac.doFinal(), 0, page1, com.wechatdigest.core.db.WcdbDecryptor.PAGE_SIZE - 64, 64)
        File(contactDir, "contact.db").writeBytes(page1)

        val outKeysFile = File(tempDir, "app_keys/all_keys.json")
        val mockKeyManager = com.wechatdigest.core.db.WeChatKeyExtractionManager(
            binDir = File(tempDir, "app_bin"),
            defaultKeysFile = outKeysFile,
            defaultPassphraseFile = File(tempDir, "app_keys/passphrase.json"),
            isMacOs = { true },
            wechatAppLocator = { File(tempDir, "WeChat.app").apply { mkdirs() } },
            executableExists = { true },
            commandRunner = { cmd, _ ->
                if (cmd.firstOrNull()?.contains("xcode-select") == true) {
                    com.wechatdigest.core.db.CommandExecResult(0, "/Library/Developer/CommandLineTools\n", "")
                } else {
                    com.wechatdigest.core.db.CommandExecResult(0, "", "")
                }
            },
            uidGidProvider = { 501 to 20 },
            elevationRunner = { shellCmd, _ ->
                val logPath = Regex("""--json-events > '([^']+)'""").find(shellCmd)!!.groupValues[1]
                File(logPath).writeText("""{"event":"complete","verifiedCount":1,"totalCount":1}""" + "\n")
                outKeysFile.parentFile.mkdirs()
                outKeysFile.writeText(
                    """{"contact/contact.db":{"enc_key":"${encKey.joinToString("") { "%02x".format(it) }}"}}"""
                )
                com.wechatdigest.core.db.CommandExecResult(0, "", "")
            }
        )

        val syncService = SyncService(
            eventCardStore = eventCardStore,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = com.wechatdigest.storage.SyncCheckpointStore(appDb)
        )
        val appState = AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            scope = testScope,
            keyExtractionManager = mockKeyManager
        )
        appState.updateSettings(AppSettings(wechatDataDir = dir.absolutePath)).join()

        appState.refreshKeyDiagnostics().join()
        assertFalse(appState.keyPreflightStatus.value?.isReadyToSync ?: true)

        var callbackPath: String? = null
        appState.startGuidedKeyExtraction(resignWeChat = false, onKeysExtracted = { callbackPath = it }).join()

        assertTrue(appState.keyExtractionState.value is com.wechatdigest.core.db.KeyExtractionState.Succeeded)
        assertEquals(outKeysFile.canonicalPath, appState.settings.value.keysPath)
        assertEquals(outKeysFile.canonicalPath, callbackPath)
        assertTrue(appState.keyPreflightStatus.value?.isReadyToSync == true)

        // verifyWeChatKeysNow with empty-string override still auto-saves resolved canonical keysPath
        appState.updateSettings(AppSettings(wechatDataDir = dir.absolutePath, keysPath = "")).join()
        appState.verifyWeChatKeysNow(overrideDataDir = dir.absolutePath, overrideKeysPath = "").join()
        assertEquals(outKeysFile.canonicalPath, appState.settings.value.keysPath)

        // buildManualKeyExtractCommand runs off-EDT and respects custom keysPathOverride
        val customManualOut = File(tempDir, "custom_keys.json")
        val manualCmd = kotlin.test.assertNotNull(
            appState.buildManualKeyExtractCommand(dir.absolutePath, customManualOut.absolutePath)
        )
        assertTrue(manualCmd.contains("/usr/bin/python3 -I "))
        assertTrue(manualCmd.contains("--output '${customManualOut.canonicalPath}'"))

        // cancelKeyExtraction cancels safely without throwing and lets a follow-up run succeed
        appState.cancelKeyExtraction()
        assertEquals(com.wechatdigest.core.db.KeyExtractionState.Cancelled, appState.keyExtractionState.value)
        appState.startGuidedKeyExtraction(resignWeChat = false).join()
        assertTrue(appState.keyExtractionState.value is com.wechatdigest.core.db.KeyExtractionState.Succeeded)

        appState.close()
    }
}

