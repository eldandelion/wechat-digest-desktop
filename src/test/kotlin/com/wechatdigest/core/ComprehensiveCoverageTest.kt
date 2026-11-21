package com.wechatdigest.core

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.decompress.ZstdDecompressor
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.WeChatContact
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComprehensiveCoverageTest {

    @Test
    fun testWeChatContactDisplayNameBranches() {
        val contactWithRemark = WeChatContact("wxid_1", "Alice", "Alice Boss")
        assertEquals("Alice Boss", contactWithRemark.displayName)

        val contactWithNick = WeChatContact("wxid_2", "Bob", "")
        assertEquals("Bob", contactWithNick.displayName)

        val contactWithUser = WeChatContact("wxid_3", "", "   ")
        assertEquals("wxid_3", contactWithUser.displayName)
    }

    @Test
    fun testGroupChatAndWeChatMessageDataClasses() {
        val group = GroupChat("room@chatroom", "Family", memberCount = 10)
        assertEquals("room@chatroom", group.roomId)
        assertEquals("Family", group.displayName)
        assertEquals(10, group.memberCount)

        val copyGroup = group.copy(memberCount = 11)
        assertEquals(11, copyGroup.memberCount)
        assertTrue(group.toString().contains("Family"))

        val msg = WeChatMessage(
            localId = 1L,
            timestampSeconds = 1700000000L,
            senderId = "wxid_sender",
            senderDisplayName = "Sender Name",
            rawContent = "Raw content",
            textContent = "Text content",
            isZstdCompressed = false
        )
        assertEquals(1L, msg.localId)
        assertEquals(1700000000L, msg.timestampSeconds)
        assertEquals("wxid_sender", msg.senderId)
        assertEquals("Sender Name", msg.senderDisplayName)
        assertEquals("Raw content", msg.rawContent)
        assertEquals("Text content", msg.textContent)
        assertFalse(msg.isZstdCompressed)

        val copyMsg = msg.copy(isZstdCompressed = true)
        assertTrue(copyMsg.isZstdCompressed)
        assertTrue(msg.toString().contains("Sender Name"))
    }

    @Test
    fun testZstdDecompressorEdgeCases() {
        assertFalse(ZstdDecompressor.isZstdCompressed(byteArrayOf()))
        assertFalse(ZstdDecompressor.isZstdCompressed(byteArrayOf(0x28.toByte())))
        assertFalse(ZstdDecompressor.isZstdCompressed(byteArrayOf(0x28.toByte(), 0xB5.toByte())))
        assertFalse(ZstdDecompressor.isZstdCompressed(byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte())))

        val corruptedZstd = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertTrue(ZstdDecompressor.isZstdCompressed(corruptedZstd))
        val fallback = ZstdDecompressor.decompressToString(corruptedZstd)
        assertNotNull(fallback)
    }

    @Test
    fun testWeChatDatabaseDriverNonExistentFile(@TempDir tempDir: File) {
        val driver = WeChatDatabaseDriver(tempDir)
        val nonExistentDb = File(tempDir, "does_not_exist.db")
        assertFailsWith<FileNotFoundException> {
            driver.openReadOnlyConnection(nonExistentDb)
        }
    }

    @Test
    fun testContactRepositoryWithFlatAndMissingDirectory(@TempDir tempDir: File) {
        val driver = WeChatDatabaseDriver(tempDir)
        val repo = WeChatContactRepository(driver)

        assertFailsWith<FileNotFoundException> {
            repo.loadContactsMap()
        }

        val flatTarget = File(tempDir, "contact.db")
        DriverManager.getConnection("jdbc:sqlite:${flatTarget.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('10000000001@chatroom', 'Computer Science Notice Group', '')")
                stmt.execute("INSERT INTO contact VALUES ('wxid_prof_1', 'Prof. Turing', 'Dr. Turing')")
            }
        }

        val flatDriver = WeChatDatabaseDriver(tempDir)
        val flatRepo = WeChatContactRepository(flatDriver)
        val contacts = flatRepo.loadContactsMap()
        assertTrue(contacts.isNotEmpty(), "Flat contact.db should be loaded")

        val chatrooms = flatRepo.listAllChatrooms()
        assertEquals(1, chatrooms.size)

        val notFound = flatRepo.findGroupByName("NonExistentGroup123456789")
        assertNull(notFound, "Non-existent group should return null")
    }

    @Test
    fun testMessageRepositoryEdgeCasesAndShardMiss(@TempDir tempDir: File) {
        val driver = WeChatDatabaseDriver(tempDir)
        val contactRepo = WeChatContactRepository(driver)
        val msgRepo = WeChatMessageRepository(driver, contactRepo)

        val missingShard = msgRepo.findDatabaseShardForRoom("random_room@chatroom")
        assertNull(missingShard)

        assertFailsWith<FileNotFoundException> {
            msgRepo.getRecentMessages("random_room@chatroom")
        }
    }
}
