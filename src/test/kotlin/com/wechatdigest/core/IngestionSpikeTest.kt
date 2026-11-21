package com.wechatdigest.core

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.decompress.ZstdDecompressor
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IngestionSpikeTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun testRoomIdMd5Calculation() {
        val roomId = "10000000001@chatroom"
        val actualTable = WeChatDatabaseDriver.calculateRoomTableName(roomId)
        assertTrue(actualTable.startsWith("Msg_"))
        assertEquals(36, actualTable.length)
        assertEquals(actualTable, WeChatDatabaseDriver.calculateRoomTableName(roomId))
    }

    @Test
    fun testZstdDecompressorPlainText() {
        val sampleText = "Hello from WeChat Digest!"
        val bytes = sampleText.toByteArray(Charsets.UTF_8)
        assertFalse(ZstdDecompressor.isZstdCompressed(bytes))
        val result = ZstdDecompressor.decompressToString(bytes)
        assertEquals(sampleText, result)
    }

    @Test
    fun testZstdDecompressorMagicBytesDetection() {
        val fakeZstd = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte(), 0x01, 0x02)
        assertTrue(ZstdDecompressor.isZstdCompressed(fakeZstd))

        val notZstd = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertFalse(ZstdDecompressor.isZstdCompressed(notZstd))
    }

    @Test
    fun testSyntheticDatabaseIngestionPipeline() {
        val roomId = "10000000001@chatroom"
        val tableName = WeChatDatabaseDriver.calculateRoomTableName(roomId)

        val contactDb = File(tempDir, "contact.db")
        DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('$roomId', 'Graduate Study Group', '')")
                stmt.execute("INSERT INTO contact VALUES ('wxid_prof_lin', 'Prof_Lin', '')")
            }
        }

        val messageDb = File(tempDir, "message_0.db")
        DriverManager.getConnection("jdbc:sqlite:${messageDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE $tableName (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)"
                )
                stmt.execute(
                    "INSERT INTO $tableName VALUES (101, 1790000000, 'wxid_prof_lin', CAST('wxid_prof_lin:\nSubmit final report before Friday 17:00.' AS BLOB))"
                )
            }
        }

        val driver = WeChatDatabaseDriver(tempDir)
        val contactRepo = WeChatContactRepository(driver)
        val messageRepo = WeChatMessageRepository(driver, contactRepo)

        val group = contactRepo.findGroupByName("Graduate Study Group")
        assertNotNull(group)
        assertEquals(roomId, group.roomId)

        val shard = messageRepo.findDatabaseShardForRoom(group.roomId)
        assertNotNull(shard)
        assertEquals("message_0.db", shard.name)

        val messages = messageRepo.getRecentMessages(group.roomId, limit = 15)
        assertEquals(1, messages.size)
        assertEquals("Prof_Lin", messages.single().senderDisplayName)
        assertTrue(messages.single().textContent.contains("Submit final report"))
    }
}
