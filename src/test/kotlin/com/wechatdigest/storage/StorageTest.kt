package com.wechatdigest.storage

import com.wechatdigest.app.AppSettings
import com.wechatdigest.app.AppThemeMode
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventDetailItem
import com.wechatdigest.core.model.EventDetailKind
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.core.sanitizer.PrefilterConfig
import com.wechatdigest.core.sanitizer.PrefilterMatchType
import com.wechatdigest.core.sanitizer.PrefilterRule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StorageTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var dbFile: File
    private lateinit var appDb: AppDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var eventCardStore: EventCardStore

    @BeforeEach
    fun setUp() {
        dbFile = File(tempDir, "test_app.db")
        appDb = AppDatabase(dbFile)
        settingsStore = SettingsStore(appDb)
        eventCardStore = EventCardStore(appDb)
    }

    @Test
    fun `settings store returns defaults when empty`() {
        val settings = settingsStore.load()
        assertEquals("", settings.wechatDataDir)
        assertEquals("en", settings.targetLanguage)
        assertEquals("international student studying at university", settings.userPersona)
        assertTrue(settings.monitoredRoomIds.isEmpty())
        assertEquals(LlmProviderType.OPENAI_COMPATIBLE, settings.llm.providerType)
    }

    @Test
    fun `settings store round-trips custom settings`() {
        val custom = AppSettings(
            wechatDataDir = "/tmp/wechat/data",
            llm = LlmConfig(
                providerType = LlmProviderType.OLLAMA,
                endpointUrl = "http://localhost:11434",
                apiKey = "ollama-key",
                modelName = "llama3.2"
            ),
            monitoredRoomIds = setOf("room1@chatroom", "room2@chatroom"),
            userPersona = "Faculty researcher",
            targetLanguage = "zh",
            messagesPerSync = 100,
            themeMode = AppThemeMode.DARK,
            parseAttachmentContents = false,
            prefilter = PrefilterConfig(
                minMessageLength = 3,
                rules = listOf(
                    PrefilterRule(
                        id = "custom-1",
                        pattern = "noted",
                        matchType = PrefilterMatchType.WHOLE_MESSAGE,
                        enabled = false,
                        label = "Disabled test rule"
                    ),
                    PrefilterRule(
                        id = "custom-2",
                        pattern = "[Sticker]",
                        matchType = PrefilterMatchType.CONTAINS,
                        enabled = true
                    )
                )
            )
        )

        settingsStore.save(custom)
        val loaded = settingsStore.load()

        assertEquals(custom.wechatDataDir, loaded.wechatDataDir)
        assertEquals(custom.llm.providerType, loaded.llm.providerType)
        assertEquals(custom.llm.endpointUrl, loaded.llm.endpointUrl)
        assertEquals(custom.llm.apiKey, loaded.llm.apiKey)
        assertEquals(custom.llm.modelName, loaded.llm.modelName)
        assertEquals(custom.monitoredRoomIds, loaded.monitoredRoomIds)
        assertEquals(custom.userPersona, loaded.userPersona)
        assertEquals(custom.targetLanguage, loaded.targetLanguage)
        assertEquals(custom.messagesPerSync, loaded.messagesPerSync)
        assertEquals(AppThemeMode.DARK, loaded.themeMode)
        assertFalse(loaded.parseAttachmentContents)
        assertEquals(custom.prefilter, loaded.prefilter)
    }

    @Test
    fun `settings store populates default prefilter config when loading legacy json`() {
        val legacyJson = """{"wechatDataDir":"/legacy/wechat","targetLanguage":"en"}"""
        appDb.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("INSERT OR REPLACE INTO settings (key, value) VALUES ('app_settings', '$legacyJson')")
            }
        }

        val loaded = settingsStore.load()
        assertEquals("/legacy/wechat", loaded.wechatDataDir)
        assertEquals(PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH, loaded.prefilter.minMessageLength)
        assertTrue(loaded.prefilter.rules.isNotEmpty())
        assertTrue(loaded.prefilter.rules.any { it.pattern == "received" && it.enabled })
    }

    @Test
    fun `event card store upsert, listActive, markRead, and dismiss`() {
        val card1 = EventCard(
            id = "event-1",
            title = "Final Exam",
            summary = "Math 101 Exam on Dec 15",
            translatedSummary = "期末考试：12月15日数学101",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            eventDate = "2026-12-15",
            originalSender = "Prof. Smith",
            sourceRoomId = "math@chatroom",
            timestampSeconds = 1731000000L
        )

        val card2 = EventCard(
            id = "event-2",
            title = "Campus Pizza Party",
            summary = "Free pizza at student center",
            translatedSummary = "学生活动中心免费披萨",
            category = EventCategory.ACTIVITY,
            priority = EventPriority.LOW,
            eventDate = "2026-11-20",
            originalSender = "Student Council",
            sourceRoomId = "activity@chatroom",
            timestampSeconds = 1730900000L
        )

        // Upsert 2 cards
        val insertedCount = eventCardStore.upsert(listOf(card1, card2))
        assertEquals(2, insertedCount)

        val activeCards = eventCardStore.listActive()
        assertEquals(2, activeCards.size)
        assertEquals("event-1", activeCards[0].id)
        assertFalse(activeCards[0].isRead)
        assertFalse(activeCards[0].isDismissed)

        // Deduplication test: re-inserting card1 should be ignored (0 new inserts)
        val duplicateCount = eventCardStore.upsert(listOf(card1))
        assertEquals(0, duplicateCount)
        assertEquals(2, eventCardStore.listActive().size)

        // Mark read test
        eventCardStore.markRead("event-1")
        val afterRead = eventCardStore.listActive()
        val readCard = afterRead.find { it.id == "event-1" }
        assertNotNull(readCard)
        assertTrue(readCard.isRead)

        // Dismiss test
        eventCardStore.dismiss("event-2")
        val afterDismiss = eventCardStore.listActive()
        assertEquals(1, afterDismiss.size)
        assertEquals("event-1", afterDismiss[0].id)

        // Dismissed card should not be resurrected by re-upsert
        val reinsertDismissed = eventCardStore.upsert(listOf(card2))
        assertEquals(0, reinsertDismissed)
        val stillDismissed = eventCardStore.listActive()
        assertEquals(1, stillDismissed.size)
    }

    @Test
    fun `event card store orders chronologically most recent first regardless of read status or priority`() {
        val now = 1731000000L
        val lowReadNewest = EventCard(
            id = "low-read-newest",
            title = "Low Read Newest",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.OTHER,
            priority = EventPriority.LOW,
            originalSender = "Sender",
            sourceRoomId = "room1@chatroom",
            timestampSeconds = now + 500
        )

        val criticalUnreadOld = EventCard(
            id = "critical-unread-old",
            title = "Critical Unread Old",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Sender",
            sourceRoomId = "room2@chatroom",
            timestampSeconds = now - 100
        )

        val criticalUnreadNow = EventCard(
            id = "critical-unread-now",
            title = "Critical Unread Now",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Sender",
            sourceRoomId = "room3@chatroom",
            timestampSeconds = now
        )

        val importantUnreadNow = EventCard(
            id = "important-unread-now",
            title = "Important Unread Now",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Sender",
            sourceRoomId = "room4@chatroom",
            timestampSeconds = now
        )

        val normalUnreadMiddle = EventCard(
            id = "normal-unread-middle",
            title = "Normal Unread Middle",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.EXAM,
            priority = EventPriority.NORMAL,
            originalSender = "Sender",
            sourceRoomId = "room5@chatroom",
            timestampSeconds = now + 100
        )

        eventCardStore.upsert(
            listOf(criticalUnreadOld, importantUnreadNow, criticalUnreadNow, normalUnreadMiddle, lowReadNewest)
        )
        eventCardStore.markRead("low-read-newest")

        val sorted = eventCardStore.listActive()
        val sortedIds = sorted.map { it.id }

        // Expected order: strictly most recent timestampSeconds DESC, breaking ties by priority ASC:
        // 1. lowReadNewest (timestamp = now + 500, LOW, read)
        // 2. normalUnreadMiddle (timestamp = now + 100, NORMAL, unread)
        // 3. criticalUnreadNow (timestamp = now, CRITICAL, unread)
        // 4. importantUnreadNow (timestamp = now, IMPORTANT, unread)
        // 5. criticalUnreadOld (timestamp = now - 100, CRITICAL, unread)
        assertEquals(
            listOf(
                "low-read-newest",
                "normal-unread-middle",
                "critical-unread-now",
                "important-unread-now",
                "critical-unread-old"
            ),
            sortedIds
        )
    }

    @Test
    fun `event card store upsert empty list returns 0 and markUnread works`() {
        val count = eventCardStore.upsert(emptyList())
        assertEquals(0, count)

        val card = EventCard(
            id = "unread-toggle-card",
            title = "Toggle Read",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Sender",
            sourceRoomId = "room",
            timestampSeconds = 100L
        )

        eventCardStore.upsert(listOf(card))
        eventCardStore.markRead("unread-toggle-card")
        assertTrue(eventCardStore.listActive().first().isRead)

        eventCardStore.markUnread("unread-toggle-card")
        assertFalse(eventCardStore.listActive().first().isRead)
    }

    @Test
    fun `settings store returns defaults when db contains malformed json`() {
        appDb.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("INSERT OR REPLACE INTO settings (key, value) VALUES ('app_settings', 'INVALID_JSON_CORRUPT')")
            }
        }

        val loaded = settingsStore.load()
        assertEquals("", loaded.wechatDataDir)
        assertEquals("en", loaded.targetLanguage)
    }

    @Test
    fun `defaultDbFile returns valid path under user home`() {
        val file = AppDatabase.defaultDbFile()
        assertTrue(file.absolutePath.contains("WeChatDigest"))
    }

    @Test
    fun `clearAll and clear wipe stored records cleanly`() {
        settingsStore.save(AppSettings(wechatDataDir = "/tmp/wechat", targetLanguage = "zh"))
        assertEquals("/tmp/wechat", settingsStore.load().wechatDataDir)

        settingsStore.clear()
        assertEquals("", settingsStore.load().wechatDataDir)
        assertEquals("en", settingsStore.load().targetLanguage)

        val card = EventCard(
            id = "card-to-clear",
            title = "Clear Test",
            summary = "Summary",
            translatedSummary = "Summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Sender",
            sourceRoomId = "room@chatroom",
            timestampSeconds = 100L
        )
        eventCardStore.upsert(listOf(card))
        assertEquals(1, eventCardStore.listActive().size)
        eventCardStore.clearAll()
        assertTrue(eventCardStore.listActive().isEmpty())

        val groupConfigStore = GroupConfigStore(appDb)
        groupConfigStore.save(com.wechatdigest.core.model.GroupConfig("room@chatroom", "Prompt", "Persona"))
        assertEquals(1, groupConfigStore.getAll().size)
        groupConfigStore.clearAll()
        assertTrue(groupConfigStore.getAll().isEmpty())
    }

    @Test
    fun `event card store preserves sourceRoomName and sourceMessages`() {
        val card = EventCard(
            id = "card-source-fields",
            title = "Lab Safety Training",
            summary = "明天下午两点实验室安全培训",
            translatedSummary = "Lab safety training tomorrow at 2 PM.",
            category = EventCategory.NOTICE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Prof_Wang",
            sourceRoomId = "10000000001@chatroom",
            sourceRoomName = "2026秋季学期学生群（境内）",
            sourceMessages = "[Prof_Wang]: 明天下午两点实验室安全培训，请全体新生参加。",
            timestampSeconds = 1731000000L
        )

        eventCardStore.upsert(listOf(card))
        val retrieved = eventCardStore.listActive().single { it.id == "card-source-fields" }

        assertEquals("2026秋季学期学生群（境内）", retrieved.sourceRoomName)
        assertEquals("[Prof_Wang]: 明天下午两点实验室安全培训，请全体新生参加。", retrieved.sourceMessages)
        assertEquals("", retrieved.translatedSourceMessages)

        eventCardStore.updateTranslatedSourceMessages(
            "card-source-fields",
            "[Prof_Wang]: Lab safety training tomorrow at 2 PM, all freshmen must attend."
        )
        val updated = eventCardStore.listActive().single { it.id == "card-source-fields" }
        assertEquals(
            "[Prof_Wang]: Lab safety training tomorrow at 2 PM, all freshmen must attend.",
            updated.translatedSourceMessages
        )
    }

    @Test
    fun `event card store persists sections, scopes listRecentByRoom, and dedupes by sourceMessages`() {
        val originalSource = "[2026-11-16 15:45:37] Advisor_Chen: (1) Thesis award Nov 25/26. (2) Delayed disclosure Nov 25/26."
        val sectionedCard = EventCard(
            id = "sectioned-1",
            title = "Degree Thesis Notices",
            summary = "Two thesis submission tracks.",
            translatedSummary = "Two thesis submission tracks.",
            sections = listOf(
                EventSection("Track 1: Award Nomination", "Email by Nov 25; hard copies Nov 26 at 422-1."),
                EventSection("Track 2: Delayed Disclosure", "Email by Nov 25; 2 copies Nov 26 at 422-1.")
            ),
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            eventDate = "2026-11-25",
            originalSender = "Advisor_Chen",
            sourceRoomId = "cse@chatroom",
            sourceRoomName = "International students CSE",
            sourceMessages = originalSource,
            timestampSeconds = 1794815137L
        )

        assertEquals(1, eventCardStore.upsert(listOf(sectionedCard)))

        val loaded = eventCardStore.listActive().single { it.id == "sectioned-1" }
        assertEquals(2, loaded.sections.size)
        assertEquals("Track 1: Award Nomination", loaded.sections[0].heading)
        assertEquals("Email by Nov 25; 2 copies Nov 26 at 422-1.", loaded.sections[1].content)

        // Attempt to insert a differently titled split card backed by the same sourceMessages (whitespace-variant)
        val duplicateSplitCard = sectionedCard.copy(
            id = "sectioned-duplicate-split",
            title = "Deadline for submitting final paper copies",
            eventDate = "2026-11-26",
            sourceMessages = "  [2026-11-16 15:45:37] Advisor_Chen: (1) Thesis award Nov 25/26.   (2) Delayed disclosure Nov 25/26.  "
        )
        assertEquals(0, eventCardStore.upsert(listOf(duplicateSplitCard)))
        assertEquals(1, eventCardStore.listActive().count { it.sourceRoomId == "cse@chatroom" })

        // Verify listRecentByRoom scoping, dismissed filtering, and limit
        val olderSameRoom = EventCard(
            id = "cse-older",
            title = "Older CSE Notice",
            summary = "Older summary",
            translatedSummary = "Older summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Admin",
            sourceRoomId = "cse@chatroom",
            sourceMessages = "[2026-11-15 10:00:00] Admin: Older notice.",
            timestampSeconds = 1794700000L
        )
        val otherRoom = EventCard(
            id = "other-room",
            title = "Other Room Notice",
            summary = "Other summary",
            translatedSummary = "Other summary",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            originalSender = "Admin",
            sourceRoomId = "math@chatroom",
            sourceMessages = "[2026-11-16 16:00:00] Admin: Math notice.",
            timestampSeconds = 1794900000L
        )
        val dismissedSameRoom = EventCard(
            id = "cse-dismissed",
            title = "Dismissed CSE Notice",
            summary = "Dismissed",
            translatedSummary = "Dismissed",
            category = EventCategory.NOTICE,
            priority = EventPriority.LOW,
            originalSender = "Admin",
            sourceRoomId = "cse@chatroom",
            sourceMessages = "[2026-11-16 17:00:00] Admin: Dismissed notice.",
            timestampSeconds = 1794950000L,
            isDismissed = true
        )
        eventCardStore.upsert(listOf(olderSameRoom, otherRoom, dismissedSameRoom))

        val recentCse = eventCardStore.listRecentByRoom("cse@chatroom", limit = 10)
        assertEquals(listOf("sectioned-1", "cse-older"), recentCse.map { it.id })
        assertEquals(listOf("sectioned-1"), eventCardStore.listRecentByRoom("cse@chatroom", limit = 1).map { it.id })
    }

    @Test
    fun `event card store persists and updates isStarred flag`() {
        val card = EventCard(
            id = "star-card-1",
            title = "Important Scholarship Notice",
            summary = "Apply before June 1",
            translatedSummary = "Apply before June 1",
            category = EventCategory.DEADLINE,
            priority = EventPriority.IMPORTANT,
            originalSender = "Dean_Office",
            sourceRoomId = "grad@chatroom",
            timestampSeconds = 1795000000L,
            isStarred = false
        )

        eventCardStore.upsert(listOf(card))
        assertFalse(eventCardStore.listActive().single().isStarred)
        assertFalse(eventCardStore.listRecentByRoom("grad@chatroom").single().isStarred)

        eventCardStore.setStarred("star-card-1", true)
        assertTrue(eventCardStore.listActive().single().isStarred)
        assertTrue(eventCardStore.listRecentByRoom("grad@chatroom").single().isStarred)

        eventCardStore.setStarred("star-card-1", false)
        assertFalse(eventCardStore.listActive().single().isStarred)
    }

    @Test
    fun `event card store persists and updates attachments list`() {
        val pendingAttachment = EventAttachment(
            fileName = "exam_seats.xlsx",
            fileExtension = "xlsx",
            fileSizeBytes = 20480L,
            md5 = "0123456789abcdef0123456789abcdef",
            localPath = null,
            messageTimestampSeconds = 1795000100L,
            mediaExpireAtSeconds = 1893456000L
        )
        val card = EventCard(
            id = "attach-card-1",
            title = "Final Exam Seating Chart",
            summary = "See attached Excel file for room numbers.",
            translatedSummary = "See attached Excel file for room numbers.",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Advisor",
            sourceRoomId = "exam@chatroom",
            attachments = listOf(pendingAttachment),
            timestampSeconds = 1795000100L
        )

        eventCardStore.upsert(listOf(card))
        val loaded = eventCardStore.listActive().single()
        assertEquals(1, loaded.attachments.size)
        assertEquals("exam_seats.xlsx", loaded.attachments.single().fileName)
        assertEquals(null, loaded.attachments.single().localPath)

        val downloadedFile = File(tempDir, "exam_seats.xlsx").apply { writeBytes(ByteArray(32)) }
        val resolved = pendingAttachment.copy(localPath = downloadedFile.absolutePath)
        eventCardStore.updateAttachments("attach-card-1", listOf(resolved))

        val reloaded = eventCardStore.listRecentByRoom("exam@chatroom").single()
        assertEquals(downloadedFile.absolutePath, reloaded.attachments.single().localPath)
        assertTrue(reloaded.attachments.single().isLocalFilePresent())
    }

    @Test
    fun `event card store round-trips section items and reads legacy section json safely`() {
        val structuredCard = EventCard(
            id = "items-card-1",
            title = "Final Exam Details",
            summary = "Exam on June 15.",
            translatedSummary = "Exam on June 15.",
            sections = listOf(
                EventSection(
                    heading = "Exam Details",
                    content = "Bring student ID.",
                    items = listOf(
                        EventDetailItem(EventDetailKind.DATE, "Date", "2026-06-15"),
                        EventDetailItem(EventDetailKind.TIME, "Time", "14:00 - 16:00"),
                        EventDetailItem(EventDetailKind.LOCATION, "Location", "Room 302")
                    )
                )
            ),
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Advisor",
            sourceRoomId = "cs@chatroom",
            timestampSeconds = 1795000200L
        )
        assertEquals(1, eventCardStore.upsert(listOf(structuredCard)))

        val loaded = eventCardStore.listActive().single { it.id == "items-card-1" }
        assertEquals(3, loaded.sections.single().items.size)
        assertEquals(EventDetailKind.LOCATION, loaded.sections.single().items[2].kind)
        assertEquals("Room 302", loaded.sections.single().items[2].value)

        // Legacy row whose sections JSON has no "items" property must still deserialize cleanly
        appDb.getConnection().prepareStatement(
            """
            INSERT INTO event_cards (
                id, dedupe_key, title, summary, translated_summary, sections, category, priority,
                event_date, action_items, original_sender, source_room_id, source_room_name,
                source_messages, translated_source_messages, attachments, timestamp_seconds,
                is_read, is_starred, is_dismissed, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "legacy-sections-1")
            ps.setString(2, "legacy-dedupe-1")
            ps.setString(3, "Legacy Notice")
            ps.setString(4, "Summary")
            ps.setString(5, "Summary")
            ps.setString(6, """[{"heading":"Logistics","content":"- Location: 422-1"}]""")
            ps.setString(7, "NOTICE")
            ps.setString(8, "NORMAL")
            ps.setString(9, null)
            ps.setString(10, "[]")
            ps.setString(11, "Admin")
            ps.setString(12, "cs@chatroom")
            ps.setString(13, "CS Group")
            ps.setString(14, "")
            ps.setString(15, "")
            ps.setString(16, "[]")
            ps.setLong(17, 1795000100L)
            ps.setLong(18, 1795000100L)
            ps.executeUpdate()
        }

        val legacyLoaded = eventCardStore.listActive().single { it.id == "legacy-sections-1" }
        assertEquals("Logistics", legacyLoaded.sections.single().heading)
        assertTrue(legacyLoaded.sections.single().items.isEmpty())
        assertEquals("- Location: 422-1", legacyLoaded.sections.single().content)

        // Verify updateSections persists Pass 4 enriched items & bullets cleanly
        val updatedSections = listOf(
            EventSection(
                heading = "Logistics",
                content = "",
                items = listOf(EventDetailItem(EventDetailKind.LOCATION, "Venue", "Room 422-1")),
                bullets = listOf("Location: 422-1")
            )
        )
        eventCardStore.updateSections("legacy-sections-1", updatedSections)
        val enrichedLoaded = eventCardStore.listActive().single { it.id == "legacy-sections-1" }
        assertEquals(1, enrichedLoaded.sections.single().items.size)
        assertEquals("Venue", enrichedLoaded.sections.single().items.single().label)
        assertEquals(listOf("Location: 422-1"), enrichedLoaded.sections.single().bullets)
    }

    @Test
    fun `applyUpdateOrInsert atomically supersedes active card by short id prefix and preserves star`() {
        val originalId = "abcdef12-3456-7890-abcd-111122223333"
        val original = EventCard(
            id = originalId,
            title = "Algorithm Exam Room A",
            summary = "Exam in Hall A.",
            translatedSummary = "Exam in Hall A.",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof. Wang",
            originalSenderId = "wxid_prof_wang",
            sourceRoomId = "algo@chatroom",
            sourceMessages = "[2026-11-20 09:00:00] Prof. Wang: Algorithm Final Exam will be held in Hall A on Dec 20.",
            sourceLocalIds = listOf(101L),
            timestampSeconds = 1795000000L,
            isStarred = true
        )
        assertEquals(1, eventCardStore.upsert(listOf(original)))
        val fpSet = eventCardStore.listActiveFingerprints()
        assertEquals(1, fpSet.size)

        // Update referencing 8-char prefix supersedes original atomically and inherits star state
        val updated = EventCard(
            id = "fedcba98-0000-0000-0000-444455556666",
            title = "Algorithm Exam Moved to Hall B",
            summary = "Exam moved to Hall B.",
            translatedSummary = "Exam moved to Hall B.",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Prof. Wang",
            originalSenderId = "wxid_prof_wang",
            sourceRoomId = "algo@chatroom",
            sourceMessages = "[2026-11-21 10:00:00] Prof. Wang: Correction: Algorithm Final Exam moved from Hall A to Hall B.",
            sourceLocalIds = listOf(102L),
            timestampSeconds = 1795086400L,
            status = com.wechatdigest.core.model.EventCardStatus.UPDATED,
            supersedesCardId = "card:abcdef12"
        )
        val applyResult = eventCardStore.applyUpdateOrInsertOrThrow(listOf(updated))
        assertEquals(1, applyResult.insertedCards.size)
        assertEquals(listOf(originalId), applyResult.supersededCardIds)
        assertEquals(originalId, applyResult.insertedCards.single().supersedesCardId)
        assertTrue(applyResult.insertedCards.single().isStarred)

        val active = eventCardStore.listActive()
        assertEquals(1, active.size)
        assertEquals(updated.id, active.single().id)
        assertEquals(com.wechatdigest.core.model.EventCardStatus.UPDATED, active.single().status)
        assertEquals(originalId, active.single().supersedesCardId)
        assertEquals("wxid_prof_wang", active.single().originalSenderId)
        assertEquals(listOf(102L), active.single().sourceLocalIds)
        assertTrue(active.single().isStarred)

        // Security regressions: cross-room exact-ID supersede blocked & untrusted cross-sender spoof does not dismiss original
        val foreignAttackerUpdate = EventCard(
            id = "spoof-1",
            title = "Fake Cancellation",
            summary = "Cancelled",
            translatedSummary = "Cancelled",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Student_X",
            originalSenderId = "wxid_student_spoof",
            sourceRoomId = "algo@chatroom",
            sourceMessages = "Cancelled",
            timestampSeconds = 1795100000L,
            status = com.wechatdigest.core.model.EventCardStatus.CANCELLED,
            supersedesCardId = updated.id
        )
        val crossRoomUpdate = foreignAttackerUpdate.copy(
            id = "cross-room-spoof",
            originalSenderId = "wxid_prof_wang",
            sourceRoomId = "other_room@chatroom",
            supersedesCardId = updated.id
        )
        val spoofOutcome = eventCardStore.applyUpdateOrInsertOrThrow(listOf(foreignAttackerUpdate, crossRoomUpdate), roomOwnerId = "wxid_owner")
        assertTrue(spoofOutcome.supersededCardIds.isEmpty(), "Untrusted cross-sender & cross-room supersedes must not dismiss prior card")
        assertNotNull(eventCardStore.listActive().firstOrNull { it.id == updated.id && !it.isDismissed })

        // Idempotent replay of the exact UPDATED card must keep dedupe_key identical and insert 0 duplicates
        val replayCount = eventCardStore.applyUpdateOrInsertOrThrow(listOf(updated)).insertedCards.size
        assertEquals(0, replayCount, "Re-applying already-persisted amended card must remain idempotent")

        // Prefix guard rails: <6 char prefix, ambiguous >=6 char prefix across 2 active cards, and SQL LIKE wildcards
        val w1 = original.copy(id = "wild12%3_a", title = "W1", sourceMessages = "W1 unique body for dedupe", isDismissed = false)
        val w2 = original.copy(id = "wild129999", title = "W2", sourceMessages = "W2 unique body for dedupe", isDismissed = false)
        val a1 = original.copy(id = "ambig12_one", title = "A1", sourceMessages = "A1 unique body for dedupe", isDismissed = false)
        val a2 = original.copy(id = "ambig12_two", title = "A2", sourceMessages = "A2 unique body for dedupe", isDismissed = false)
        eventCardStore.upsert(listOf(w1, w2, a1, a2))
        val wildcardAttack = original.copy(
            id = "wild-attack",
            title = "Wildcard Attempt",
            sourceMessages = "Wildcard attack text",
            status = com.wechatdigest.core.model.EventCardStatus.UPDATED,
            supersedesCardId = "wild12%",
            isDismissed = false
        )
        val wildcardRes = eventCardStore.applyUpdateOrInsertOrThrow(listOf(wildcardAttack))
        assertEquals(w1.id, wildcardRes.supersededCardIds.single(), "Escaped LIKE prefix 'wild12%' must match literal '%', never wild129999")

        val ambiguousAttack = wildcardAttack.copy(id = "ambig-attack", sourceMessages = "Ambig text", supersedesCardId = "ambig12")
        val shortAttack = wildcardAttack.copy(id = "short-attack", sourceMessages = "Short text", supersedesCardId = "ambig")
        val guardRes = eventCardStore.applyUpdateOrInsertOrThrow(listOf(ambiguousAttack, shortAttack))
        assertTrue(guardRes.supersededCardIds.isEmpty(), "Ambiguous or <6-char prefixes must fail closed without superseding")
    }

    @Test
    fun `migrates legacy pre-phase-3 event_cards table safely`() {
        val legacyFile = File(tempDir, "legacy_events_v2.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE event_cards (
                        id TEXT PRIMARY KEY,
                        dedupe_key TEXT UNIQUE,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        translated_summary TEXT NOT NULL,
                        sections TEXT DEFAULT '[]',
                        category TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        event_date TEXT,
                        action_items TEXT NOT NULL,
                        original_sender TEXT NOT NULL,
                        source_room_id TEXT NOT NULL,
                        source_room_name TEXT DEFAULT '',
                        source_messages TEXT DEFAULT '',
                        translated_source_messages TEXT DEFAULT '',
                        attachments TEXT DEFAULT '[]',
                        timestamp_seconds INTEGER NOT NULL,
                        is_read INTEGER DEFAULT 0,
                        is_starred INTEGER DEFAULT 0,
                        is_dismissed INTEGER DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "INSERT INTO event_cards VALUES ('leg-1', 'd1', 'Old Title', 'Sum', 'Sum', '[]', 'NOTICE', 'NORMAL', NULL, '[]', 'Prof', 'r@chatroom', 'Room', 'body', '', '[]', 1700000000, 0, 0, 0, 1700000000)"
                )
            }
        }
        val migratedDb = AppDatabase(legacyFile)
        val migratedStore = EventCardStore(migratedDb)
        val loaded = migratedStore.listActive().single()
        assertEquals("leg-1", loaded.id)
        assertEquals(com.wechatdigest.core.model.EventCardStatus.ACTIVE, loaded.status)
        assertTrue(loaded.sourceLocalIds.isEmpty())
        assertEquals("", loaded.originalSenderId)
    }

    @Test
    fun `updateCardFieldsOrThrow and replaceExtractedCardOrThrow preserve flags and attribution`() {
        val card = EventCard(
            id = "edit-card-1",
            title = "Draft Notice",
            summary = "Original summary",
            translatedSummary = "Original translated",
            category = EventCategory.NOTICE,
            priority = EventPriority.NORMAL,
            eventDate = "2026-12-01",
            originalSender = "Admin",
            originalSenderId = "wxid_admin",
            sourceRoomId = "dept@chatroom",
            sourceMessages = "[2026-11-20 09:00:00] Admin: Submit forms before Dec 1.",
            sourceLocalIds = listOf(55L),
            timestampSeconds = 1795000000L,
            isRead = true,
            isStarred = true
        )
        eventCardStore.upsert(listOf(card))

        val edited = eventCardStore.updateCardFieldsOrThrow(
            id = "edit-card-1",
            title = "Updated Form Deadline",
            category = EventCategory.DEADLINE,
            priority = EventPriority.CRITICAL,
            eventDate = "2026-12-03"
        )
        assertNotNull(edited)
        assertEquals("Updated Form Deadline", edited.title)
        assertEquals(EventCategory.DEADLINE, edited.category)
        assertEquals(EventPriority.CRITICAL, edited.priority)
        assertEquals("2026-12-03", edited.eventDate)
        assertTrue(edited.isRead)
        assertTrue(edited.isStarred)
        assertEquals("wxid_admin", edited.originalSenderId)
        assertEquals(listOf(55L), edited.sourceLocalIds)

        val reextracted = eventCardStore.replaceExtractedCardOrThrow(
            existingId = "edit-card-1",
            replacement = card.copy(
                title = "Re-extracted Thesis Notice",
                summary = "New summary",
                translatedSummary = "New translated",
                status = com.wechatdigest.core.model.EventCardStatus.CANCELLED
            )
        )
        assertNotNull(reextracted)
        assertEquals("Re-extracted Thesis Notice", reextracted.title)
        assertEquals(com.wechatdigest.core.model.EventCardStatus.CANCELLED, reextracted.status)
        assertTrue(reextracted.isRead)
        assertTrue(reextracted.isStarred)
        // Cancelled cards drop out of active fingerprint dedup set
        assertTrue(eventCardStore.listActiveFingerprints().isEmpty())
    }

    @Test
    fun `setCompleted and setCheckedItems persist and survive superseding updates`() {
        val original = EventCard(
            id = "chk-orig-1111",
            title = "Visa Renewal",
            summary = "Bring passport and I-20.",
            translatedSummary = "Bring passport and I-20.",
            category = EventCategory.DEADLINE,
            priority = EventPriority.CRITICAL,
            originalSender = "Intl Office",
            originalSenderId = "wxid_intl",
            sourceRoomId = "intl@chatroom",
            sourceMessages = "[2026-11-20 10:00:00] Intl Office: Submit visa renewal documents by Friday.",
            timestampSeconds = 1795000000L,
            isCompleted = false,
            checkedItems = listOf("0:0:123456")
        )
        eventCardStore.upsert(listOf(original))

        eventCardStore.setCompleted("chk-orig-1111", true)
        eventCardStore.setCheckedItems("chk-orig-1111", listOf("0:0:123456", "0:1:654321"))

        val loaded = eventCardStore.listActive().single { it.id == "chk-orig-1111" }
        assertTrue(loaded.isCompleted)
        assertEquals(listOf("0:0:123456", "0:1:654321"), loaded.checkedItems)

        // An authorized update superseding this card preserves checkedItems & isCompleted
        val updateCard = original.copy(
            id = "chk-upd-2222",
            title = "Visa Renewal Extended",
            summary = "Extended until next Tuesday.",
            translatedSummary = "Extended until next Tuesday.",
            timestampSeconds = 1795000500L,
            status = com.wechatdigest.core.model.EventCardStatus.UPDATED,
            supersedesCardId = "chk-orig",
            isCompleted = false,
            checkedItems = emptyList()
        )
        val outcome = eventCardStore.applyUpdateOrInsertOrThrow(listOf(updateCard))
        assertEquals(listOf("chk-orig-1111"), outcome.supersededCardIds)
        val active = eventCardStore.listActive().single { it.id == "chk-upd-2222" }
        assertTrue(active.isCompleted)
        assertEquals(listOf("0:0:123456", "0:1:654321"), active.checkedItems)

        // Updating sections prunes orphaned checklist keys
        eventCardStore.updateSectionsOrThrow(
            "chk-upd-2222",
            listOf(
                com.wechatdigest.core.model.EventSection(
                    heading = "Docs",
                    items = listOf(
                        com.wechatdigest.core.model.EventDetailItem(
                            kind = com.wechatdigest.core.model.EventDetailKind.DOCUMENT,
                            label = "Passport",
                            value = "Valid 6 months"
                        )
                    )
                )
            )
        )
        assertTrue(eventCardStore.listActive().single { it.id == "chk-upd-2222" }.checkedItems.isEmpty())

        // SettingsStore clamps out-of-range messagesPerSync on load
        settingsStore.save(com.wechatdigest.app.AppSettings(messagesPerSync = 999))
        assertEquals(500, settingsStore.load().messagesPerSync)
        settingsStore.save(com.wechatdigest.app.AppSettings(messagesPerSync = -5))
        assertEquals(1, settingsStore.load().messagesPerSync)
    }
}
