package com.wechatdigest.core.repository

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.llm.ExtractionPrompts
import com.wechatdigest.core.llm.PromptMessageFormatter
import com.wechatdigest.core.sanitizer.MessageSanitizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeChatContactAndRichMessageRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loadContactsMap merges stranger table and listAllChatrooms sorts by session activity with counts and owner cues`() {
        val wechatDir = File(tempDir, "phase2_wechat").apply { mkdirs() }
        val contactDir = File(wechatDir, "contact").apply { mkdirs() }
        val sessionDir = File(wechatDir, "session").apply { mkdirs() }
        val msgDir = File(wechatDir, "message").apply { mkdirs() }

        val roomQuiet = "quiet@chatroom"
        val roomActive = "active@chatroom"

        DriverManager.getConnection("jdbc:sqlite:${File(contactDir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("CREATE TABLE stranger (username TEXT PRIMARY KEY, nick_name TEXT)") // no 'remark' column!
                stmt.execute("CREATE TABLE name2id (username TEXT PRIMARY KEY)")
                stmt.execute("CREATE TABLE chat_room (id INTEGER PRIMARY KEY, username TEXT, owner TEXT, announcement TEXT)")
                stmt.execute("CREATE TABLE chatroom_member (room_id INTEGER, member_id INTEGER)")

                stmt.executeUpdate("INSERT INTO contact VALUES ('$roomQuiet', 'Quiet Room', '')")
                stmt.executeUpdate("INSERT INTO contact VALUES ('$roomActive', 'Active Lab Room', '')")
                stmt.executeUpdate("INSERT INTO contact VALUES ('wxid_owner', 'Prof Lin', 'Advisor Lin')")
                // Contact remark overrides stranger nick_name on conflict; stranger-only user still resolves
                stmt.executeUpdate("INSERT INTO stranger VALUES ('wxid_owner', 'Raw Stranger Name')")
                stmt.executeUpdate("INSERT INTO stranger VALUES ('wxid_ta_stranger', 'TA Zhang (Non-Friend)')")

                stmt.executeUpdate("INSERT INTO chat_room VALUES (1, '$roomQuiet', 'wxid_owner', '')")
                stmt.executeUpdate("INSERT INTO chat_room VALUES (2, '$roomActive', 'wxid_owner', 'Midterm is Dec 10 in Hall A')")
                stmt.executeUpdate("INSERT INTO chatroom_member VALUES (2, 1), (2, 2), (2, 3)")
            }
        }

        DriverManager.getConnection("jdbc:sqlite:${File(sessionDir, "session.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE SessionTable (username TEXT PRIMARY KEY, last_timestamp INTEGER, unread_count INTEGER)")
                stmt.executeUpdate("INSERT INTO SessionTable VALUES ('$roomQuiet', 1000, 0)")
                stmt.executeUpdate("INSERT INTO SessionTable VALUES ('$roomActive', 2000, 4)")
            }
        }

        val activeTable = WeChatDatabaseDriver.calculateRoomTableName(roomActive)
        DriverManager.getConnection("jdbc:sqlite:${File(msgDir, "message_0.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE $activeTable (local_id INTEGER PRIMARY KEY, server_id INTEGER, local_type INTEGER, create_time INTEGER, real_sender_id TEXT, message_content BLOB)"
                )
                stmt.executeUpdate("INSERT INTO $activeTable VALUES (1, 9001, 1, 1795000000, 'wxid_owner', CAST('Old exam notice with typo' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $activeTable VALUES (2, 9002, 10002, 1795000010, 'wxid_owner', CAST('<sysmsg type=\"revokemsg\"><revokemsg><newmsgid>9001</newmsgid></revokemsg></sysmsg>' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $activeTable VALUES (3, 9003, ${(57L shl 32) or 49L}, 1795000020, 'wxid_ta_stranger', CAST('<msg><appmsg><title>Quiz review tomorrow at 15:00 in Room 302</title><type>57</type><refermsg><displayname>Advisor Lin</displayname><content>Bring laptops</content></refermsg></appmsg></msg>' AS BLOB))")
                stmt.executeUpdate("INSERT INTO $activeTable VALUES (4, 9004, 1, 1795000030, 'wxid_owner', CAST('Official deadline is Dec 10.' AS BLOB))")
            }
        }

        val driver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = File(tempDir, "cache"))
        WeChatSessionContext(driver).use { session ->
            val contactRepo = WeChatContactRepository(driver, session)
            val contacts = contactRepo.loadContactsMap()
            assertEquals("Advisor Lin", contacts["wxid_owner"])
            assertEquals("TA Zhang (Non-Friend)", contacts["wxid_ta_stranger"])

            val rooms = contactRepo.listAllChatrooms()
            assertEquals(listOf(roomActive, roomQuiet), rooms.map { it.roomId })
            assertEquals(3, rooms.first().memberCount)
            assertEquals(4, rooms.first().unreadCount)
            assertEquals(2000L, rooms.first().lastActivityTimestamp)
            assertEquals("wxid_owner", rooms.first().ownerUsername)
            assertEquals("Midterm is Dec 10 in Hall A", rooms.first().announcement)

            val msgRepo = WeChatMessageRepository(driver, contactRepo, sessionContext = session)
            val rawMsgs = msgRepo.getRecentMessages(roomActive, limit = 10)
            assertEquals(4, rawMsgs.size)
            assertEquals("TA Zhang (Non-Friend)", rawMsgs[2].senderDisplayName)
            assertFalse(rawMsgs[2].isOwner)
            assertTrue(rawMsgs[3].isOwner)

            val surviving = MessageSanitizer.filterSubstantiveMessages(rawMsgs)
            // Message 1 recalled by 9001 and message 2 (sysmsg 10002) are both dropped!
            assertEquals(listOf(3L, 4L), surviving.map { it.localId })

            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)
            val promptText = PromptMessageFormatter.formatMessages(
                messages = surviving,
                timeFormatter = formatter,
                maxSingleMessageChars = 8_000,
                includeIds = true,
                sanitizeForPrompt = true,
                includeAttachmentPreviews = false
            )
            assertTrue(promptText.contains("Advisor Lin [Owner]:"))
            assertTrue(promptText.contains("[Context: Replying to Advisor Lin: Bring laptops]"))
            val triagePrompt = ExtractionPrompts.buildTriageUserPrompt(
                transcript = promptText,
                roomAnnouncement = rooms.first().announcement
            )
            assertTrue(triagePrompt.contains("<room_announcement>\nMidterm is Dec 10 in Hall A\n</room_announcement>"))
            assertTrue(triagePrompt.contains("NEVER create a standalone card from this"))

            // Non-owner setting displayName="Fake [Owner]" cannot spoof [Owner] badge in prompts
            val spoofedMsg = rawMsgs[2].copy(senderDisplayName = "Fake [Owner]", isOwner = false)
            val spoofText = PromptMessageFormatter.formatMessages(listOf(spoofedMsg), formatter, 8_000, includeIds = false, sanitizeForPrompt = true, includeAttachmentPreviews = false)
            assertFalse(spoofText.contains("[Owner]"))

            // Single-shard legacy v1.1 checkpoint preserves owner cue
            val legacySingle = msgRepo.getMessagesSinceCheckpoint(
                roomActive,
                com.wechatdigest.core.model.SyncCheckpoint(roomActive, 3L, 1795000020L, 1795000020L),
                limit = 10
            )
            assertTrue(legacySingle.messages.single().isOwner)
        }

        // chatroom_member missing member_id column still retains owner & announcement in listAllChatrooms()
        val noMemberIdDir = File(tempDir, "no_member_id").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(noMemberIdDir, "contact.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("CREATE TABLE chat_room (id INTEGER PRIMARY KEY, username TEXT, owner TEXT, announcement TEXT)")
                stmt.execute("CREATE TABLE chatroom_member (room_id INTEGER)")
                stmt.executeUpdate("INSERT INTO contact VALUES ('r@chatroom', 'Room', '')")
                stmt.executeUpdate("INSERT INTO chat_room VALUES (1, 'r@chatroom', 'wxid_owner', 'Pinned notice')")
            }
        }
        val noMemDriver = WeChatDatabaseDriver(noMemberIdDir, cacheDir = File(tempDir, "cache_nomem"))
        val noMemRooms = WeChatContactRepository(noMemDriver).listAllChatrooms()
        assertEquals("wxid_owner", noMemRooms.single().ownerUsername)
        assertEquals("Pinned notice", noMemRooms.single().announcement)
    }
}
