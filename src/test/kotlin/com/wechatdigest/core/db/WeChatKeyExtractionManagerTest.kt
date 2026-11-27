package com.wechatdigest.core.db

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeChatKeyExtractionManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parseCodeSignOutput distinguishes hardened runtime from adhoc or debuggable builds`() {
        assertEquals(
            WeChatCodeSignState.HARDENED_RUNTIME,
            createManager().parseCodeSignOutput(
                "Identifier=com.tencent.xinWeChat\nFlags=0x10000(runtime)\nSignature=Authority=Apple Root CA",
                ""
            )
        )
        assertEquals(
            WeChatCodeSignState.ADHOC_OR_DEBUGGABLE,
            createManager().parseCodeSignOutput(
                "Identifier=com.tencent.xinWeChat\nFlags=0x2(adhoc)\nSignature=adhoc",
                ""
            )
        )
        assertEquals(
            WeChatCodeSignState.ADHOC_OR_DEBUGGABLE,
            createManager().parseCodeSignOutput(
                "Flags=0x10000(runtime)",
                "<key>com.apple.security.get-task-allow</key>\n\t<true/>"
            )
        )
        assertEquals(
            WeChatCodeSignState.HARDENED_RUNTIME,
            createManager().parseCodeSignOutput(
                "Flags=0x10000(runtime)",
                "<key>com.apple.security.get-task-allow</key><false/><key>com.apple.security.cs.allow-jit</key><true/>"
            )
        )
        assertEquals(
            WeChatCodeSignState.UNKNOWN,
            createManager().parseCodeSignOutput("")
        )
    }

    @Test
    fun `shellQuote safely quotes special chars and rejects control characters or symlinks`() {
        val manager = createManager()
        assertEquals("'it'\"'\"'s /path with spaces'", manager.shellQuote("it's /path with spaces"))
        assertFailsWith<IllegalArgumentException> {
            manager.shellQuote("/bad/path\nrm -rf /")
        }

        val dbDir = File(tempDir, "db storage").apply { mkdirs() }
        val appDir = File(tempDir, "WeChat.app").apply { mkdirs() }
        val cmd = manager.buildManualTerminalCommand(
            wechatDataDir = dbDir,
            wechatAppFile = appDir,
            includeResignStep = true
        )
        assertNotNull(cmd)
        assertTrue(cmd.contains("codesign --force --deep --sign -"))
        assertTrue(cmd.contains("/usr/bin/python3 -I "))
        assertTrue(cmd.contains("(/usr/bin/killall -u \"\$USER\" WeChat 2>/dev/null || true) && /usr/bin/open -a"))
        assertTrue(cmd.contains("--db-dir '${dbDir.canonicalPath}'"))
        assertTrue(cmd.contains("--owner-uid \$(id -u)"))

        // Stage tamper recovery & symlink rejection
        val staged = manager.stageAndVerifyExtractorScript()
        assertNotNull(staged)
        assertTrue(staged.isFile)
        staged.writeText("tampered!")
        val reStaged = manager.stageAndVerifyExtractorScript()
        assertNotNull(reStaged)
        assertFalse(reStaged.readText().contains("tampered!"))

        val symBinDir = File(tempDir, "sym_bin")
        val realBinDir = File(tempDir, "real_bin").apply { mkdirs() }
        Files.createSymbolicLink(symBinDir.toPath(), realBinDir.toPath())
        val symManager = createManager(binDirOverride = symBinDir)
        assertNull(symManager.stageAndVerifyExtractorScript())
    }

    @Test
    fun `extractKeysInteractive streams JSON events and verifies extracted all_keys json`() = runBlocking {
        val dbDir = File(tempDir, "wx_db_storage").apply { mkdirs() }
        val contactDir = File(dbDir, "contact").apply { mkdirs() }
        val encKey = ByteArray(32) { (it + 3).toByte() }
        val salt = ByteArray(16) { (it + 9).toByte() }
        File(contactDir, "contact.db").writeBytes(buildPage1WithHmac(salt, encKey))

        val outKeysFile = File(tempDir, "out/all_keys.json")
        val passphraseFile = File(tempDir, "out/wechat-passphrase.json")
        val eventsSeen = mutableListOf<KeyExtractionState>()
        var capturedShellCmd = ""

        val manager = WeChatKeyExtractionManager(
            binDir = File(tempDir, "bin"),
            defaultKeysFile = outKeysFile,
            defaultPassphraseFile = passphraseFile,
            isMacOs = { true },
            wechatAppLocator = { File(tempDir, "WeChat.app").apply { mkdirs() } },
            commandRunner = { _, _ -> CommandExecResult(0, "", "") },
            uidGidProvider = { 501 to 20 },
            elevationRunner = { shellCmd, _ ->
                capturedShellCmd = shellCmd
                // Simulate split partial JSON line write followed by completion line
                val logPath = Regex("""--json-events > '([^']+)'""").find(shellCmd)!!.groupValues[1]
                File(logPath).appendText("""{"event":"stage","stage":"lldb_""")
                Thread.sleep(350)
                File(logPath).appendText(
                    """wait","message":"Waiting for WeChat login"}""" + "\n" +
                        """{"event":"complete","verifiedCount":1,"totalCount":1,"outputPath":"${outKeysFile.absolutePath}"}"""
                )
                outKeysFile.parentFile.mkdirs()
                outKeysFile.writeText(
                    """{"contact/contact.db":{"enc_key":"${encKey.joinToString("") { "%02x".format(it) }}"}}"""
                )
                CommandExecResult(0, "", "")
            }
        )

        val preflight = manager.inspectPreflight(dbDir, customKeysFile = null)
        assertFalse(preflight.isReadyToSync)
        assertTrue(preflight.isEncryptedDb)

        val result = manager.extractKeysInteractive(
            preflight = preflight.copy(hasLldb = true, hasPython3 = true, codeSignState = WeChatCodeSignState.HARDENED_RUNTIME),
            resignWeChat = true,
            onStateChange = { eventsSeen.add(it) }
        )

        assertTrue(result is KeyExtractionState.Succeeded)
        assertEquals(1, result.verifiedCount)
        assertEquals(1, result.totalCount)
        assertTrue(eventsSeen.any { it is KeyExtractionState.Running && it.stage == "lldb_wait" })
        assertTrue(capturedShellCmd.contains("/usr/bin/shasum -a 256 -c -"))
        assertTrue(capturedShellCmd.contains("/usr/bin/python3 -I "))
        assertTrue(capturedShellCmd.contains("/bin/cp '"))
        assertFalse(capturedShellCmd.contains("/bin/cp -p"))
        assertTrue(capturedShellCmd.contains("/usr/bin/sudo -u '#501' /usr/bin/open -a"))
        assertFalse(capturedShellCmd.contains("&& /usr/bin/open -a"))

        // Fails closed when defaultKeysFile is symlinked
        val symOutKeys = File(tempDir, "sym_keys.json")
        Files.createSymbolicLink(symOutKeys.toPath(), outKeysFile.toPath())
        val symOutManager = WeChatKeyExtractionManager(
            binDir = File(tempDir, "bin_sym_out"),
            defaultKeysFile = symOutKeys,
            defaultPassphraseFile = passphraseFile,
            isMacOs = { true },
            wechatAppLocator = { File(tempDir, "WeChat.app").apply { mkdirs() } },
            uidGidProvider = { 501 to 20 }
        )
        val symOutRes = symOutManager.extractKeysInteractive(
            preflight = preflight.copy(hasLldb = true, hasPython3 = true),
            resignWeChat = false,
            onStateChange = {}
        )
        assertTrue(symOutRes is KeyExtractionState.Failed)
        assertEquals("unsafe_path", symOutRes.errorCode)

        // Fails closed when uid/gid lookup fails
        val noUidManager = WeChatKeyExtractionManager(
            binDir = File(tempDir, "bin2"),
            defaultKeysFile = outKeysFile,
            defaultPassphraseFile = passphraseFile,
            isMacOs = { true },
            wechatAppLocator = { File(tempDir, "WeChat.app").apply { mkdirs() } },
            uidGidProvider = { null }
        )
        val noUidResult = noUidManager.extractKeysInteractive(
            preflight = preflight.copy(hasLldb = true, hasPython3 = true),
            resignWeChat = false,
            onStateChange = {}
        )
        assertTrue(noUidResult is KeyExtractionState.Failed)
        assertEquals("uid_lookup_failed", noUidResult.errorCode)
    }

    private fun createManager(binDirOverride: File = File(tempDir, "bin")) = WeChatKeyExtractionManager(
        binDir = binDirOverride,
        defaultKeysFile = File(tempDir, "all_keys.json"),
        defaultPassphraseFile = File(tempDir, "wechat-passphrase.json"),
        isMacOs = { true }
    )

    private fun buildPage1WithHmac(salt: ByteArray, encKey: ByteArray): ByteArray {
        val page1 = ByteArray(WcdbDecryptor.PAGE_SIZE)
        System.arraycopy(salt, 0, page1, 0, 16)
        val macSalt = ByteArray(16) { idx -> (salt[idx].toInt() xor 0x3A).toByte() }
        val macKey = WcdbDecryptor.pbkdf2HmacSha512(encKey, macSalt, 2, 32)
        val mac = javax.crypto.Mac.getInstance("HmacSHA512").apply {
            init(SecretKeySpec(macKey, "HmacSHA512"))
        }
        mac.update(
            page1,
            WcdbDecryptor.SALT_SIZE,
            WcdbDecryptor.PAGE_SIZE - WcdbDecryptor.SALT_SIZE - WcdbDecryptor.RESERVE_SIZE + WcdbDecryptor.IV_SIZE
        )
        mac.update(byteArrayOf(1, 0, 0, 0))
        System.arraycopy(mac.doFinal(), 0, page1, WcdbDecryptor.PAGE_SIZE - 64, 64)
        return page1
    }
}
