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

    @Test
    fun `no-session plaintext WAL snapshot hits cache, refreshes on WAL-only growth, and falls back on corrupt WAL`() {
        val wechatDir = File(tempDir, "nosession_wal_wechat").apply { mkdirs() }
        val dbFile = File(wechatDir, "message_0.db")
        val room = "nosession_wal@chatroom"
        val table = WeChatDatabaseDriver.calculateRoomTableName(room)
        val cacheDir = File(tempDir, "nosession_cache")

        val contactDb = File(wechatDir, "contact.db")
        DriverManager.getConnection("jdbc:sqlite:${contactDb.absolutePath}").use { cConn ->
            cConn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE contact (username TEXT PRIMARY KEY, nick_name TEXT, remark TEXT)")
                stmt.execute("INSERT INTO contact VALUES ('wxid_1', 'Alice', '')")
            }
        }

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { writerConn ->
            writerConn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA wal_autocheckpoint=0")
                stmt.execute("CREATE TABLE $table (local_id INTEGER PRIMARY KEY, create_time INTEGER, real_sender_id TEXT, message_content BLOB)")
                stmt.executeUpdate("INSERT INTO $table VALUES (1, 1795000000, 'wxid_1', CAST('Committed baseline row' AS BLOB))")
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                stmt.executeUpdate("INSERT INTO $table VALUES (2, 1795000010, 'wxid_1', CAST('First live WAL row' AS BLOB))")
            }

            val walFile = File(wechatDir, "message_0.db-wal")
            assertTrue(walFile.length() > WcdbDecryptor.WAL_HEADER_SIZE)
            val frozenDbMtime = dbFile.lastModified()

            val driver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = cacheDir)
            val repo = WeChatMessageRepository(driver, WeChatContactRepository(driver))

            // 1. Cache miss -> creates persistent snapshot + .wal_state and reads both main + live WAL rows
            val firstRead = repo.getRecentMessages(room, limit = 10)
            assertEquals(listOf(1L, 2L), firstRead.map { it.localId })

            val walStateFile = cacheDir.walkTopDown().firstOrNull { it.isFile && it.name == ".message_0.db.wal_state" }
            val snapshotFile = cacheDir.walkTopDown().firstOrNull { it.isFile && it.name == "message_0.db" }
            assertTrue(walStateFile != null && walStateFile.exists(), "Expected persistent .wal_state marker")
            assertTrue(snapshotFile != null && snapshotFile.exists(), "Expected persistent snapshot DB file")
            val sentinelSnapshotMtime = maxOf(dbFile.lastModified(), walFile.lastModified()) + 10_000L
            assertTrue(snapshotFile.setLastModified(sentinelSnapshotMtime))

            // 2. Second read without DB or WAL changes must reuse cached snapshot without overwriting it
            val secondRead = repo.getRecentMessages(room, limit = 10)
            assertEquals(listOf(1L, 2L), secondRead.map { it.localId })
            assertEquals(sentinelSnapshotMtime, snapshotFile.lastModified(), "Cache hit must not rewrite snapshotFile")

            // 3. Grow -wal WITHOUT changing main.db mtime -> .wal_state mismatch invalidates cache and picks up row 3
            writerConn.createStatement().use { stmt ->
                stmt.executeUpdate("INSERT INTO $table VALUES (3, 1795000020, 'wxid_1', CAST('Second WAL row without main mtime bump' AS BLOB))")
            }
            dbFile.setLastModified(frozenDbMtime)
            walFile.setLastModified(walFile.lastModified() + 2000L)

            val thirdRead = repo.getRecentMessages(room, limit = 10)
            assertEquals(listOf(1L, 2L, 3L), thirdRead.map { it.localId })
            assertEquals("Second WAL row without main mtime bump", thirdRead.last().textContent)
        }

        // 4. Closing writerConn checkpoints rows 1..3 into main.db; writing corrupt -wal (> 32 bytes)
        // forces createPlaintextWalSnapshot's catch-fallback to read committed main.db cleanly without throwing.
        val corruptWal = File(wechatDir, "message_0.db-wal")
        corruptWal.writeBytes(ByteArray(WcdbDecryptor.WAL_HEADER_SIZE + 128) { 0x5A })
        corruptWal.setLastModified(System.currentTimeMillis() + 5000L)

        val fallbackDriver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = File(tempDir, "corrupt_wal_cache"))
        val fallbackRepo = WeChatMessageRepository(fallbackDriver, WeChatContactRepository(fallbackDriver))
        val fallbackRead = fallbackRepo.getRecentMessages(room, limit = 10)
        assertEquals(listOf(1L, 2L, 3L), fallbackRead.map { it.localId })
        assertEquals("Committed baseline row", fallbackRead.first().textContent)
    }

    @Test
    fun `resolveDatabaseFile rejects symlinked rawFile, cacheDir, and snapshotFile`() {
        val wechatDir = File(tempDir, "symlink_guard_wechat").apply { mkdirs() }
        val realDb = File(wechatDir, "real_message_0.db")
        DriverManager.getConnection("jdbc:sqlite:${realDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                stmt.executeUpdate("INSERT INTO t VALUES (1)")
            }
        }

        // Hold live WAL frames open so symlink checks run across all snapshot guards
        DriverManager.getConnection("jdbc:sqlite:${realDb.absolutePath}").use { writerConn ->
            writerConn.createStatement().use { stmt ->
                stmt.execute("PRAGMA wal_autocheckpoint=0")
                stmt.executeUpdate("INSERT INTO t VALUES (2)")
            }
            val realWal = File(wechatDir, "real_message_0.db-wal")
            assertTrue(realWal.length() > WcdbDecryptor.WAL_HEADER_SIZE)

            // 1. Symlinked rawFile with live -wal sibling
            val linkedDb = File(wechatDir, "linked_message_0.db")
            java.nio.file.Files.createSymbolicLink(linkedDb.toPath(), realDb.toPath())
            realWal.copyTo(File(wechatDir, "linked_message_0.db-wal"), overwrite = true)
            val normalDriver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = File(tempDir, "normal_cache"))
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                normalDriver.openReadOnlyConnection(linkedDb, sessionContext = null).close()
            }

            // 2. Symlinked cacheDir with active WAL
            val realCacheTarget = File(tempDir, "real_cache_target").apply { mkdirs() }
            val symlinkCache = File(tempDir, "symlink_cache")
            java.nio.file.Files.createSymbolicLink(symlinkCache.toPath(), realCacheTarget.toPath())
            val symlinkCacheDriver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = symlinkCache)
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                symlinkCacheDriver.openReadOnlyConnection(realDb, sessionContext = null).close()
            }

            // 3. Symlinked pre-existing snapshotFile inside valid cacheDir
            val goodCache = File(tempDir, "good_cache")
            val goodDriver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = goodCache)
            goodDriver.openReadOnlyConnection(realDb, sessionContext = null).close()

            val snapFile = goodCache.walkTopDown().first { it.isFile && it.name == "real_message_0.db" }
            assertTrue(snapFile.delete())
            java.nio.file.Files.createSymbolicLink(snapFile.toPath(), realDb.toPath())
            val walFile = File(wechatDir, "real_message_0.db-wal")
            walFile.setLastModified(walFile.lastModified() + 3000L)

            kotlin.test.assertFailsWith<IllegalArgumentException> {
                goodDriver.openReadOnlyConnection(realDb, sessionContext = null).close()
            }
        }

        // 4. Symlinked plaintext rawFile WITHOUT any -wal sibling is also rejected fail-closed
        val noWalLink = File(wechatDir, "no_wal_link.db")
        java.nio.file.Files.createSymbolicLink(noWalLink.toPath(), realDb.toPath())
        val noWalDriver = WeChatDatabaseDriver(baseDir = wechatDir, cacheDir = File(tempDir, "nowal_cache"))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            noWalDriver.openReadOnlyConnection(noWalLink, sessionContext = null).close()
        }
    }
}
