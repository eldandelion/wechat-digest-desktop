package com.wechatdigest.core.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WcdbDecryptorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `hexToBytes decodes valid hex strings`() {
        val hex = "0b251fc2"
        val bytes = WcdbDecryptor.hexToBytes(hex)
        assertEquals(4, bytes.size)
        assertEquals(0x0b.toByte(), bytes[0])
        assertEquals(0x25.toByte(), bytes[1])
        assertEquals(0x1f.toByte(), bytes[2])
        assertEquals(0xc2.toByte(), bytes[3])
    }

    @Test
    fun `isEncrypted correctly detects SQLite header`() {
        val plainDb = File(tempDir, "plain.db")
        plainDb.writeBytes("SQLite format 3\u0000SomeOtherData".toByteArray(Charsets.ISO_8859_1))
        assertFalse(WcdbDecryptor.isEncrypted(plainDb))

        val encryptedDb = File(tempDir, "encrypted.db")
        encryptedDb.writeBytes("RandomSaltAndData123456789".toByteArray(Charsets.ISO_8859_1))
        assertTrue(WcdbDecryptor.isEncrypted(encryptedDb))
    }

    @Test
    fun `findKeysFile locates keys file via customKeysFile and baseDir`() {
        val keysFile = File(tempDir, "all_keys.json").apply {
            writeText("""{"contact/contact.db":{"enc_key":"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"}}""")
        }

        val foundDirect = WcdbDecryptor.findKeysFile(customKeysFile = keysFile)
        assertNotNull(foundDirect)
        assertEquals(keysFile.absolutePath, foundDirect.absolutePath)

        val subDir = File(tempDir, "nested/db_storage").apply { mkdirs() }
        val foundParent = WcdbDecryptor.findKeysFile(baseDir = subDir)
        assertNotNull(foundParent)
        assertEquals(keysFile.absolutePath, foundParent.absolutePath)
    }

    @Test
    fun `loadKeys parses all_keys json and indexes full path and filename`() {
        val keyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val keysFile = File(tempDir, "all_keys.json").apply {
            writeText("""{"contact/contact.db":{"enc_key":"$keyHex"}}""")
        }

        val keys = WcdbDecryptor.loadKeys(keysFile)
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.containsKey("contact/contact.db"))
        assertTrue(keys.containsKey("contact.db"))
        assertEquals(32, keys["contact.db"]?.size)
    }

    @Test
    fun `decryptDatabase and resolveDecryptedFile decrypt synthetic encrypted SQLite pages`() {
        val rawKey = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 7).toByte() }
        val keySpec = SecretKeySpec(rawKey, "AES")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")

        // Build synthetic 4096-byte plaintext page 1 starting with SQLite header
        val plainPage1 = ByteArray(WcdbDecryptor.PAGE_SIZE) { (it % 97).toByte() }
        System.arraycopy(WcdbDecryptor.SQLITE_HEADER, 0, plainPage1, 0, 16)

        // Encrypt bytes [16 .. PAGE_SIZE - RESERVE_SIZE) into page 1 format
        val encPage1 = ByteArray(WcdbDecryptor.PAGE_SIZE)
        val encryptedLen1 = WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE - WcdbDecryptor.SALT_SIZE
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        val cipherBytes1 = cipher.doFinal(plainPage1, 16, encryptedLen1)
        System.arraycopy(ByteArray(16) { 0x5A.toByte() }, 0, encPage1, 0, 16) // 16-byte salt
        System.arraycopy(cipherBytes1, 0, encPage1, 16, encryptedLen1)
        System.arraycopy(iv, 0, encPage1, WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE, 16)

        val encryptedFile = File(tempDir, "contact.db").apply { writeBytes(encPage1) }
        assertTrue(WcdbDecryptor.isEncrypted(encryptedFile))

        val cacheDir = File(tempDir, "cache")
        val keysMap = mapOf("contact.db" to rawKey)
        val resolved = WcdbDecryptor.resolveDecryptedFile(encryptedFile, keysMap, cacheDir)

        assertTrue(resolved.exists())
        assertFalse(WcdbDecryptor.isEncrypted(resolved))
        assertContentEquals(
            plainPage1.copyOfRange(0, WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE),
            resolved.readBytes().copyOfRange(0, WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE)
        )

        // Missing key throws IllegalStateException
        assertFailsWith<IllegalStateException> {
            WcdbDecryptor.resolveDecryptedFile(encryptedFile, emptyMap(), cacheDir)
        }
    }

    @Test
    fun `resolveDecryptedFile replays committed LE and BE encrypted WAL frames, ignores uncommitted frames, and invalidates via wal_state`() {
        val rawKey = ByteArray(32) { (it + 3).toByte() }
        val keysMap = mapOf("notes.db" to rawKey)

        // Create base SQLite DB with 80-byte page reserve (matching SQLCipher 4 layout) + initial row
        val plainBase = File(tempDir, "plain_base.db")
        initSqlCipherReserveDb(plainBase)
        DriverManager.getConnection("jdbc:sqlite:${plainBase.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
                stmt.execute("INSERT INTO notes VALUES (1, 'initial-row')")
            }
        }
        val basePages = plainBase.readBytes()
        assertTrue(basePages.size >= WcdbDecryptor.PAGE_SIZE)

        // Create updated SQLite DB with a 2nd row (committed) + padding to span >1 page (tests pgno==1 and pgno>1)
        val plainCommitted = File(tempDir, "plain_committed.db").apply { writeBytes(basePages) }
        val longPad = "pad-".repeat(900)
        DriverManager.getConnection("jdbc:sqlite:${plainCommitted.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO notes VALUES (2, 'committed-wal-row')")
                stmt.execute("INSERT INTO notes VALUES (20, '$longPad')")
            }
        }
        val committedPages = plainCommitted.readBytes()
        assertTrue(committedPages.size >= WcdbDecryptor.PAGE_SIZE * 2, "Expected multi-page DB to cover pgno > 1 WAL frames")

        val plainUncommitted = File(tempDir, "plain_uncommitted.db").apply { writeBytes(committedPages) }
        DriverManager.getConnection("jdbc:sqlite:${plainUncommitted.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO notes VALUES (3, 'uncommitted-wal-row')")
            }
        }
        val uncommittedPages = plainUncommitted.readBytes()

        val salt1 = 0x11223344
        val salt2 = 0x55667788

        for (magic in listOf(WcdbDecryptor.WAL_MAGIC_LE, WcdbDecryptor.WAL_MAGIC_BE)) {
            val tag = if (magic == WcdbDecryptor.WAL_MAGIC_LE) "le" else "be"
            val acctRoot = File(tempDir, "acct_$tag").apply { mkdirs() }
            val cacheDir = File(tempDir, "cache_$tag")
            val encDb = File(acctRoot, "notes.db").apply {
                writeBytes(encryptSqliteDatabaseBytes(basePages, rawKey))
            }

            val walFile = File(acctRoot, "notes.db-wal")
            val committedCount = committedPages.size / WcdbDecryptor.PAGE_SIZE
            val committedFrames = (1..committedCount).map { pgno ->
                val pageSlice = committedPages.copyOfRange((pgno - 1) * WcdbDecryptor.PAGE_SIZE, pgno * WcdbDecryptor.PAGE_SIZE)
                val encPage = encryptPageForTest(pageSlice, pgno, rawKey, ivSeed = pgno + 10)
                val dbSizeAfter = if (pgno == committedCount) committedCount else 0
                Triple(pgno, dbSizeAfter, encPage)
            }
            val uncommittedPage1 = uncommittedPages.copyOfRange(0, WcdbDecryptor.PAGE_SIZE)
            val encUncommitted1 = encryptPageForTest(uncommittedPage1, 1, rawKey, ivSeed = 99)
            val allFrames = committedFrames + listOf(Triple(1, 0, encUncommitted1))

            walFile.writeBytes(buildSyntheticWal(magic, salt1, salt2, allFrames))

            val resolved = WcdbDecryptor.resolveDecryptedFile(encDb, keysMap, cacheDir, sourceRoot = acctRoot)
            val rows = queryNotesRows(resolved)
            assertEquals(listOf(1 to "initial-row", 2 to "committed-wal-row", 20 to longPad), rows, "WAL ($tag) should commit rows 2 & 20 and ignore uncommitted row 3")

            // Update WAL while keeping main.db mtime unchanged -> .wal_state must invalidate cache
            val mainMtimeBefore = encDb.lastModified()
            val plainCommitted2 = File(tempDir, "plain_committed2_$tag.db").apply { writeBytes(committedPages) }
            DriverManager.getConnection("jdbc:sqlite:${plainCommitted2.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO notes VALUES (4, 'second-wal-commit')")
                }
            }
            val committed2Pages = plainCommitted2.readBytes()
            val count2 = committed2Pages.size / WcdbDecryptor.PAGE_SIZE
            val frames2 = (1..count2).map { pgno ->
                val slice = committed2Pages.copyOfRange((pgno - 1) * WcdbDecryptor.PAGE_SIZE, pgno * WcdbDecryptor.PAGE_SIZE)
                Triple(pgno, if (pgno == count2) count2 else 0, encryptPageForTest(slice, pgno, rawKey, ivSeed = pgno + 50))
            }
            walFile.writeBytes(buildSyntheticWal(magic, salt1, salt2 + 1, frames2))
            walFile.setLastModified(System.currentTimeMillis() + 2_000L)
            encDb.setLastModified(mainMtimeBefore)

            val refreshed = WcdbDecryptor.resolveDecryptedFile(encDb, keysMap, cacheDir, sourceRoot = acctRoot)
            val refreshedRows = queryNotesRows(refreshed)
            assertTrue(refreshedRows.contains(4 to "second-wal-commit"), "WAL change must invalidate decrypted cache even when main.db mtime is unchanged")
        }
    }

    @Test
    fun `resolveDecryptedFile falls back to main db when encrypted WAL is corrupt or empty`() {
        val rawKey = ByteArray(32) { (it + 5).toByte() }
        val plainBase = File(tempDir, "corrupt_plain.db")
        initSqlCipherReserveDb(plainBase)
        DriverManager.getConnection("jdbc:sqlite:${plainBase.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
                stmt.execute("INSERT INTO notes VALUES (1, 'base-only')")
            }
        }
        val acctDir = File(tempDir, "corrupt_acct").apply { mkdirs() }
        val encDb = File(acctDir, "notes.db").apply {
            writeBytes(encryptSqliteDatabaseBytes(plainBase.readBytes(), rawKey))
        }
        val walFile = File(acctDir, "notes.db-wal")
        walFile.writeBytes(ByteArray(128) { 0x7F.toByte() }) // invalid WAL magic/checksum

        val resolved = WcdbDecryptor.resolveDecryptedFile(encDb, mapOf("notes.db" to rawKey), File(tempDir, "cache_corrupt"), sourceRoot = acctDir)
        assertEquals(listOf(1 to "base-only"), queryNotesRows(resolved))

        // Empty <= 32 byte WAL also resolves cleanly
        walFile.writeBytes(ByteArray(16))
        walFile.setLastModified(System.currentTimeMillis() + 2_000L)
        val resolvedEmptyWal = WcdbDecryptor.resolveDecryptedFile(encDb, mapOf("notes.db" to rawKey), File(tempDir, "cache_corrupt"), sourceRoot = acctDir)
        assertEquals(listOf(1 to "base-only"), queryNotesRows(resolvedEmptyWal))
    }

    @Test
    fun `resolveDecryptedFile isolates cache directories across accounts and rejects symlinks`() {
        val keyA = ByteArray(32) { 0x11.toByte() }
        val keyB = ByteArray(32) { 0x22.toByte() }

        val acctA = File(tempDir, "acctA").apply { mkdirs() }
        val acctB = File(tempDir, "acctB").apply { mkdirs() }
        val dbA = File(acctA, "contact/contact.db").apply { parentFile.mkdirs() }
        val dbB = File(acctB, "contact/contact.db").apply { parentFile.mkdirs() }

        val plainA = File(tempDir, "plainA.db")
        initSqlCipherReserveDb(plainA)
        DriverManager.getConnection("jdbc:sqlite:${plainA.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE who (name TEXT)")
                stmt.execute("INSERT INTO who VALUES ('AccountA')")
            }
        }
        val plainB = File(tempDir, "plainB.db")
        initSqlCipherReserveDb(plainB)
        DriverManager.getConnection("jdbc:sqlite:${plainB.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE who (name TEXT)")
                stmt.execute("INSERT INTO who VALUES ('AccountB')")
            }
        }
        dbA.writeBytes(encryptSqliteDatabaseBytes(plainA.readBytes(), keyA))
        dbB.writeBytes(encryptSqliteDatabaseBytes(plainB.readBytes(), keyB))

        val sharedCacheDir = File(tempDir, "decrypted")
        val resolvedA = WcdbDecryptor.resolveDecryptedFile(dbA, mapOf("contact/contact.db" to keyA), sharedCacheDir, sourceRoot = acctA)
        val resolvedB = WcdbDecryptor.resolveDecryptedFile(dbB, mapOf("contact/contact.db" to keyB), sharedCacheDir, sourceRoot = acctB)

        assertNotEquals(resolvedA.canonicalPath, resolvedB.canonicalPath)
        DriverManager.getConnection("jdbc:sqlite:${resolvedA.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM who").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("AccountA", rs.getString(1))
                }
            }
        }
        DriverManager.getConnection("jdbc:sqlite:${resolvedB.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM who").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("AccountB", rs.getString(1))
                }
            }
        }

        // Symlinked all_keys.json is rejected (loadKeys returns emptyMap)
        val realKeys = File(tempDir, "real_keys.json").apply {
            writeText("""{"contact/contact.db":{"enc_key":"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"}}""")
        }
        val linkKeys = File(tempDir, "link_keys.json")
        Files.createSymbolicLink(linkKeys.toPath(), realKeys.toPath())
        assertTrue(WcdbDecryptor.loadKeys(linkKeys).isEmpty())

        // Symlinked cacheDir is rejected fail-closed
        val realCache = File(tempDir, "real_cache").apply { mkdirs() }
        val linkCache = File(tempDir, "link_cache")
        Files.createSymbolicLink(linkCache.toPath(), realCache.toPath())
        assertFailsWith<IllegalArgumentException> {
            WcdbDecryptor.resolveDecryptedFile(dbA, mapOf("contact/contact.db" to keyA), linkCache, sourceRoot = acctA)
        }
    }

    private fun initSqlCipherReserveDb(dbFile: File) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA page_size=4096")
                stmt.execute("CREATE TABLE __init_reserve (id INTEGER)")
                stmt.execute("DROP TABLE __init_reserve")
            }
        }
        val bytes = dbFile.readBytes()
        val usableSize = WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE // 4016
        bytes[20] = WcdbDecryptor.RESERVE_SIZE.toByte()
        bytes[105] = ((usableSize ushr 8) and 0xFF).toByte()
        bytes[106] = (usableSize and 0xFF).toByte()
        dbFile.writeBytes(bytes)
    }

    private fun queryNotesRows(dbFile: File): List<Pair<Int, String>> {
        val rows = mutableListOf<Pair<Int, String>>()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, body FROM notes ORDER BY id ASC").use { rs ->
                    while (rs.next()) {
                        rows.add(rs.getInt(1) to rs.getString(2))
                    }
                }
            }
        }
        return rows
    }

    private fun encryptSqliteDatabaseBytes(plainBytes: ByteArray, rawKey: ByteArray): ByteArray {
        val pageCount = plainBytes.size / WcdbDecryptor.PAGE_SIZE
        val out = ByteArrayOutputStream(plainBytes.size)
        for (pgno in 1..pageCount) {
            val pageSlice = plainBytes.copyOfRange((pgno - 1) * WcdbDecryptor.PAGE_SIZE, pgno * WcdbDecryptor.PAGE_SIZE)
            out.write(encryptPageForTest(pageSlice, pgno, rawKey, ivSeed = pgno))
        }
        return out.toByteArray()
    }

    private fun encryptPageForTest(plainPage: ByteArray, pgno: Int, rawKey: ByteArray, ivSeed: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(rawKey, "AES")
        val iv = ByteArray(WcdbDecryptor.IV_SIZE) { (it + ivSeed).toByte() }
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        val encPage = ByteArray(WcdbDecryptor.PAGE_SIZE)
        if (pgno == 1) {
            val len = WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE - WcdbDecryptor.SALT_SIZE
            val cipherBytes = cipher.doFinal(plainPage, 16, len)
            System.arraycopy(ByteArray(16) { 0x5A.toByte() }, 0, encPage, 0, 16)
            System.arraycopy(cipherBytes, 0, encPage, 16, len)
        } else {
            val len = WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE
            val cipherBytes = cipher.doFinal(plainPage, 0, len)
            System.arraycopy(cipherBytes, 0, encPage, 0, len)
        }
        System.arraycopy(iv, 0, encPage, WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.RESERVE_SIZE, WcdbDecryptor.IV_SIZE)
        return encPage
    }

    private fun buildSyntheticWal(
        magic: Int,
        salt1: Int,
        salt2: Int,
        frames: List<Triple<Int, Int, ByteArray>>
    ): ByteArray {
        val wordOrder = if (magic == WcdbDecryptor.WAL_MAGIC_LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val header = ByteArray(WcdbDecryptor.WAL_HEADER_SIZE)
        val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        hdrBuf.putInt(0, magic)
        hdrBuf.putInt(4, WcdbDecryptor.WAL_FORMAT)
        hdrBuf.putInt(8, WcdbDecryptor.PAGE_SIZE)
        hdrBuf.putInt(12, 1)
        hdrBuf.putInt(16, salt1)
        hdrBuf.putInt(20, salt2)
        val (h1, h2) = WcdbDecryptor.computeWalChecksum(header, 0, 24, wordOrder, 0, 0)
        hdrBuf.putInt(24, h1)
        hdrBuf.putInt(28, h2)

        val out = ByteArrayOutputStream()
        out.write(header)

        var s1 = h1
        var s2 = h2
        for ((pgno, dbSizeAfterCommit, pageBytes) in frames) {
            val fHdr = ByteArray(WcdbDecryptor.WAL_FRAME_HEADER_SIZE)
            val fBuf = ByteBuffer.wrap(fHdr).order(ByteOrder.BIG_ENDIAN)
            fBuf.putInt(0, pgno)
            fBuf.putInt(4, dbSizeAfterCommit)
            fBuf.putInt(8, salt1)
            fBuf.putInt(12, salt2)
            val (a1, a2) = WcdbDecryptor.computeWalChecksum(fHdr, 0, 8, wordOrder, s1, s2)
            val (f1, f2) = WcdbDecryptor.computeWalChecksum(pageBytes, 0, WcdbDecryptor.PAGE_SIZE, wordOrder, a1, a2)
            s1 = f1
            s2 = f2
            fBuf.putInt(16, f1)
            fBuf.putInt(20, f2)
            out.write(fHdr)
            out.write(pageBytes)
        }
        return out.toByteArray()
    }

    @Test
    fun `WAL replay rejects forged multi-TB dbSizeAfterCommit, prunes shrunk pages on regrow, and loadKeys avoids leaking JSON snippets`() {
        val encKey = ByteArray(WcdbDecryptor.KEY_SIZE) { (it + 11).toByte() }
        val mainPlain = ByteArray(WcdbDecryptor.PAGE_SIZE * 2) { 0x11.toByte() }
        System.arraycopy(WcdbDecryptor.SQLITE_HEADER, 0, mainPlain, 0, 16)
        val mainFile = File(tempDir, "message_0.db").apply { writeBytes(mainPlain) }

        val forgedWalFile = File(tempDir, "forged.wal").apply {
            writeBytes(
                buildSyntheticWal(
                    magic = WcdbDecryptor.WAL_MAGIC_LE,
                    salt1 = 10,
                    salt2 = 20,
                    frames = listOf(
                        Triple(2, -1 /* 0xFFFFFFFF unsigned */, encryptPageForTest(ByteArray(WcdbDecryptor.PAGE_SIZE), pgno = 2, rawKey = encKey, ivSeed = 9))
                    )
                )
            )
        }
        val forgedTarget = File(tempDir, "out_forged.db").apply { writeBytes(mainPlain) }
        assertFalse(WcdbDecryptor.replayEncryptedWalIfValid(forgedTarget, forgedWalFile, encKey))
        assertEquals(mainPlain.size.toLong(), forgedTarget.length())

        // Shrink from 3 pages -> 2 pages -> regrow to 3 pages without rewriting page 3:
        // stale pre-shrink page 3 must NOT resurrect into the output!
        val page3BeforeShrink = ByteArray(WcdbDecryptor.PAGE_SIZE) { 0x77.toByte() }
        val page2Shrink = ByteArray(WcdbDecryptor.PAGE_SIZE) { 0x22.toByte() }
        val page1Final = ByteArray(WcdbDecryptor.PAGE_SIZE) { 0x33.toByte() }.also {
            System.arraycopy(WcdbDecryptor.SQLITE_HEADER, 0, it, 0, 16)
        }
        val shrinkWalFile = File(tempDir, "shrink.wal").apply {
            writeBytes(
                buildSyntheticWal(
                    magic = WcdbDecryptor.WAL_MAGIC_BE,
                    salt1 = 33,
                    salt2 = 44,
                    frames = listOf(
                        Triple(3, 3, encryptPageForTest(page3BeforeShrink, pgno = 3, rawKey = encKey, ivSeed = 3)),
                        Triple(2, 2, encryptPageForTest(page2Shrink, pgno = 2, rawKey = encKey, ivSeed = 2)),
                        Triple(1, 3, encryptPageForTest(page1Final, pgno = 1, rawKey = encKey, ivSeed = 1))
                    )
                )
            )
        }
        val shrinkOut = File(tempDir, "out_shrink.db").apply { writeBytes(mainPlain) }
        assertTrue(WcdbDecryptor.replayEncryptedWalIfValid(shrinkOut, shrinkWalFile, encKey))
        assertEquals(3L * WcdbDecryptor.PAGE_SIZE, shrinkOut.length())
        val finalBytes = shrinkOut.readBytes()
        // Page 3 region (bytes 2*4096 until end) should remain 0-filled from setLength, NOT 0x77
        assertTrue(finalBytes.copyOfRange(2 * WcdbDecryptor.PAGE_SIZE, 2 * WcdbDecryptor.PAGE_SIZE + 64).all { it == 0.toByte() })

        // Symlinked custom keys file must be rejected by findKeysFile
        val realKeys = File(tempDir, "real_keys.json").apply { writeText("""{"contact.db":{"enc_key":"${"aa".repeat(32)}"}}""") }
        val symlinkKeys = File(tempDir, "link_keys.json")
        runCatching { java.nio.file.Files.createSymbolicLink(symlinkKeys.toPath(), realKeys.toPath()) }
        if (java.nio.file.Files.isSymbolicLink(symlinkKeys.toPath())) {
            assertEquals(null, WcdbDecryptor.findKeysFile(customKeysFile = symlinkKeys, baseDir = tempDir))
        }

        // Symlinked WAL file leaves mainDecryptedFile intact and does not corrupt output
        val symWal = File(tempDir, "sym_target.wal").apply { writeBytes(shrinkWalFile.readBytes()) }
        val linkWal = File(tempDir, "link.wal")
        Files.createSymbolicLink(linkWal.toPath(), symWal.toPath())
        val intactOut = File(tempDir, "out_intact.db").apply { writeBytes(mainPlain) }
        assertFalse(WcdbDecryptor.replayEncryptedWalIfValid(intactOut, linkWal, encKey))
        assertTrue(intactOut.readBytes().contentEquals(mainPlain))
    }
}
