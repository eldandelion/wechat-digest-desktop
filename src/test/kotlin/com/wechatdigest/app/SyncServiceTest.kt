package com.wechatdigest.app

import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import com.wechatdigest.storage.AppDatabase
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.SyncCheckpointStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncServiceTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var appDb: AppDatabase
    private lateinit var eventCardStore: EventCardStore

    @BeforeEach
    fun setUp() {
        val dbFile = File(tempDir, "test_app.db")
        appDb = AppDatabase(dbFile)
        eventCardStore = EventCardStore(appDb)
    }

    @Test
    fun `syncNow fails when wechatDataDir does not exist`() = runBlocking {
        val syncService = SyncService(eventCardStore)
        val settings = AppSettings(
            wechatDataDir = "/path/to/nonexistent/dir",
            monitoredRoomIds = setOf("test@chatroom")
        )

        val result = syncService.syncNow(settings)
        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).message.contains("directory does not exist", ignoreCase = true))
    }

    @Test
    fun `syncNow fails when wechatDataDir is blank`() = runBlocking {
        val syncService = SyncService(eventCardStore)
        val settings = AppSettings(
            wechatDataDir = "",
            monitoredRoomIds = setOf("test@chatroom")
        )

        val result = syncService.syncNow(settings)
        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).message.contains("not configured", ignoreCase = true))
    }

    @Test
    fun `syncNow fails when no monitored rooms are configured`() = runBlocking {
        val syncService = SyncService(eventCardStore)
        val settings = AppSettings(
            wechatDataDir = tempDir.absolutePath,
            monitoredRoomIds = emptySet()
        )

        val result = syncService.syncNow(settings)
        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).message.contains("No chatrooms selected", ignoreCase = true))
    }

    @Test
    fun `syncNow handles room failure when database shard cannot be found`() = runBlocking {
        val syncService = SyncService(eventCardStore)
        val settings = AppSettings(
            wechatDataDir = tempDir.absolutePath,
            monitoredRoomIds = setOf("nonexistent@chatroom")
        )

        val result = syncService.syncNow(settings)
        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).message.contains("nonexistent@chatroom"))
    }

    @Test
    fun `syncNow runs end-to-end with decrypted db and mock LLM client`() = runBlocking {
        val targetRoomId = "10000000001@chatroom"
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(targetRoomId)
        val decryptedDir = File(tempDir, "synthetic_decrypted").apply { mkdirs() }

        val contactDb = File(decryptedDir, "contact.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$targetRoomId', 'International Student Notices', '')")
                stmt.execute("INSERT INTO contact VALUES ('wxid_office', 'Advisor_Chen', '')")
            }
        }
        val messageDb = File(decryptedDir, "message_0.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${messageDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $tableName (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.execute("INSERT INTO $tableName VALUES (101, 1794800000, 'wxid_office', 'Students can pick up passports at the office tomorrow.')")
            }
        }

        val mockEngine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            val contentJson = when {
                body.contains("Semantic Filter & Topic Chunker") -> {
                    val firstId = Regex("""\[id:(\d+)]""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 1L
                    """{"chunks":[{"topic":"Passport Collection Notice","message_ids":[$firstId],"category":"NOTICE"}]}"""
                }
                body.contains("Rule & Substance Gatekeeper") ->
                    """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Valid notice"}"""
                body.contains("Structured Event Card Translator") ->
                    """{"title":"Passport Collection Notice","summary":"Passport collection notice: students can collect passports at the office tomorrow.","sections":[]}"""
                else ->
                    """{"is_relevant":true,"events":[{"title":"Passport Collection Notice","summary":"Students can pick up passports tomorrow.","translated_summary":"Passport collection notice: students can collect passports tomorrow.","category":"NOTICE","priority":"CRITICAL","event_date":"2026-11-12","source_message_ids":[]}]}"""
            }
            val mockLlmJson = """
                {
                  "id": "chatcmpl-mock",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), contentJson)}
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()
            respond(
                content = mockLlmJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val groupConfigStore = com.wechatdigest.storage.GroupConfigStore(appDb)
        val syncCheckpointStore = SyncCheckpointStore(appDb)
        groupConfigStore.save(
            com.wechatdigest.core.model.GroupConfig(
                roomId = targetRoomId,
                customPrompt = "Focus on passport collection notices only",
                customPersona = "International exchange student"
            )
        )

        val llmCallCount = java.util.concurrent.atomic.AtomicInteger(0)
        val syncService = SyncService(
            eventCardStore = eventCardStore,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = syncCheckpointStore,
            llmClientFactory = {
                val perSyncClient = KtorLlmClient(
                    config = LlmConfig(
                        providerType = LlmProviderType.OPENAI_COMPATIBLE,
                        apiKey = "test-key"
                    ),
                    engine = mockEngine
                )
                object : LlmClient {
                    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String {
                        llmCallCount.incrementAndGet()
                        return perSyncClient.chatCompletion(systemPrompt, userPrompt)
                    }
                    override fun close() {
                        perSyncClient.close()
                    }
                }
            }
        )

        val settings = AppSettings(
            wechatDataDir = decryptedDir.absolutePath,
            monitoredRoomIds = setOf(targetRoomId),
            messagesPerSync = 20
        )

        // 1. First sync: seeds checkpoint and calls LLM (Pass 1 triage + Pass 2 gatekeeper + Pass 3 extraction)
        val result1 = syncService.syncNow(settings)
        assertTrue(result1 is SyncResult.Success)
        val success1 = result1 as SyncResult.Success
        assertTrue(success1.totalScannedMessages > 0)
        assertTrue(success1.newEventsCount >= 1)
        val callsAfterFirstSync = llmCallCount.get()
        assertTrue(callsAfterFirstSync >= 1, "Expected at least 1 LLM call on initial sync")

        val checkpointAfterFirst = syncCheckpointStore.get(targetRoomId)
        kotlin.test.assertNotNull(checkpointAfterFirst)
        assertTrue(checkpointAfterFirst.lastLocalId > 0)

        // 2. Second sync: zero new messages -> skips LLM completely!
        val result2 = syncService.syncNow(settings)
        assertTrue(result2 is SyncResult.Success)
        val success2 = result2 as SyncResult.Success
        assertEquals(0, success2.totalScannedMessages)
        assertEquals(0, success2.newEventsCount)
        assertEquals(callsAfterFirstSync, llmCallCount.get(), "LLM must NOT be called when no new messages exist")

        // 3. Reset checkpoint -> third sync re-scans messages without duplicating existing card
        syncCheckpointStore.reset(targetRoomId)
        val result3 = syncService.syncNow(settings)
        assertTrue(result3 is SyncResult.Success)
        val success3 = result3 as SyncResult.Success
        assertTrue(success3.totalScannedMessages > 0)
        assertEquals(0, success3.newEventsCount, "Existing card must be deduplicated cleanly on rescan")

        val activeCards = eventCardStore.listActive()
        assertTrue(activeCards.isNotEmpty())
        assertEquals("Passport Collection Notice", activeCards[0].title)
        assertEquals(targetRoomId, activeCards[0].sourceRoomId)
    }

    @Test
    fun `syncNow skips checkpoint advance on room LLM failure and picks newest message shard`() = runBlocking {
        val wechatDir = File(tempDir, "mock_wechat").apply { mkdirs() }
        val messageDir = File(wechatDir, "message").apply { mkdirs() }

        val room1 = "room1@chatroom"
        val room2 = "room2@chatroom"
        val table1 = WeChatDatabaseDriver.calculateRoomTableName(room1)
        val table2 = WeChatDatabaseDriver.calculateRoomTableName(room2)

        // Create contact.db
        val contactDb = File(wechatDir, "contact.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$room1', 'Group One', '')")
                stmt.execute("INSERT INTO contact VALUES ('$room2', 'Group Two', '')")
                stmt.execute("INSERT INTO contact VALUES ('wxid_sender', 'Prof_Lin', '')")
            }
        }

        // Create older message_0.db containing both tables (with stale data for room2)
        val shard0 = File(messageDir, "message_0.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${shard0.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table1 (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.execute("INSERT INTO $table1 VALUES (10, 1794800000, 'wxid_sender', 'Midterm exam scheduled for Nov 20 in Room 201.')")
                stmt.execute("CREATE TABLE $table2 (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.execute("INSERT INTO $table2 VALUES (1, 1794000000, 'wxid_sender', 'Old archive notice in shard 0.')")
            }
        }

        // Create newer message_4.db containing room2 with fresh message
        val shard4 = File(messageDir, "message_4.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${shard4.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table2 (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.execute("INSERT INTO $table2 VALUES (25, 1794900000, 'wxid_sender', 'Final thesis defense registration closes Friday.')")
            }
        }

        // Verify WeChatMessageRepository picks newest shard (message_4.db) for room2
        val driver = WeChatDatabaseDriver(wechatDir)
        val msgRepo = WeChatMessageRepository(driver, WeChatContactRepository(driver))
        assertEquals("message_4.db", msgRepo.findDatabaseShardForRoom(room2)?.name)
        val recentRoom2 = msgRepo.getRecentMessages(room2, limit = 10)
        assertEquals(1, recentRoom2.size)
        assertTrue(recentRoom2.single().textContent.contains("Final thesis defense"))

        // Sync 1: Room 1 succeeds, Room 2 fails (HTTP 500) -> checkpoint saved ONLY for Room 1
        var failRoom2 = true
        val syncCheckpointStore = SyncCheckpointStore(appDb)
        val syncService = SyncService(
            eventCardStore = eventCardStore,
            syncCheckpointStore = syncCheckpointStore,
            llmClientFactory = { config ->
                val engine = MockEngine { request ->
                    val body = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
                    if (failRoom2 && body.contains("thesis defense")) {
                        respond("Timeout / error", HttpStatusCode.InternalServerError)
                    } else {
                        val content = when {
                            body.contains("Semantic Filter & Topic Chunker") && body.contains("Midterm exam") ->
                                """{"chunks":[{"topic":"Midterm Exam Room 201","message_ids":[10],"category":"EXAM"}]}"""
                            body.contains("Semantic Filter & Topic Chunker") ->
                                """{"chunks":[{"topic":"Thesis Defense Registration","message_ids":[25],"category":"DEADLINE"}]}"""
                            body.contains("Rule & Substance Gatekeeper") ->
                                """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Valid exam/deadline notice"}"""
                            body.contains("Midterm exam") ->
                                """{"is_relevant":true,"events":[{"title":"Midterm Exam Room 201","summary":"Midterm on Nov 20","translated_summary":"Midterm on Nov 20 in Room 201.","category":"EXAM","priority":"IMPORTANT","source_message_ids":[10]}]}"""
                            else ->
                                """{"is_relevant":true,"events":[{"title":"Thesis Defense Registration","summary":"Register by Friday","translated_summary":"Register for thesis defense by Friday.","category":"DEADLINE","priority":"CRITICAL","source_message_ids":[25]}]}"""
                        }
                        val payload = """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}"""
                        respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                KtorLlmClient(config.copy(apiKey = "test-key"), engine)
            }
        )

        val settings = AppSettings(
            wechatDataDir = wechatDir.absolutePath,
            monitoredRoomIds = linkedSetOf(room1, room2),
            messagesPerSync = 50
        )

        val res1 = syncService.syncNow(settings)
        assertTrue(res1 is SyncResult.Success)
        val success1 = res1 as SyncResult.Success
        assertEquals(1, success1.newEventsCount)
        assertEquals(1, success1.roomErrors.size)
        assertTrue(success1.roomErrors.single().contains(room2))
        assertEquals(10L, syncCheckpointStore.get(room1)?.lastLocalId)
        kotlin.test.assertNull(syncCheckpointStore.get(room2), "Failed room must NOT advance checkpoint")

        // Sync 2: LLM recovers -> Room 2 retries cleanly and saves card + checkpoint
        failRoom2 = false
        val res2 = syncService.syncNow(settings)
        assertTrue(res2 is SyncResult.Success)
        val success2 = res2 as SyncResult.Success
        assertTrue(success2.roomErrors.isEmpty())
        assertEquals(1, success2.newEventsCount)
        assertEquals(25L, syncCheckpointStore.get(room2)?.lastLocalId)

        val cards = eventCardStore.listActive()
        assertEquals(setOf(room1, room2), cards.map { it.sourceRoomId }.toSet())
    }

    @Test
    fun `listRoomMembers, Name2Id resolution, and allowedSenderIds deterministically block non-selected senders`() = runBlocking {
        val wechatDir = File(tempDir, "allowlist_wechat").apply { mkdirs() }
        val messageDir = File(wechatDir, "message").apply { mkdirs() }
        val room = "cse_allow@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)

        // contact.db with chat_room, chatroom_member, name2id, and contact tables
        val contactDb = File(wechatDir, "contact.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (id INTEGER PRIMARY KEY, username TEXT, nick_name TEXT, remark TEXT)")
                stmt.execute("CREATE TABLE name2id (username TEXT PRIMARY KEY)")
                stmt.execute("CREATE TABLE chat_room (id INTEGER PRIMARY KEY, username TEXT, owner TEXT)")
                stmt.execute("CREATE TABLE chatroom_member (room_id INTEGER, member_id INTEGER)")

                stmt.execute("INSERT INTO contact VALUES (1, '$room', 'CSE Group', '')")
                stmt.execute("INSERT INTO contact VALUES (2, 'wxid_teacher', 'Prof_Lin', '')")
                stmt.execute("INSERT INTO contact VALUES (3, 'wxid_student', 'High_Student', '')")

                stmt.execute("INSERT INTO name2id(rowid, username) VALUES (1, '$room')")
                stmt.execute("INSERT INTO name2id(rowid, username) VALUES (2, 'wxid_teacher')")
                stmt.execute("INSERT INTO name2id(rowid, username) VALUES (3, 'wxid_student')")

                stmt.execute("INSERT INTO chat_room VALUES (1, '$room', 'wxid_teacher')")
                stmt.execute("INSERT INTO chatroom_member VALUES (1, 2)")
                stmt.execute("INSERT INTO chatroom_member VALUES (1, 3)")
            }
        }

        // message_0.db with Name2Id lookup (numeric real_sender_id without wxid_:\n prefix)
        val shard = File(messageDir, "message_0.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${shard.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE Name2Id (user_name TEXT PRIMARY KEY, is_session INTEGER)")
                stmt.execute("INSERT INTO Name2Id(rowid, user_name) VALUES (1, 'wxid_teacher')")
                stmt.execute("INSERT INTO Name2Id(rowid, user_name) VALUES (2, 'wxid_student')")

                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.execute("INSERT INTO $table VALUES (10, 1794800000, '1', 'Notice:\nThesis defense slides must be uploaded by Friday 17:00.')")
                stmt.execute("INSERT INTO $table VALUES (20, 1794800100, '2', 'wxid_teacher:\nStudent personal message attempting sender spoof!')")
            }
        }

        val driver = WeChatDatabaseDriver(wechatDir)
        val contactRepo = WeChatContactRepository(driver)
        val members = contactRepo.listRoomMembers(room)
        assertEquals(2, members.size)
        assertEquals("wxid_teacher", members[0].username)
        assertTrue(members[0].isOwner, "Owner must sort first")
        assertEquals("Prof_Lin", members[0].displayName)
        assertEquals("wxid_student", members[1].username)

        val msgRepo = WeChatMessageRepository(driver, contactRepo)
        val loadedMsgs = msgRepo.getRecentMessages(room, limit = 10)
        assertEquals(listOf("wxid_teacher", "wxid_student"), loadedMsgs.map { it.senderId })
        assertEquals(listOf("Prof_Lin", "High_Student"), loadedMsgs.map { it.senderDisplayName })

        val groupConfigStore = com.wechatdigest.storage.GroupConfigStore(appDb)
        groupConfigStore.save(
            com.wechatdigest.core.model.GroupConfig(
                roomId = room,
                allowedSenderIds = listOf("wxid_teacher")
            )
        )
        val syncCheckpointStore = SyncCheckpointStore(appDb)
        val capturedLlmBodies = mutableListOf<String>()
        val syncService = SyncService(
            eventCardStore = eventCardStore,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = syncCheckpointStore,
            llmClientFactory = { config ->
                val engine = MockEngine { request ->
                    val body = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
                    capturedLlmBodies.add(body)
                    val content = when {
                        body.contains("Semantic Filter & Topic Chunker") ->
                            """{"chunks":[{"topic":"Thesis Defense Slides","message_ids":[10],"category":"DEADLINE"}]}"""
                        body.contains("Rule & Substance Gatekeeper") ->
                            """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Teacher notice"}"""
                        else ->
                            """{"is_relevant":true,"events":[{"title":"Thesis Defense Slides Deadline","summary":"Upload defense slides by Friday 17:00.","translated_summary":"Upload thesis defense slides by Friday 17:00.","category":"DEADLINE","priority":"IMPORTANT","source_message_ids":[10]}]}"""
                    }
                    val payload = """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}"""
                    respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                KtorLlmClient(config.copy(apiKey = "test-key"), engine)
            }
        )

        val res = syncService.syncNow(
            AppSettings(
                wechatDataDir = wechatDir.absolutePath,
                monitoredRoomIds = setOf(room),
                messagesPerSync = 20
            )
        )
        assertTrue(res is SyncResult.Success)
        assertEquals(1, (res as SyncResult.Success).newEventsCount)
        assertTrue(capturedLlmBodies.none { it.contains("attempting sender spoof") }, "Non-allowed spoofed sender must never reach LLM")
        assertTrue(
            capturedLlmBodies.any { it.contains("Notice:\\nThesis defense slides") || it.contains("Thesis defense slides must be uploaded") },
            "Teacher message with 'Notice:\\n' must NOT be dropped",
        )
        assertTrue(
            eventCardStore.listActive().single { it.sourceRoomId == room }.sourceMessages.contains("Notice:\nThesis defense slides"),
            "Saved event card must preserve teacher notice text",
        )
        assertEquals(20L, syncCheckpointStore.get(room)?.lastLocalId, "Checkpoint must still advance past skipped student messages")
    }

    @Test
    fun `listRoomMembers handles fallback schemas and syncNow fails closed on corrupt config or skips LLM when allowlist drops all`() = runBlocking {
        // 1. Missing chat_room/chatroom_member -> emptyList cleanly
        val noChatroomDir = File(tempDir, "legacy_wechat").apply { mkdirs() }
        val legacyContactDb = File(noChatroomDir, "contact.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${legacyContactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
            }
        }
        val legacyDriver = WeChatDatabaseDriver(noChatroomDir)
        assertTrue(WeChatContactRepository(legacyDriver).listRoomMembers("r@chatroom").isEmpty())
        assertTrue(WeChatContactRepository(legacyDriver).listRoomMembers("   ").isEmpty())

        // 2. chat_room + chatroom_member without name2id & contact without id column (rowid fallback + whitespace remark + stranger)
        val fallbackDir = File(tempDir, "fallback_members").apply { mkdirs() }
        val fallbackDb = File(fallbackDir, "contact.db")
        val room = "fallback@chatroom"
        java.sql.DriverManager.getConnection("jdbc:sqlite:${fallbackDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("CREATE TABLE stranger (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("CREATE TABLE chat_room (id INTEGER PRIMARY KEY, username TEXT, owner TEXT)")
                stmt.execute("CREATE TABLE chatroom_member (room_id INTEGER, member_id INTEGER)")

                stmt.execute("INSERT INTO contact(rowid, username, nick_name, remark) VALUES (5, 'wxid_ws', 'NickWS', '   ')")
                stmt.execute("INSERT INTO stranger VALUES ('wxid_ws', 'StrangerNick', '')")
                stmt.execute("INSERT INTO chat_room VALUES (99, '$room', 'wxid_ws')")
                stmt.execute("INSERT INTO chatroom_member VALUES (99, 5)")
            }
        }
        val fallbackMembers = WeChatContactRepository(WeChatDatabaseDriver(fallbackDir)).listRoomMembers(room)
        assertEquals(1, fallbackMembers.size)
        assertEquals("NickWS", fallbackMembers.single().displayName, "Whitespace remark must fall back to nick_name")
        assertTrue(fallbackMembers.single().isOwner)

        // 3. SyncService skips LLM and still advances checkpoint when allowlist filters out every message
        val msgDir = File(fallbackDir, "message").apply { mkdirs() }
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(msgDir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.execute("INSERT INTO $table VALUES (42, 1794800000, 'wxid_other', 'Long student chatter message ignored by allowlist')")
            }
        }
        val groupConfigStore = com.wechatdigest.storage.GroupConfigStore(appDb)
        groupConfigStore.save(com.wechatdigest.core.model.GroupConfig(roomId = room, allowedSenderIds = listOf("wxid_ws")))
        val checkpointStore = SyncCheckpointStore(appDb)
        var llmCalls = 0
        val syncService = SyncService(
            eventCardStore = eventCardStore,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = checkpointStore,
            llmClientFactory = { config ->
                object : LlmClient {
                    override suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String {
                        llmCalls++
                        return "{}"
                    }
                    override fun close() {}
                }
            }
        )
        val skipResult = syncService.syncNow(AppSettings(wechatDataDir = fallbackDir.absolutePath, monitoredRoomIds = setOf(room)))
        assertTrue(skipResult is SyncResult.Success)
        assertEquals(0, llmCalls, "LLM must not run when allowlist drops every message")
        assertEquals(42L, checkpointStore.get(room)?.lastLocalId, "Checkpoint must still advance")

        // 4. Corrupt allowed_sender_ids JSON in group_configs fails closed without calling LLM or advancing checkpoint
        checkpointStore.reset(room)
        appDb.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("UPDATE group_configs SET allowed_sender_ids = 'CORRUPT_JSON' WHERE room_id = '$room'")
            }
        }
        val corruptResult = syncService.syncNow(AppSettings(wechatDataDir = fallbackDir.absolutePath, monitoredRoomIds = setOf(room)))
        assertTrue(corruptResult is SyncResult.Success)
        val corruptSuccess = corruptResult as SyncResult.Success
        assertEquals(0, corruptSuccess.newEventsCount)
        assertEquals(1, corruptSuccess.roomErrors.size)
        assertTrue(corruptSuccess.roomErrors.single().contains(room))
        assertEquals(0, llmCalls, "Corrupt config must fail closed before calling LLM")
        kotlin.test.assertNull(checkpointStore.get(room), "Corrupt config must not advance checkpoint")
    }

    @Test
    fun `syncNow throttles Ollama sequentially and skips checkpoint on CardTranslator failure`() = runBlocking {
        val dir = File(tempDir, "ollama_wechat").apply { mkdirs() }
        val room = "ollama@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$room', 'Ollama Group', '')")
                stmt.execute("INSERT INTO contact VALUES ('teacher', 'Prof_Lin', '')")
            }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $table VALUES (1, 1794800000, 'teacher', CAST('提交论文通知：本周五前提交。' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $table VALUES (2, 1794800010, 'teacher', CAST('学术报告通知：周五下午三点。' AS BLOB))")
            }
        }

        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val engine = MockEngine { req ->
            val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            when {
                body.contains("Semantic Filter & Topic Chunker") -> {
                    val content = """{"chunks":[{"topic":"N1","message_ids":[1]},{"topic":"N2","message_ids":[2]}]}"""
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                body.contains("Rule & Substance Gatekeeper") -> {
                    val content = if (body.contains("\"verdicts\"") || body.contains("\\\"verdicts\\\"")) {
                        """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true},{"chunk_index":1,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true}]}"""
                    } else {
                        """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Clean notice"}"""
                    }
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                body.contains("Structured Event Card Translator") -> respond("Translator error", HttpStatusCode.InternalServerError)
                else -> {
                    val concurrent = active.incrementAndGet()
                    maxInFlight.accumulateAndGet(concurrent, ::maxOf)
                    kotlinx.coroutines.delay(25)
                    active.decrementAndGet()
                    val content = """{"is_relevant":true,"events":[{"title":"未翻译的中文通知标题","summary":"本周五前需要提交最终版论文。","category":"DEADLINE","priority":"IMPORTANT","source_message_ids":[1]}]}"""
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }

        val checkpointStore = SyncCheckpointStore(appDb)
        val service = SyncService(
            eventCardStore = eventCardStore,
            syncCheckpointStore = checkpointStore,
            llmClientFactory = { cfg -> KtorLlmClient(cfg, engine) }
        )
        val res = service.syncNow(
            AppSettings(
                wechatDataDir = dir.absolutePath,
                monitoredRoomIds = setOf(room),
                targetLanguage = "en",
                llm = LlmConfig(providerType = LlmProviderType.OLLAMA, endpointUrl = "http://localhost:11434", modelName = "qwen2.5")
            )
        )
        assertTrue(res is SyncResult.Success)
        assertEquals(1, maxInFlight.get(), "Ollama sync must throttle concurrency to 1")
        assertEquals(1, (res as SyncResult.Success).roomErrors.size, "CardTranslator failure must bubble as room error")
        kotlin.test.assertNull(checkpointStore.get(room), "Translator failure must prevent checkpoint advancement")
    }

    @Test
    fun `syncNow extracts file attachment resolves path via hardlink and injects bounded spreadsheet preview`() = runBlocking<Unit> {
        val accountRoot = File(tempDir, "wxid_sync_attach").apply { mkdirs() }
        val dbDir = File(accountRoot, "db_storage").apply { mkdirs() }
        val contactDir = File(dbDir, "contact").apply { mkdirs() }
        val messageDir = File(dbDir, "message").apply { mkdirs() }
        val hardlinkDir = File(dbDir, "hardlink").apply { mkdirs() }
        val fileMonthDir = File(accountRoot, "msg/file/2026-11").apply { mkdirs() }

        val excelFile = File(fileMonthDir, "exam_seats.xlsx")
        org.apache.poi.xssf.usermodel.XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Schedule")
            sheet.createRow(0).apply {
                createCell(0).setCellValue("Course")
                createCell(1).setCellValue("Room")
                createCell(2).setCellValue("Time")
            }
            sheet.createRow(1).apply {
                createCell(0).setCellValue("Algorithms Final")
                createCell(1).setCellValue("Auditorium C-201")
                createCell(2).setCellValue("Nov 29 09:00")
            }
            excelFile.outputStream().use { wb.write(it) }
        }

        val excelBytes = excelFile.readBytes()
        val excelMd5 = java.security.MessageDigest.getInstance("MD5")
            .digest(excelBytes)
            .joinToString("") { "%02x".format(it) }

        val room = "exam_sync@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(contactDir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$room', 'CS Exam Group', '')")
                stmt.execute("INSERT INTO contact VALUES ('wxid_prof', 'Prof_Zhang', '')")
            }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(hardlinkDir, "hardlink.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE dir2id(username TEXT PRIMARY KEY)")
                stmt.execute("INSERT INTO dir2id(rowid, username) VALUES (1, '2026-11')")
                stmt.execute("CREATE TABLE file_hardlink_info_v4(md5 TEXT, file_name TEXT, file_size INTEGER, dir1 INTEGER, dir2 INTEGER)")
                stmt.executeUpdate("INSERT INTO file_hardlink_info_v4 VALUES ('$excelMd5', 'exam_seats.xlsx', ${excelFile.length()}, 1, 0)")
            }
        }
        val packedFileType = (6L shl 32) or 49L
        val fileXml = """
            wxid_prof:
            <?xml version="1.0"?>
            <msg><appmsg><title>exam_seats.xlsx</title><type>6</type><appattach><totallen>${excelFile.length()}</totallen><fileext>xlsx</fileext></appattach><md5>$excelMd5</md5></appmsg></msg>
        """.trimIndent()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(messageDir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, local_type INTEGER, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $table VALUES (1, 1795000000, 'wxid_prof', 1, CAST('Final exam room and seating spreadsheet attached below.' AS BLOB))")
                val ps = conn.prepareStatement("INSERT INTO $table VALUES (2, 1795000005, 'wxid_prof', ?, ?)")
                ps.setLong(1, packedFileType)
                ps.setBytes(2, fileXml.toByteArray(Charsets.UTF_8))
                ps.executeUpdate()
                ps.close()
            }
        }

        val capturedPrompts = mutableListOf<String>()
        val engine = MockEngine { req ->
            val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            capturedPrompts.add(body)
            val content = when {
                body.contains("Semantic Filter & Topic Chunker") ->
                    """{"chunks":[{"topic":"Algorithms Final Exam","message_ids":[1,2],"category":"EXAM"}]}"""
                body.contains("Rule & Substance Gatekeeper") ->
                    """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true}]}"""
                else ->
                    """{"is_relevant":true,"events":[{"title":"Algorithms Final Exam Seating","summary":"Algorithms Final is in Auditorium C-201 on Nov 29 at 09:00 per exam_seats.xlsx.","category":"EXAM","priority":"IMPORTANT","source_message_ids":[1,2]}]}"""
            }
            val payload = """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}"""
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val checkpointStore = SyncCheckpointStore(appDb)
        val service = SyncService(
            eventCardStore = eventCardStore,
            syncCheckpointStore = checkpointStore,
            llmClientFactory = { cfg -> KtorLlmClient(cfg, engine) }
        )
        val result = service.syncNow(
            AppSettings(
                wechatDataDir = dbDir.absolutePath,
                monitoredRoomIds = setOf(room),
                parseAttachmentContents = true,
                llm = LlmConfig(providerType = LlmProviderType.OPENAI_COMPATIBLE, apiKey = "k")
            )
        )
        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).newEventsCount)

        assertTrue(
            capturedPrompts.any { it.contains("Auditorium C-201") && it.contains("[Shared File: exam_seats.xlsx") },
            "Prompts must receive bounded spreadsheet preview content"
        )

        val storedCard = eventCardStore.listActive().single()
        assertEquals(1, storedCard.attachments.size)
        assertEquals("exam_seats.xlsx", storedCard.attachments.single().fileName)
        assertEquals(excelFile.canonicalPath, storedCard.attachments.single().localPath)
        assertFalse(storedCard.sourceMessages.contains("Auditorium C-201"), "Raw spreadsheet preview should stay out of stored sourceMessages")
    }

    @Test
    fun `syncNow persists cards incrementally and keeps partial cards without advancing checkpoint on mid-room failure`() = runBlocking {
        val dir = File(tempDir, "incremental_partial_wechat").apply { mkdirs() }
        val room = "partial@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$room', 'Partial Sync Group', '')")
                stmt.execute("INSERT INTO contact VALUES ('teacher', 'Prof_Lin', '')")
            }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                val longNotice1 = "Notice 1: Submit form online by Friday. " + "Please review the attached guidelines carefully. ".repeat(32)
                stmt.executeUpdate("INSERT INTO $table VALUES (1, 1794800000, 'teacher', CAST('$longNotice1' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $table VALUES (2, 1794800010, 'teacher', CAST('通知2：周五下午三点实验室开会。' AS BLOB))")
            }
        }

        var failSecondTranslator = true
        val engine = MockEngine { req ->
            val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            when {
                body.contains("Semantic Filter & Topic Chunker") -> {
                    val content = """{"chunks":[{"topic":"Notice 1","message_ids":[1]},{"topic":"Notice 2","message_ids":[2]}]}"""
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                body.contains("Rule & Substance Gatekeeper") -> {
                    val content = if (body.contains("\"verdicts\"") || body.contains("\\\"verdicts\\\"")) {
                        """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true},{"chunk_index":1,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true}]}"""
                    } else {
                        """{"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true,"reason":"Clean notice"}"""
                    }
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                body.contains("Structured Event Card Translator") -> {
                    if (failSecondTranslator) {
                        respond("Translator 500", HttpStatusCode.InternalServerError)
                    } else {
                        val content = """{"title":"Notice 2 Meeting","summary":"Lab meeting at 15:00 on Friday.","sections":[]}"""
                        respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                body.contains("Notice 1: Submit form online") -> {
                    val content = """{"is_relevant":true,"events":[{"title":"Notice 1","summary":"Submit form online by Friday.","category":"DEADLINE","priority":"IMPORTANT","source_message_ids":[1]}]}"""
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> {
                    // Notice 2 returned untranslated so CardTranslator is triggered
                    val content = """{"is_relevant":true,"events":[{"title":"通知2：实验室开会","summary":"周五下午三点在实验室开会。","category":"NOTICE","priority":"NORMAL","source_message_ids":[2]}]}"""
                    respond("""{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }

        val checkpointStore = SyncCheckpointStore(appDb)
        val service = SyncService(
            eventCardStore = eventCardStore,
            syncCheckpointStore = checkpointStore,
            llmClientFactory = { cfg -> KtorLlmClient(cfg, engine) }
        )
        val firstPersisted = mutableListOf<String>()
        val progressEvents = mutableListOf<SyncProgress>()
        val firstRun = service.syncNow(
            settings = AppSettings(
                wechatDataDir = dir.absolutePath,
                monitoredRoomIds = setOf(room),
                targetLanguage = "en",
                llm = LlmConfig(providerType = LlmProviderType.OLLAMA, endpointUrl = "http://localhost:11434", modelName = "qwen2.5")
            ),
            onCardPersisted = { firstPersisted.add(it.title) },
            onProgress = { progressEvents.add(it) }
        )

        assertTrue(firstRun is SyncResult.Success)
        assertEquals(1, (firstRun as SyncResult.Success).newEventsCount)
        assertEquals(1, firstRun.roomErrors.size)
        assertEquals(listOf("Notice 1"), firstPersisted)
        assertEquals(listOf("Notice 1"), eventCardStore.listActive().map { it.title })
        assertTrue(progressEvents.any { it.stageLabel.contains("Scanning") })
        kotlin.test.assertNull(checkpointStore.get(room), "Mid-room failure must not advance checkpoint")

        // Second sync recovers Notice 2 and suppresses duplicate Notice 1 via dedupe_key without re-emitting
        failSecondTranslator = false
        val secondPersisted = mutableListOf<String>()
        val secondRun = service.syncNow(
            settings = AppSettings(
                wechatDataDir = dir.absolutePath,
                monitoredRoomIds = setOf(room),
                targetLanguage = "en",
                llm = LlmConfig(providerType = LlmProviderType.OLLAMA, endpointUrl = "http://localhost:11434", modelName = "qwen2.5")
            ),
            onCardPersisted = { secondPersisted.add(it.title) }
        )
        assertTrue(secondRun is SyncResult.Success)
        assertEquals(1, (secondRun as SyncResult.Success).newEventsCount)
        assertTrue(secondRun.roomErrors.isEmpty())
        assertEquals(listOf("Notice 2 Meeting"), secondPersisted)
        assertEquals(2, eventCardStore.listActive().size)
        assertEquals(2L, checkpointStore.get(room)?.lastLocalId)
    }

    @Test
    fun `syncNow serializes concurrent cloud card upserts cleanly`() = runBlocking {
        val dir = File(tempDir, "cloud_concurrent_wechat").apply { mkdirs() }
        val room = "cloud@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$room', 'Cloud Group', '')")
                stmt.execute("INSERT INTO contact VALUES ('t1', 'Teacher_1', '')")
            }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                (1..4).forEach { id ->
                    stmt.executeUpdate("INSERT INTO $table VALUES ($id, ${1794800000 + id * 10}, 't1', CAST('Notice $id details' AS BLOB))")
                }
            }
        }

        val engine = MockEngine { req ->
            val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            val content = when {
                body.contains("Semantic Filter & Topic Chunker") ->
                    """{"chunks":[{"topic":"T1","message_ids":[1]},{"topic":"T2","message_ids":[2]},{"topic":"T3","message_ids":[3]},{"topic":"T4","message_ids":[4]}]}"""
                body.contains("Rule & Substance Gatekeeper") ->
                    """{"verdicts":[{"chunk_index":0,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true},{"chunk_index":1,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true},{"chunk_index":2,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true},{"chunk_index":3,"is_group_notice":true,"passes_rules":true,"has_substance":true,"keep":true}]}"""
                else -> {
                    val idx = (1..4).first { body.contains("Notice $it details") }
                    """{"is_relevant":true,"events":[{"title":"Cloud Card $idx","summary":"Notice $idx summary","category":"NOTICE","priority":"NORMAL","source_message_ids":[$idx]}]}"""
                }
            }
            val wrapper = """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), content)}}}]}"""
            respond(wrapper, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val checkpointStore = SyncCheckpointStore(appDb)
        val service = SyncService(
            eventCardStore = eventCardStore,
            syncCheckpointStore = checkpointStore,
            llmClientFactory = { cfg -> KtorLlmClient(cfg, engine) }
        )
        val persistedTitles = mutableListOf<String>()
        val result = service.syncNow(
            settings = AppSettings(
                wechatDataDir = dir.absolutePath,
                monitoredRoomIds = setOf(room),
                llm = LlmConfig(providerType = LlmProviderType.OPENAI_COMPATIBLE, apiKey = "k")
            ),
            onCardPersisted = { persistedTitles.add(it.title) }
        )

        assertTrue(result is SyncResult.Success)
        assertEquals(4, (result as SyncResult.Success).newEventsCount)
        assertEquals(4, persistedTitles.size)
        assertEquals(4, eventCardStore.listActive().size)
        assertEquals(4L, checkpointStore.getOrThrow(room)?.lastLocalId)
    }
}
