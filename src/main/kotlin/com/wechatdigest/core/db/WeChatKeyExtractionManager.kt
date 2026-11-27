package com.wechatdigest.core.db

import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.llm.local.LocalModelPaths
import com.wechatdigest.core.llm.local.sha256HexEqualsConstantTime
import com.wechatdigest.core.notification.AppleScriptRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Codesign / Hardened Runtime inspection status for `/Applications/WeChat.app`. */
enum class WeChatCodeSignState {
    ADHOC_OR_DEBUGGABLE,
    HARDENED_RUNTIME,
    APP_NOT_FOUND,
    UNKNOWN
}

/** Diagnostic snapshot of macOS prerequisites and SQLCipher 4 database key status. */
data class KeyPreflightStatus(
    val isMacOs: Boolean,
    val wechatAppFile: File?,
    val wechatVersion: String?,
    val codeSignState: WeChatCodeSignState,
    val hasLldb: Boolean,
    val hasPython3: Boolean,
    val isWeChatRunning: Boolean,
    val wechatDataDir: File?,
    val isEncryptedDb: Boolean,
    val keysFile: File?,
    val passphraseFile: File?,
    val verificationReport: WcdbDecryptor.DbKeyVerificationReport?
) {
    val needsResign: Boolean
        get() = codeSignState == WeChatCodeSignState.HARDENED_RUNTIME

    val isReadyToSync: Boolean
        get() = when {
            wechatDataDir == null -> false
            !isEncryptedDb -> true
            else -> verificationReport?.allVerified == true
        }
}

/** Live UI state for interactive guided database key extraction. */
sealed interface KeyExtractionState {
    data object Idle : KeyExtractionState
    data class Running(val stage: String, val detail: String) : KeyExtractionState
    data class Succeeded(val verifiedCount: Int, val totalCount: Int, val keysFile: File) : KeyExtractionState
    data class Failed(val errorCode: String, val message: String) : KeyExtractionState
    data object Cancelled : KeyExtractionState
}

/** Parsed JSON-line progress event emitted by bundled `wcdb_key_extractor.py --json-events`. */
@Serializable
data class ExtractorProgressEvent(
    val event: String = "",
    val stage: String = "",
    val code: String = "",
    val message: String = "",
    val verifiedCount: Int = 0,
    val totalCount: Int = 0,
    val outputPath: String = "",
    val timeoutSec: Int = 0
)

/** Subprocess stdout/stderr capture result used by [WeChatKeyExtractionManager]. */
data class CommandExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * Coordinates macOS preflight inspection, SHA-256-verified staging of the bundled Python key
 * extractor (`wcdb_key_extractor.py`), copyable Terminal command generation, and guided macOS
 * Touch ID/Admin elevation for initial WeChat database key capture.
 */
class WeChatKeyExtractionManager(
    private val binDir: File = File(LocalModelPaths.defaultBaseDir(), "bin"),
    private val defaultKeysFile: File = File(LocalModelPaths.defaultBaseDir(), "all_keys.json"),
    private val defaultPassphraseFile: File = File(LocalModelPaths.defaultBaseDir(), "wechat-passphrase.json"),
    private val isMacOs: () -> Boolean = ::defaultIsMacOs,
    private val wechatAppLocator: () -> File? = ::defaultLocateWeChatApp,
    private val executableExists: (String) -> Boolean = { File(it).canExecute() },
    private val commandRunner: (List<String>, Long) -> CommandExecResult = ::defaultCommandRunner,
    private val uidGidProvider: () -> Pair<Int, Int>? = { defaultUidGidProvider(commandRunner) },
    private val elevationRunner: (String, Long) -> CommandExecResult = ::defaultAdminElevationRunner
) {
    private val scriptStageLock = Any()

    /** Returns live prerequisite & key-verification diagnostics for [wechatDataDir] and [customKeysFile]. */
    fun inspectPreflight(
        wechatDataDir: File?,
        customKeysFile: File? = null
    ): KeyPreflightStatus {
        val mac = isMacOs()
        val appFile = if (mac) wechatAppLocator() else null
        val version = appFile?.let { readWeChatVersion(it) }
        val signState = if (!mac) {
            WeChatCodeSignState.UNKNOWN
        } else if (appFile == null) {
            WeChatCodeSignState.APP_NOT_FOUND
        } else {
            inspectWeChatCodeSign(appFile)
        }
        val hasLldb = mac && checkLldbInstalled()
        val hasPython3 = mac && executableExists(PYTHON3_PATH)
        val isRunning = mac && checkWeChatProcessRunning()

        val validDataDir = wechatDataDir?.takeIf {
            !WcdbDecryptor.hasSymlinkInChain(it) && it.exists() && it.isDirectory
        }
        val resolvedKeys = if (customKeysFile != null) {
            WcdbDecryptor.findKeysFile(customKeysFile = customKeysFile, baseDir = validDataDir)
        } else {
            WcdbDecryptor.findKeysFile(customKeysFile = defaultKeysFile, baseDir = validDataDir)
                ?: WcdbDecryptor.findKeysFile(customKeysFile = null, baseDir = validDataDir)
        }
        val resolvedPassphrase = WcdbDecryptor.findPassphraseFile(defaultPassphraseFile)
            ?: WcdbDecryptor.findPassphraseFile()

        val dbFiles = validDataDir?.let { WcdbDecryptor.collectDatabaseFiles(it) }.orEmpty()
        val hasEncryptedDb = dbFiles.any { WcdbDecryptor.isEncrypted(it) }
        val report = if (validDataDir != null && hasEncryptedDb) {
            WcdbDecryptor.verifyAndRefreshKeysForDirectory(
                baseDir = validDataDir,
                keysFile = resolvedKeys,
                passphraseFile = resolvedPassphrase,
                defaultOutputKeysFile = defaultKeysFile
            )
        } else {
            null
        }
        val effectiveKeysFile = report?.activeKeysFile ?: resolvedKeys

        return KeyPreflightStatus(
            isMacOs = mac,
            wechatAppFile = appFile,
            wechatVersion = version,
            codeSignState = signState,
            hasLldb = hasLldb,
            hasPython3 = hasPython3,
            isWeChatRunning = isRunning,
            wechatDataDir = validDataDir,
            isEncryptedDb = hasEncryptedDb,
            keysFile = effectiveKeysFile,
            passphraseFile = resolvedPassphrase ?: defaultPassphraseFile,
            verificationReport = report
        )
    }

    /**
     * Extracts `/scripts/wcdb_key_extractor.py` into [binDir] with constant-time SHA-256 verification,
     * rejecting symlinks and tampered artifacts fail-closed.
     */
    fun stageAndVerifyExtractorScript(): File? = stageAndVerifyExtractorScriptWithHash()?.first

    internal fun stageAndVerifyExtractorScriptWithHash(): Pair<File, String>? = synchronized(scriptStageLock) {
        return@synchronized runCatching {
            val expectedSha = loadExpectedScriptSha256() ?: return@runCatching null
            if (hasSymlinkInAppChain(binDir)) return@runCatching null
            LocalModelPaths.ensureOwnerOnlyDirectory(binDir)
            val target = File(binDir, SCRIPT_FILE_NAME)
            val marker = File(binDir, SCRIPT_MARKER_NAME)
            if (hasSymlinkInAppChain(target) || hasSymlinkInAppChain(marker)) {
                if (Files.isSymbolicLink(target.toPath())) target.delete()
                if (Files.isSymbolicLink(marker.toPath())) marker.delete()
            }

            if (target.isFile &&
                !Files.isSymbolicLink(target.toPath()) &&
                marker.isFile &&
                !Files.isSymbolicLink(marker.toPath()) &&
                sha256HexEqualsConstantTime(marker.readText().trim(), expectedSha) &&
                sha256HexEqualsConstantTime(computeSha256Hex(target), expectedSha)
            ) {
                LocalModelPaths.setOwnerOnlyPermissions(target, isDirectory = false, executable = false)
                return@runCatching target to expectedSha
            }

            val stream = WeChatKeyExtractionManager::class.java.getResourceAsStream(RESOURCE_SCRIPT_PATH)
                ?: return@runCatching null
            val tempScript = LocalModelPaths.createOwnerOnlyTempFile(binDir, "wcdb_key_extractor-", ".py.tmp")
            val tempMarker = LocalModelPaths.createOwnerOnlyTempFile(binDir, "wcdb_key_extractor-", ".sha256.tmp")
            try {
                stream.use { input ->
                    tempScript.outputStream().use { out -> input.copyTo(out) }
                }
                val actualSha = computeSha256Hex(tempScript)
                if (!sha256HexEqualsConstantTime(actualSha, expectedSha)) {
                    System.err.println("[WeChatKeyExtractionManager] Extractor script SHA-256 check failed.")
                    return@runCatching null
                }
                LocalModelPaths.atomicMove(tempScript, target, executable = false)
                tempMarker.writeText("$expectedSha\n", Charsets.UTF_8)
                LocalModelPaths.atomicMove(tempMarker, marker, executable = false)
                target to expectedSha
            } finally {
                runCatching { if (tempScript.exists()) tempScript.delete() }
                runCatching { if (tempMarker.exists()) tempMarker.delete() }
            }
        }.onFailure { err ->
            System.err.println(
                "[WeChatKeyExtractionManager] Failed staging extractor: ${LlmJsonUtils.sanitizeLogMessage(err.message)}"
            )
        }.getOrNull()
    }

    /**
     * Builds a safe copy-pasteable zsh command string for users who prefer running extraction in Terminal.
     */
    fun buildManualTerminalCommand(
        wechatDataDir: File,
        wechatAppFile: File? = File(DEFAULT_WECHAT_APP_PATH),
        includeResignStep: Boolean = true,
        outputKeysFile: File = defaultKeysFile,
        passphraseFile: File = defaultPassphraseFile
    ): String? {
        if (!isMacOs()) return null
        if (!isSafeShellPath(wechatDataDir) ||
            hasSymlinkInAppChain(outputKeysFile) ||
            hasSymlinkInAppChain(passphraseFile)
        ) {
            return null
        }
        val dbCanon = runCatching { wechatDataDir.canonicalPath }.getOrNull() ?: return null
        val outCanon = runCatching { outputKeysFile.canonicalPath }.getOrNull() ?: return null
        val passCanon = runCatching { passphraseFile.canonicalPath }.getOrNull() ?: return null
        if (hasControlChars(dbCanon) || hasControlChars(outCanon) || hasControlChars(passCanon)) {
            return null
        }

        val scriptFile = stageAndVerifyExtractorScript() ?: return null
        val scriptCanon = runCatching { scriptFile.canonicalPath }.getOrNull() ?: return null
        if (hasControlChars(scriptCanon)) return null

        val resignPrefix = if (includeResignStep && wechatAppFile != null && isSafeAppBundlePath(wechatAppFile)) {
            val appCanon = runCatching { wechatAppFile.canonicalPath }.getOrNull() ?: return null
            if (hasControlChars(appCanon)) return null
            val appPath = shellQuote(appCanon)
            "sudo /usr/bin/codesign --force --deep --sign - $appPath && " +
                "(/usr/bin/killall -u \"\$USER\" WeChat 2>/dev/null || true) && " +
                "/usr/bin/open -a $appPath && /bin/sleep 2 && "
        } else {
            ""
        }
        return resignPrefix +
            "sudo $PYTHON3_PATH -I ${shellQuote(scriptCanon)} " +
            "--db-dir ${shellQuote(dbCanon)} " +
            "--output ${shellQuote(outCanon)} " +
            "--passphrase-file ${shellQuote(passCanon)} " +
            "--owner-uid \$(id -u) --owner-gid \$(id -g)"
    }

    /**
     * Executes interactive guided key extraction using macOS Touch ID / Admin authentication and streams
     * structured stage updates into [onStateChange].
     */
    suspend fun extractKeysInteractive(
        preflight: KeyPreflightStatus,
        resignWeChat: Boolean,
        timeoutSeconds: Int = DEFAULT_CAPTURE_TIMEOUT_SEC,
        onStateChange: (KeyExtractionState) -> Unit
    ): KeyExtractionState = withContext(Dispatchers.IO) {
        if (!isMacOs()) {
            return@withContext KeyExtractionState.Failed(
                "unsupported_os",
                "In-app WeChat key extraction is only supported on macOS."
            ).also(onStateChange)
        }
        val dbDir = preflight.wechatDataDir
            ?: return@withContext KeyExtractionState.Failed(
                "missing_db_dir",
                "Select a valid WeChat data directory first."
            ).also(onStateChange)
        if (!isSafeShellPath(dbDir)) {
            return@withContext KeyExtractionState.Failed(
                "unsafe_path",
                "WeChat data directory path contains symlinks or unsafe characters."
            ).also(onStateChange)
        }
        if (!preflight.hasPython3) {
            return@withContext KeyExtractionState.Failed(
                "python_missing",
                "macOS system python3 (/usr/bin/python3) is not available."
            ).also(onStateChange)
        }
        if (!preflight.hasLldb) {
            return@withContext KeyExtractionState.Failed(
                "lldb_missing",
                "Xcode Command Line Tools (lldb) must be installed first."
            ).also(onStateChange)
        }

        val (scriptFile, expectedSha) = stageAndVerifyExtractorScriptWithHash()
            ?: return@withContext KeyExtractionState.Failed(
                "script_verify_failed",
                "Bundled key extractor integrity check failed."
            ).also(onStateChange)

        val uidGid = uidGidProvider()
            ?: return@withContext KeyExtractionState.Failed(
                "uid_lookup_failed",
                "Unable to determine current macOS user ID."
            ).also(onStateChange)

        if (hasSymlinkInAppChain(defaultKeysFile) ||
            hasSymlinkInAppChain(defaultPassphraseFile) ||
            hasControlChars(defaultKeysFile.canonicalPath) ||
            hasControlChars(defaultPassphraseFile.canonicalPath)
        ) {
            return@withContext KeyExtractionState.Failed(
                "unsafe_path",
                "Configured key or passphrase path contains symlinks or unsafe characters."
            ).also(onStateChange)
        }

        val progressDir = LocalModelPaths.createOwnerOnlyTempDirectory("wxkey-progress-")
        val eventsLog = LocalModelPaths.createOwnerOnlyTempFile(progressDir, "events-", ".jsonl")
        val cancelFile = File(progressDir, "cancel.flag")
        var lastErrorEvent: ExtractorProgressEvent? = null
        var completeEvent: ExtractorProgressEvent? = null

        try {
            onStateChange(
                KeyExtractionState.Running(
                    stage = "authorizing",
                    detail = if (resignWeChat && preflight.needsResign) {
                        "Approve the macOS administrator prompt to re-sign WeChat and start key extraction..."
                    } else {
                        "Approve the macOS administrator prompt to read WeChat keys..."
                    }
                )
            )

            val shellCommand = buildElevatedShellCommand(
                scriptFile = scriptFile,
                expectedScriptSha256 = expectedSha,
                dbDir = dbDir,
                outputKeysFile = defaultKeysFile,
                passphraseFile = defaultPassphraseFile,
                eventsLog = eventsLog,
                cancelFile = cancelFile,
                wechatAppFile = preflight.wechatAppFile,
                resignWeChat = resignWeChat && preflight.needsResign,
                uidGid = uidGid,
                timeoutSeconds = timeoutSeconds
            )

            var readByteOffset = 0L
            val partialLineBuffer = ByteArrayOutputStream()
            fun drainEventsLog(flushRemainder: Boolean = false) {
                if (!eventsLog.exists() || !eventsLog.isFile) return
                runCatching {
                    RandomAccessFile(eventsLog, "r").use { raf ->
                        val len = raf.length()
                        if (len < readByteOffset) {
                            readByteOffset = 0L
                            partialLineBuffer.reset()
                        }
                        if (len > readByteOffset) {
                            raf.seek(readByteOffset)
                            val chunk = ByteArray(4096)
                            while (readByteOffset < len) {
                                val read = raf.read(chunk)
                                if (read <= 0) break
                                for (i in 0 until read) {
                                    val b = chunk[i]
                                    if (b == '\n'.code.toByte()) {
                                        val line = partialLineBuffer.toString(Charsets.UTF_8)
                                        partialLineBuffer.reset()
                                        val parsed = parseProgressEvent(line) ?: continue
                                        when (parsed.event) {
                                            "stage" -> onStateChange(
                                                KeyExtractionState.Running(
                                                    parsed.stage,
                                                    LlmJsonUtils.sanitizeLogMessage(parsed.message)
                                                )
                                            )
                                            "complete" -> completeEvent = parsed
                                            "error" -> lastErrorEvent = parsed
                                        }
                                    } else {
                                        partialLineBuffer.write(b.toInt())
                                    }
                                }
                                readByteOffset += read
                            }
                        }
                        if (flushRemainder && partialLineBuffer.size() > 0) {
                            val line = partialLineBuffer.toString(Charsets.UTF_8)
                            partialLineBuffer.reset()
                            val parsed = parseProgressEvent(line)
                            if (parsed != null) {
                                when (parsed.event) {
                                    "stage" -> onStateChange(
                                        KeyExtractionState.Running(
                                            parsed.stage,
                                            LlmJsonUtils.sanitizeLogMessage(parsed.message)
                                        )
                                    )
                                    "complete" -> completeEvent = parsed
                                    "error" -> lastErrorEvent = parsed
                                }
                            }
                        }
                    }
                }
            }

            val result = coroutineScope {
                val execDeferred = async(Dispatchers.IO) {
                    runInterruptible {
                        elevationRunner(shellCommand, (timeoutSeconds + 45).toLong())
                    }
                }
                while (!execDeferred.isCompleted && isActive) {
                    drainEventsLog(flushRemainder = false)
                    delay(PROGRESS_POLL_MS)
                }
                execDeferred.await()
            }
            drainEventsLog(flushRemainder = true)

            val errorFromScript = lastErrorEvent
            if (errorFromScript != null) {
                if (errorFromScript.code == "cancelled") {
                    return@withContext KeyExtractionState.Cancelled.also(onStateChange)
                }
                return@withContext KeyExtractionState.Failed(
                    errorCode = errorFromScript.code.ifBlank { "extraction_failed" },
                    message = LlmJsonUtils.sanitizeLogMessage(errorFromScript.message)
                ).also(onStateChange)
            }
            if (result.exitCode != 0) {
                val stderrLower = result.stderr.lowercase(Locale.ROOT)
                val msg = when {
                    stderrLower.contains("user canceled") || stderrLower.contains("-128") ->
                        "Administrator prompt was cancelled."
                    else -> "Key extraction failed: ${LlmJsonUtils.sanitizeLogMessage(result.stderr.ifBlank { "exit code ${result.exitCode}" })}"
                }
                return@withContext KeyExtractionState.Failed("elevation_failed", msg).also(onStateChange)
            }

            val report = WcdbDecryptor.verifyAndRefreshKeysForDirectory(
                baseDir = dbDir,
                keysFile = defaultKeysFile,
                passphraseFile = defaultPassphraseFile,
                defaultOutputKeysFile = defaultKeysFile
            )
            if (report.allVerified) {
                return@withContext KeyExtractionState.Succeeded(
                    verifiedCount = report.verifiedDbs,
                    totalCount = report.totalEncryptedDbs,
                    keysFile = report.activeKeysFile ?: defaultKeysFile
                ).also(onStateChange)
            }

            val partialMsg = when {
                report.writeError != null -> report.writeError
                report.totalEncryptedDbs == 0 -> "No encrypted databases require keys."
                else -> "Verified ${report.verifiedDbs}/${report.totalEncryptedDbs} databases. Some databases could not be verified."
            }
            return@withContext KeyExtractionState.Failed("verification_incomplete", partialMsg).also(onStateChange)
        } catch (e: CancellationException) {
            runCatching { cancelFile.writeText("1\n", Charsets.UTF_8) }
            onStateChange(KeyExtractionState.Cancelled)
            throw e
        } catch (e: Exception) {
            return@withContext KeyExtractionState.Failed(
                "unexpected_error",
                LlmJsonUtils.sanitizeLogMessage(e.message)
            ).also(onStateChange)
        } finally {
            if (completeEvent == null) {
                runCatching { cancelFile.writeText("1\n", Charsets.UTF_8) }
            }
            runCatching { progressDir.deleteRecursively() }
        }
    }

    /** Launches macOS `xcode-select --install` GUI prompt when Developer Tools (`lldb`) are absent. */
    fun triggerXcodeToolsInstall(): Result<Unit> = runCatching {
        require(isMacOs()) { "Xcode Command Line Tools install is only supported on macOS." }
        val bin = File(XCODE_SELECT_PATH)
        require(bin.canExecute()) { "Missing $XCODE_SELECT_PATH" }
        val proc = ProcessBuilder(bin.absolutePath, "--install")
            .redirectErrorStream(true)
            .start()
        try {
            runCatching { proc.outputStream.close() }
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                error("Timed out launching xcode-select installer.")
            }
            if (proc.exitValue() != 0) {
                val out = readBoundedText(proc.inputStream, 512)
                // exit 1 with "already installed" is benign on macOS
                if (!out.contains("already installed", ignoreCase = true)) {
                    error(LlmJsonUtils.sanitizeLogMessage(out.ifBlank { "xcode-select exited ${proc.exitValue()}" }))
                }
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            runCatching { proc.inputStream.close() }
        }
    }

    internal fun buildElevatedShellCommand(
        scriptFile: File,
        expectedScriptSha256: String,
        dbDir: File,
        outputKeysFile: File,
        passphraseFile: File,
        eventsLog: File,
        cancelFile: File?,
        wechatAppFile: File?,
        resignWeChat: Boolean,
        uidGid: Pair<Int, Int>,
        timeoutSeconds: Int
    ): String {
        require(expectedScriptSha256.lowercase(Locale.ROOT).matches(SHA256_HEX_REGEX)) {
            "Invalid SHA-256 hex digest"
        }
        val uid = uidGid.first
        val gid = uidGid.second
        require(uid >= 0 && gid >= 0) { "Invalid uid/gid" }

        val sb = StringBuilder()
        if (resignWeChat && wechatAppFile != null && isSafeAppBundlePath(wechatAppFile)) {
            val quotedApp = shellQuote(wechatAppFile.canonicalPath)
            sb.append("/usr/bin/codesign --force --deep --sign - ").append(quotedApp).append(" >/dev/null 2>&1 && ")
            sb.append("(/usr/bin/pkill -u ").append(uid).append(" -x WeChat >/dev/null 2>&1 || true) && ")
            sb.append("/bin/sleep 1 && ")
            sb.append("/usr/bin/sudo -u ").append(shellQuote("#$uid")).append(" /usr/bin/open -a ").append(quotedApp).append(" && ")
            sb.append("/bin/sleep 3 && ")
        }
        val expectedNormalized = expectedScriptSha256.lowercase(Locale.ROOT)
        sb.append("_WXDIR=$(/usr/bin/mktemp -d /private/tmp/wxkey-root.XXXXXX) && ")
        sb.append("trap '/bin/rm -rf \"\$_WXDIR\"' EXIT && ")
        sb.append("/bin/cp ").append(shellQuote(scriptFile.canonicalPath)).append(" \"\$_WXDIR/extract.py\" && ")
        sb.append("/bin/chmod 0600 \"\$_WXDIR/extract.py\" && ")
        sb.append("printf '%s  %s\\n' ").append(shellQuote(expectedNormalized)).append(" \"\$_WXDIR/extract.py\" | /usr/bin/shasum -a 256 -c - >/dev/null && ")
        sb.append(PYTHON3_PATH).append(" -I \"\$_WXDIR/extract.py\" ")
        sb.append("--db-dir ").append(shellQuote(dbDir.canonicalPath)).append(' ')
        sb.append("--output ").append(shellQuote(outputKeysFile.canonicalPath)).append(' ')
        sb.append("--passphrase-file ").append(shellQuote(passphraseFile.canonicalPath)).append(' ')
        sb.append("--timeout ").append(timeoutSeconds.coerceIn(30, 300)).append(' ')
        if (cancelFile != null) {
            sb.append("--cancel-file ").append(shellQuote(cancelFile.absolutePath)).append(' ')
        }
        sb.append("--owner-uid ").append(uid).append(' ')
        sb.append("--owner-gid ").append(gid).append(' ')
        sb.append("--json-events > ").append(shellQuote(eventsLog.canonicalPath))
        return sb.toString()
    }

    internal fun parseProgressEvent(line: String): ExtractorProgressEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            LlmJsonUtils.sharedJson.decodeFromString<ExtractorProgressEvent>(trimmed)
        }.getOrNull()
    }

    private fun readWeChatVersion(appFile: File): String? {
        val plist = File(appFile, "Contents/Info.plist")
        if (!plist.exists() || WcdbDecryptor.hasSymlinkInChain(plist)) return null
        val plutil = File(PLUTIL_PATH)
        if (!plutil.canExecute()) return null
        val res = commandRunner(
            listOf(plutil.absolutePath, "-extract", "CFBundleShortVersionString", "raw", "-o", "-", plist.canonicalPath),
            3L
        )
        return res.stdout.trim().takeIf { res.exitCode == 0 && it.isNotBlank() && it.length <= 32 }
    }

    private fun inspectWeChatCodeSign(appFile: File): WeChatCodeSignState {
        val codesign = File(CODESIGN_PATH)
        if (!codesign.canExecute()) return WeChatCodeSignState.UNKNOWN
        val res = commandRunner(listOf(codesign.absolutePath, "-dvv", appFile.canonicalPath), 5L)
        val entRes = commandRunner(listOf(codesign.absolutePath, "-d", "--entitlements", "-", appFile.canonicalPath), 5L)
        return parseCodeSignOutput(res.stderr + "\n" + res.stdout, entRes.stdout + "\n" + entRes.stderr)
    }

    internal fun parseCodeSignOutput(codesignText: String, entitlementsText: String = ""): WeChatCodeSignState {
        if (codesignText.isBlank()) return WeChatCodeSignState.UNKNOWN
        val lower = codesignText.lowercase(Locale.ROOT)
        val hasGetTaskAllow = GET_TASK_ALLOW_TRUE_REGEX.containsMatchIn(entitlementsText)
        val hasRuntimeFlag = CODE_SIGN_RUNTIME_REGEX.containsMatchIn(lower)
        val isAdHoc = lower.contains("signature=adhoc")
        return when {
            hasGetTaskAllow -> WeChatCodeSignState.ADHOC_OR_DEBUGGABLE
            hasRuntimeFlag -> WeChatCodeSignState.HARDENED_RUNTIME
            isAdHoc -> WeChatCodeSignState.ADHOC_OR_DEBUGGABLE
            lower.contains("flags=") -> WeChatCodeSignState.ADHOC_OR_DEBUGGABLE
            else -> WeChatCodeSignState.UNKNOWN
        }
    }

    private fun checkLldbInstalled(): Boolean {
        if (!executableExists(LLDB_PATH)) return false
        if (!executableExists(XCODE_SELECT_PATH)) return true
        val res = commandRunner(listOf(XCODE_SELECT_PATH, "-p"), 3L)
        return res.exitCode == 0 && res.stdout.isNotBlank()
    }

    private fun checkWeChatProcessRunning(): Boolean {
        val pgrep = File(PGREP_PATH)
        if (!pgrep.canExecute()) return false
        val res = commandRunner(listOf(pgrep.absolutePath, "-x", "WeChat"), 3L)
        return res.exitCode == 0 && res.stdout.trim().isNotBlank()
    }

    private fun loadExpectedScriptSha256(): String? =
        WeChatKeyExtractionManager::class.java.getResourceAsStream(RESOURCE_SCRIPT_SHA256_PATH)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText().trim().lowercase(Locale.ROOT) }
            ?.takeIf { it.matches(SHA256_HEX_REGEX) }

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasSymlinkInAppChain(target: File): Boolean = WcdbDecryptor.hasSymlinkInChain(target)

    private fun isSafeShellPath(file: File): Boolean {
        if (hasControlChars(file.absolutePath)) return false
        return try {
            !WcdbDecryptor.hasSymlinkInChain(file) && file.exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun isSafeAppBundlePath(file: File): Boolean {
        if (hasControlChars(file.absolutePath)) return false
        return try {
            !WcdbDecryptor.hasSymlinkInChain(file) &&
                file.exists() &&
                file.isDirectory &&
                file.name.equals("WeChat.app", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    internal fun hasControlChars(text: String): Boolean =
        text.any { ch -> ch.code < 0x20 || ch.code == 0x7F }

    internal fun shellQuote(arg: String): String {
        require(!hasControlChars(arg)) { "Refusing path with control characters" }
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }

    companion object {
        private const val RESOURCE_SCRIPT_PATH = "/scripts/wcdb_key_extractor.py"
        private const val RESOURCE_SCRIPT_SHA256_PATH = "/scripts/wcdb_key_extractor.py.sha256"
        private const val SCRIPT_FILE_NAME = "wcdb_key_extractor.py"
        private const val SCRIPT_MARKER_NAME = ".wcdb_key_extractor.sha256"
        private const val DEFAULT_WECHAT_APP_PATH = "/Applications/WeChat.app"
        private const val PYTHON3_PATH = "/usr/bin/python3"
        private const val LLDB_PATH = "/usr/bin/lldb"
        private const val CODESIGN_PATH = "/usr/bin/codesign"
        private const val PLUTIL_PATH = "/usr/bin/plutil"
        private const val XCODE_SELECT_PATH = "/usr/bin/xcode-select"
        private const val PGREP_PATH = "/usr/bin/pgrep"
        private const val OSASCRIPT_PATH = "/usr/bin/osascript"
        private const val PROGRESS_POLL_MS = 300L
        private const val DEFAULT_CAPTURE_TIMEOUT_SEC = 180
        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
        private val CODE_SIGN_RUNTIME_REGEX = Regex("""flags=0x[0-9a-f]+\([^)]*runtime[^)]*\)""")
        private val GET_TASK_ALLOW_TRUE_REGEX = Regex(
            """<key>\s*com\.apple\.security\.get-task-allow\s*</key>\s*<true\s*/>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        private fun defaultIsMacOs(): Boolean =
            (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("mac")

        private fun defaultLocateWeChatApp(): File? {
            val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            val candidates = buildList {
                add(File(DEFAULT_WECHAT_APP_PATH))
                if (userHome != null) {
                    add(File(userHome, "Applications/WeChat.app"))
                }
            }
            return candidates.firstOrNull { f ->
                f.exists() && f.isDirectory && !WcdbDecryptor.hasSymlinkInChain(f)
            }
        }

        private fun defaultUidGidProvider(runner: (List<String>, Long) -> CommandExecResult): Pair<Int, Int>? {
            val idBin = File("/usr/bin/id").takeIf { it.canExecute() }?.absolutePath ?: return null
            val uid = runner(listOf(idBin, "-u"), 3L).stdout.trim().toIntOrNull() ?: return null
            val gid = runner(listOf(idBin, "-g"), 3L).stdout.trim().toIntOrNull() ?: return null
            return uid to gid
        }

        fun defaultCommandRunner(command: List<String>, timeoutSeconds: Long): CommandExecResult {
            val process = ProcessBuilder(command).start()
            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val outThread = Thread({
                stdoutRef.set(readBoundedText(process.inputStream, 32 * 1024))
            }, "wxkey-cmd-out").apply { isDaemon = true }
            val errThread = Thread({
                stderrRef.set(readBoundedText(process.errorStream, 32 * 1024))
            }, "wxkey-cmd-err").apply { isDaemon = true }

            try {
                runCatching { process.outputStream.close() }
                outThread.start()
                errThread.start()
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                    outThread.join(500)
                    errThread.join(500)
                    return CommandExecResult(-1, stdoutRef.get(), "Command timed out.")
                }
                outThread.join(1_000)
                errThread.join(1_000)
                return CommandExecResult(process.exitValue(), stdoutRef.get(), stderrRef.get())
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                throw e
            } finally {
                if (process.isAlive) process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }

        fun defaultAdminElevationRunner(shellCommand: String, timeoutSeconds: Long): CommandExecResult {
            val osascript = File(OSASCRIPT_PATH)
            if (!osascript.canExecute()) {
                return CommandExecResult(-1, "", "Missing $OSASCRIPT_PATH")
            }
            val escaped = AppleScriptRunner.escapeString(shellCommand, replaceNewlinesWithSpace = true)
            val script = """do shell script "$escaped" with administrator privileges"""
            val proc = ProcessBuilder(osascript.absolutePath, "-").start()
            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val outThread = Thread({
                stdoutRef.set(readBoundedText(proc.inputStream, 16 * 1024))
            }, "wxkey-osascript-out").apply { isDaemon = true }
            val errThread = Thread({
                stderrRef.set(readBoundedText(proc.errorStream, 16 * 1024))
            }, "wxkey-osascript-err").apply { isDaemon = true }

            try {
                proc.outputStream.use { os ->
                    os.write(script.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                outThread.start()
                errThread.start()
                val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    runCatching { proc.waitFor(1, TimeUnit.SECONDS) }
                    outThread.join(500)
                    errThread.join(500)
                    return CommandExecResult(-1, stdoutRef.get(), "Key extraction timed out after ${timeoutSeconds}s.")
                }
                outThread.join(1_000)
                errThread.join(1_000)
                return CommandExecResult(proc.exitValue(), stdoutRef.get(), stderrRef.get())
            } catch (e: InterruptedException) {
                proc.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                throw e
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                runCatching { proc.errorStream.close() }
            }
        }

        private fun readBoundedText(stream: InputStream, maxBytes: Int): String {
            return runCatching {
                stream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(2048)
                    var captured = 0
                    var total = 0
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        if (captured < maxBytes) {
                            val toCopy = minOf(read, maxBytes - captured)
                            out.write(buf, 0, toCopy)
                            captured += toCopy
                        }
                        total += read
                        if (total >= 2 * 1024 * 1024) break
                    }
                    out.toString(Charsets.UTF_8)
                }
            }.getOrDefault("")
        }
    }
}
