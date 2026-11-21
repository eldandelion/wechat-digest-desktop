package com.wechatdigest.core.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
