package com.wechatdigest.core.detector

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeChatPathDetectorTest {

    @TempDir
    lateinit var tempDir: File

    private val detector = WeChatPathDetector()

    @Test
    fun `detectCandidates finds valid mock directories`() {
        val mockWxidDir = File(tempDir, "xwechat_files/wxid_test123/db_storage")
        val contactDir = File(mockWxidDir, "contact")
        val messageDir = File(mockWxidDir, "message")
        contactDir.mkdirs()
        messageDir.mkdirs()
        File(contactDir, "contact.db").createNewFile()
        File(messageDir, "message_1.db").createNewFile()

        val mockFlatDir = File(tempDir, "flat_decrypted")
        mockFlatDir.mkdirs()
        File(mockFlatDir, "contact.db").createNewFile()
        File(mockFlatDir, "message_1.db").createNewFile()

        val dummyDir = File(tempDir, "dummy_folder")
        dummyDir.mkdirs()

        val candidates = detector.detectCandidates(customRoots = listOf(mockWxidDir, mockFlatDir, dummyDir))

        assertEquals(2, candidates.size)
        assertTrue(candidates.any { it.path == mockWxidDir && it.hasContacts && it.hasMessages })
        assertTrue(candidates.any { it.path == mockFlatDir && it.hasContacts && it.hasMessages })
    }

    @Test
    fun `isValidWeChatDir verifies contact and message db existence`() {
        val validDir = File(tempDir, "valid")
        validDir.mkdirs()
        File(validDir, "contact.db").createNewFile()
        File(validDir, "message_1.db").createNewFile()

        val invalidDir = File(tempDir, "invalid")
        invalidDir.mkdirs()

        assertTrue(detector.isValidWeChatDir(validDir))
        assertFalse(detector.isValidWeChatDir(invalidDir))
    }

    @Test
    fun `detectCandidates labels plaintext SQLite databases as decrypted`() {
        val decryptedDir = File(tempDir, "decrypted_copy").apply { mkdirs() }
        File(decryptedDir, "contact.db").writeBytes(
            "SQLite format 3\u0000SyntheticData".toByteArray(Charsets.ISO_8859_1)
        )
        File(decryptedDir, "message_0.db").writeBytes(
            "SQLite format 3\u0000SyntheticData".toByteArray(Charsets.ISO_8859_1)
        )

        val candidates = detector.detectCandidates(customRoots = listOf(decryptedDir))
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().isDecryptedCopy)
        assertTrue(candidates.single().label.contains("Plaintext / Decrypted"))
    }

    @Test
    fun `isValidWeChatDir ignores message_fts and non-numeric auxiliary message databases`() {
        val ftsOnlyDir = File(tempDir, "fts_only").apply { mkdirs() }
        File(ftsOnlyDir, "contact.db").createNewFile()
        File(ftsOnlyDir, "message_fts.db").createNewFile()
        File(ftsOnlyDir, "message_resource.db").createNewFile()

        assertFalse(detector.isValidWeChatDir(ftsOnlyDir))
        assertTrue(detector.detectCandidates(customRoots = listOf(ftsOnlyDir)).single().let { it.hasContacts && !it.hasMessages })
    }

    @Test
    fun `resolveUserPath expands tilde and detector rejects symlinked message and contact dirs`() {
        val home = System.getProperty("user.home")
        assertEquals(File(home, "Documents/wechat"), WeChatPathDetector.resolveUserPath("~/Documents/wechat"))
        assertEquals(null, WeChatPathDetector.resolveUserPath("   "))

        val realTarget = File(tempDir, "real_target").apply { mkdirs() }
        File(realTarget, "contact.db").createNewFile()
        File(realTarget, "message_0.db").createNewFile()

        val symAccount = File(tempDir, "sym_account").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(symAccount, "contact").toPath(), realTarget.toPath())
        java.nio.file.Files.createSymbolicLink(File(symAccount, "message").toPath(), realTarget.toPath())
        assertFalse(detector.isValidWeChatDir(symAccount))
    }
}
