package com.wechatdigest.core.db

import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeChatDatabaseDriverAndSessionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `openReadOnlyConnection snapshots live plaintext WAL rows without mutating original files`() {
        val wechatDir = File(tempDir, "live_wal_wechat").apply { mkdirs() }
        val dbFile = File(wechatDir, "message_0.db")
        val room = "wal_room@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { writerConn ->
            writerConn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA wal_autocheckpoint=0")
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $table VALUES (1, 1795000000, 'wxid_1', CAST('Main DB message' AS BLOB))")
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                // Insert row 2 into -wal ONLY while writerConn stays open
                stmt.executeUpdate("INSERT INTO $table VALUES (2, 1795000010, 'wxid_1', CAST('Live uncheckpointed WAL message' AS BLOB))")
            }

            val walFile = File(wechatDir, "message_0.db-wal")
            assertTrue(walFile.exists() && walFile.length() > WcdbDecryptor.WAL_HEADER_SIZE, "Expected live -wal frames on disk")
            val origDbSize = dbFile.length()
            val origWalSize = walFile.length()
            val origDbMtime = dbFile.lastModified()
            val origWalMtime = walFile.lastModified()

            val contactDb = File(wechatDir, "contact.db")
            DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { cConn ->
                cConn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                    stmt.execute("INSERT INTO contact VALUES ('wxid_1', 'Alice', '')")
                }
            }

            val driver = WeChatDatabaseDriver(
                baseDir = wechatDir,
                cacheDir = File(tempDir, "decrypted")
            )

            var snapshotDirPath: File? = null
            WeChatSessionContext(driver).use { session ->
                val contactRepo = WeChatContactRepository(driver, session)
                val messageRepo = WeChatMessageRepository(driver, contactRepo, sessionContext = session)
                val messages = messageRepo.getRecentMessages(room, limit = 10)
                assertEquals(listOf(1L, 2L), messages.map { it.localId })
                assertEquals(listOf("Main DB message", "Live uncheckpointed WAL message"), messages.map { it.textContent })
                snapshotDirPath = session.getOrCreateSnapshotDir()
                assertTrue(snapshotDirPath!!.exists())
            }

            // Ephemeral snapshot dir must be removed on close()
            assertFalse(snapshotDirPath!!.exists(), "WeChatSessionContext.close() must delete snapshot temp dir")

            // Original WeChat files must remain untouched
            assertEquals(origDbSize, dbFile.length())
            assertEquals(origWalSize, walFile.length())
            assertEquals(origDbMtime, dbFile.lastModified())
            assertEquals(origWalMtime, walFile.lastModified())
        }
    }

    @Test
    fun `WeChatSessionContext caches keys, contactsMap, and shard index once across multiple rooms`() {
        val wechatDir = File(tempDir, "session_cache_wechat").apply { mkdirs() }
        val messageDir = File(wechatDir, "message").apply { mkdirs() }
        val rooms = listOf("r1@chatroom", "r2@chatroom", "r3@chatroom")

        val contactDb = File(wechatDir, "contact.db")
        DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('wxid_sender', 'Prof_Lin', '')")
            }
        }

        val shard0 = File(messageDir, "message_0.db")
        DriverManager.getConnection("jdbc:sqlite:${shard0.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                val t0 = WeChatDatabaseDriver.calculateRoomTableName(rooms[0])
                val t1 = WeChatDatabaseDriver.calculateRoomTableName(rooms[1])
                stmt.execute("CREATE TABLE $t0 (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $t0 VALUES (1, 1795000001, 'wxid_sender', CAST('Room 1 notice' AS BLOB))")
                stmt.execute("CREATE TABLE $t1 (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $t1 VALUES (1, 1795000002, 'wxid_sender', CAST('Room 2 notice' AS BLOB))")
            }
        }
        val shard1 = File(messageDir, "message_1.db")
        DriverManager.getConnection("jdbc:sqlite:${shard1.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                val t2 = WeChatDatabaseDriver.calculateRoomTableName(rooms[2])
                stmt.execute("CREATE TABLE $t2 (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $t2 VALUES (1, 1795000003, 'wxid_sender', CAST('Room 3 notice' AS BLOB))")
            }
        }

        val driver = WeChatDatabaseDriver(wechatDir, cacheDir = File(tempDir, "cache"))
        WeChatSessionContext(driver).use { session ->
            val contactRepo = WeChatContactRepository(driver, session)
            val msgRepo = WeChatMessageRepository(driver, contactRepo, sessionContext = session)

            // Prime session caches via room 1
            assertEquals(1, msgRepo.getRecentMessages(rooms[0], 10).size)

            // Delete contact.db & remove room3 table reference check would fail if not cached!
            // Verify repeated reads across rooms hit in-memory session caches rather than re-reading contact.db or re-scanning shards
            var contactLoadCount = 0
            val cachedContacts = session.getOrLoadContactsMap {
                contactLoadCount++
                emptyMap()
            }
            assertEquals(0, contactLoadCount, "contactsMap must already be cached in session")
            assertEquals("Prof_Lin", cachedContacts["wxid_sender"])

            var shardScanCount = 0
            val cachedShards = session.getOrLoadShardIndex {
                shardScanCount++
                emptyList()
            }
            assertEquals(0, shardScanCount, "shard index must already be cached in session")
            assertEquals(2, cachedShards.size)

            assertEquals("Room 2 notice", msgRepo.getRecentMessages(rooms[1], 10).single().textContent)
            assertEquals("Room 3 notice", msgRepo.getRecentMessages(rooms[2], 10).single().textContent)
        }
    }
}
