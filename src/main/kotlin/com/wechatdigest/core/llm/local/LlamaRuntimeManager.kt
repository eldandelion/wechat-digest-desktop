package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.LlmJsonUtils
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * Downloads, SHA-256 verifies, validates against Tar-Slip/symlink attacks, extracts,
 * and activates the pinned macOS `llama-server` runtime inside `runtime/`.
 */
class LlamaRuntimeManager(
    val paths: LocalModelPaths = LocalModelPaths(),
    val release: LlamaRuntimeRelease = LocalModelCatalog.PINNED_RUNTIME,
    engine: HttpClientEngine? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val archDetector: () -> MacArchitecture? = { MacArchitecture.detect() },
    private val commandRunner: (List<String>) -> CommandOutput = ::defaultCommandRunner
) : AutoCloseable {

    private val transport: LocalHttpTransport = createLocalHttpTransport(engine)
    private val installMutex = Mutex()

    private val _runtimeState = MutableStateFlow<LocalRuntimeState>(detectInstalledState())
    val runtimeState: StateFlow<LocalRuntimeState> = _runtimeState.asStateFlow()

    private fun detectInstalledState(): LocalRuntimeState {
        val arch = archDetector() ?: return LocalRuntimeState.NotInstalled
        val executable = findVerifiedExecutable(arch) ?: return LocalRuntimeState.NotInstalled
        return LocalRuntimeState.Ready(release.tag, executable.absolutePath)
    }

    private fun findVerifiedExecutable(arch: MacArchitecture): File? {
        val tagDir = paths.runtimeTagDir(release.tag)
        val marker = File(tagDir, VERIFIED_MARKER_NAME)
        val server = File(tagDir, LLAMA_SERVER_NAME)
        val expectedMarker = expectedMarkerContent(arch)
        if (!marker.exists() || !marker.isFile || !server.exists() || !server.isFile) {
            return null
        }
        if (Files.isSymbolicLink(server.toPath()) || !server.canExecute()) {
            return null
        }
        val actualMarker = runCatching { marker.readText().trim() }.getOrNull() ?: return null
        return if (actualMarker == expectedMarker) server else null
    }

    private fun expectedMarkerContent(arch: MacArchitecture): String =
        "${release.tag}|${arch.assetArch}|${release.expectedSha256For(arch)}"

    /**
     * Returns a verified local `llama-server` executable, downloading and installing the pinned
     * macOS release tarball automatically on first invocation.
     */
    suspend fun ensureRuntimeInstalled(): File = withContext(dispatcher) {
        installMutex.withLock {
            val arch = archDetector()
                ?: throw UnsupportedOperationException("Built-in local runtime supports macOS (Apple Silicon / Intel x64) only.")
            findVerifiedExecutable(arch)?.let { existing ->
                _runtimeState.value = LocalRuntimeState.Ready(release.tag, existing.absolutePath)
                return@withContext existing
            }

            LocalModelPaths.ensureOwnerOnlyDirectory(paths.runtimeDir)
            val stagingDir = Files.createTempDirectory(paths.runtimeDir.toPath(), "staging-${release.tag}-").toFile()
            LocalModelPaths.setOwnerOnlyPermissions(stagingDir, isDirectory = true)
            val archivePart = File(paths.runtimeDir, "${release.assetNameFor(arch)}.part")

            try {
                val url = release.downloadUrlFor(arch)
                val expectedSha = release.expectedSha256For(arch)
                val expectedSize = release.expectedSizeFor(arch)

                downloadArchive(url, archivePart, expectedSize)

                _runtimeState.value = LocalRuntimeState.Verifying
                val actualSha = computeFileSha256HexCancellable(archivePart)
                if (!sha256HexEqualsConstantTime(actualSha, expectedSha)) {
                    archivePart.delete()
                    throw IOException("SHA-256 verification failed for ${release.assetNameFor(arch)}.")
                }

                _runtimeState.value = LocalRuntimeState.Extracting
                val tarBin = resolveSystemBinary("/usr/bin/tar", "tar")
                val listRes = commandRunner(listOf(tarBin, "-tzf", archivePart.absolutePath))
                if (listRes.exitCode != 0) {
                    throw IOException("Failed to inspect runtime archive: ${LlmJsonUtils.sanitizeLogMessage(listRes.stderr)}")
                }
                val verboseRes = commandRunner(listOf(tarBin, "-tvzf", archivePart.absolutePath))
                if (verboseRes.exitCode != 0) {
                    throw IOException("Failed to verify runtime archive entries: ${LlmJsonUtils.sanitizeLogMessage(verboseRes.stderr)}")
                }
                validateTarListing(listRes.stdout, verboseRes.stdout)

                val extractRes = commandRunner(
                    listOf(tarBin, "-xzf", archivePart.absolutePath, "--no-same-owner", "-C", stagingDir.absolutePath)
                )
                if (extractRes.exitCode != 0) {
                    throw IOException("Failed to extract runtime archive: ${LlmJsonUtils.sanitizeLogMessage(extractRes.stderr)}")
                }

                val extractedServer = locateServerExecutable(stagingDir)
                    ?: throw IOException("Downloaded archive did not contain a valid $LLAMA_SERVER_NAME binary.")
                val sourceBundleDir = extractedServer.parentFile
                val targetTagDir = paths.runtimeTagDir(release.tag)
                if (targetTagDir.exists()) {
                    targetTagDir.deleteRecursively()
                }
                LocalModelPaths.atomicMove(sourceBundleDir, targetTagDir, executable = true)
                LocalModelPaths.setOwnerOnlyPermissions(targetTagDir, isDirectory = true)

                targetTagDir.listFiles()?.forEach { child ->
                    if (!Files.isSymbolicLink(child.toPath()) && child.isFile) {
                        val needsExec = child.name == LLAMA_SERVER_NAME || child.name.endsWith(".dylib") || child.name.endsWith(".so")
                        LocalModelPaths.setOwnerOnlyPermissions(child, isDirectory = false, executable = needsExec)
                    }
                }

                val finalServer = File(targetTagDir, LLAMA_SERVER_NAME)
                if (!finalServer.setExecutable(true, true)) {
                    throw IOException("Unable to mark $LLAMA_SERVER_NAME executable.")
                }

                // Clear macOS Gatekeeper quarantine bit best-effort on downloaded binaries/dylibs.
                runCatching {
                    val xattrBin = resolveSystemBinary("/usr/bin/xattr", "xattr")
                    commandRunner(listOf(xattrBin, "-rd", "com.apple.quarantine", targetTagDir.absolutePath))
                }

                val markerFile = File(targetTagDir, VERIFIED_MARKER_NAME)
                markerFile.writeText(expectedMarkerContent(arch))
                LocalModelPaths.setOwnerOnlyPermissions(markerFile, isDirectory = false)

                pruneOldRuntimeVersions(keepTag = release.tag)

                _runtimeState.value = LocalRuntimeState.Ready(release.tag, finalServer.absolutePath)
                finalServer
            } catch (e: CancellationException) {
                _runtimeState.value = detectInstalledState()
                throw e
            } catch (e: Exception) {
                val msg = LlmJsonUtils.sanitizeLogMessage(e.message)
                System.err.println("[LlamaRuntimeManager] Runtime installation failed: $msg")
                _runtimeState.value = LocalRuntimeState.Failed(msg)
                throw e
            } finally {
                runCatching { if (archivePart.exists()) archivePart.delete() }
                runCatching { if (stagingDir.exists()) stagingDir.deleteRecursively() }
            }
        }
    }

    /**
     * Rejects path-traversal, absolute paths, control characters, chained symlinks, and
     * escaping symlinks/hardlinks before extracting any archive into staging storage.
     */
    internal fun validateTarListing(listOutput: String, verboseOutput: String) {
        val entries = listOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.isNotEmpty()) { "Runtime archive is empty." }

        for (entry in entries) {
            validateSafeRelativeEntryPath(entry)
        }

        val root = Path.of("/extract-root").normalize()
        val declaredSymlinkPaths = mutableSetOf<Path>()

        for (line in verboseOutput.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val modeChar = trimmed.first()
            if (modeChar != 'l' && modeChar != 'h') {
                val entryToken = trimmed.substringAfterLast(' ').trim()
                if (entryToken.isNotEmpty()) {
                    ensureNoSymlinkAncestor(root, entryToken, declaredSymlinkPaths)
                }
                continue
            }

            val arrowIndex = trimmed.indexOf(" -> ")
            val linkToIndex = trimmed.indexOf(" link to ")
            val (rawLinkPart, rawTarget) = when {
                modeChar == 'l' && arrowIndex > 0 -> trimmed.substring(0, arrowIndex) to trimmed.substring(arrowIndex + 4)
                modeChar == 'h' && linkToIndex > 0 -> trimmed.substring(0, linkToIndex) to trimmed.substring(linkToIndex + 9)
                else -> throw SecurityException("Malformed link entry in runtime archive.")
            }
            val linkName = rawLinkPart.substringAfterLast(' ').trim()
            validateSafeRelativeEntryPath(linkName)
            ensureNoSymlinkAncestor(root, linkName, declaredSymlinkPaths)

            val target = rawTarget.trim()
            require(
                target.isNotEmpty() &&
                    !target.startsWith("/") &&
                    !target.contains('\\') &&
                    target.none { it.code < 0x20 || it.code == 0x7F }
            ) {
                "Unsafe link target in runtime archive."
            }

            val linkRelative = Path.of(linkName.trimEnd('/'))
            val resolved = if (modeChar == 'l') {
                val linkParent = linkRelative.parent
                if (linkParent != null) {
                    root.resolve(linkParent).resolve(target).normalize()
                } else {
                    root.resolve(target).normalize()
                }
            } else {
                // In tar archives, hardlink targets are relative to the extraction root, not linkParent.
                val targetSegments = target.split('/').filter { it.isNotEmpty() }
                require(targetSegments.none { it == ".." }) {
                    "Hardlink target contains path traversal segment."
                }
                root.resolve(target).normalize()
            }
            require(resolved.startsWith(root)) {
                "Archive link escapes extraction root."
            }
            if (modeChar == 'l') {
                declaredSymlinkPaths.add(root.resolve(linkRelative).normalize())
            }
        }
    }

    private fun ensureNoSymlinkAncestor(root: Path, entryPath: String, declaredSymlinkPaths: Set<Path>) {
        if (declaredSymlinkPaths.isEmpty()) return
        var cursor = root.resolve(entryPath.trimEnd('/')).normalize().parent
        while (cursor != null && cursor.startsWith(root) && cursor != root) {
            require(cursor !in declaredSymlinkPaths) {
                "Archive entry traverses through a declared symlink directory."
            }
            cursor = cursor.parent
        }
    }

    private fun validateSafeRelativeEntryPath(entry: String) {
        require(entry.none { it.code < 0x20 || it.code == 0x7F }) {
            "Archive entry contains control characters."
        }
        require(!entry.startsWith("/") && !entry.contains('\\')) {
            "Archive entry uses unsafe absolute path or backslash."
        }
        val segments = entry.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == ".." }) {
            "Archive entry contains path traversal segment."
        }
    }

    private fun locateServerExecutable(stagingDir: File): File? {
        val stagingRoot = stagingDir.canonicalFile.toPath()
        return Files.walk(stagingRoot, 4).use { stream ->
            stream
                .filter { path ->
                    val name = path.fileName?.toString()
                    name == LLAMA_SERVER_NAME &&
                        !Files.isSymbolicLink(path) &&
                        Files.isRegularFile(path) &&
                        path.normalize().startsWith(stagingRoot)
                }
                .map { it.toFile() }
                .findFirst()
                .orElse(null)
        }
    }

    private suspend fun downloadArchive(url: String, archivePart: File, expectedSize: Long) {
        archivePart.delete()
        archivePart.createNewFile()
        LocalModelPaths.setOwnerOnlyPermissions(archivePart, isDirectory = false)

        transport.getStream(url) { response ->
            if (response.statusCode !in 200..299) {
                throw IOException("Runtime download failed with HTTP ${response.statusCode}.")
            }
            val total = response.firstHeader(HttpHeaders.ContentLength)?.toLongOrNull() ?: expectedSize
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var downloaded = 0L
            var lastEmit = 0L

            _runtimeState.value = LocalRuntimeState.Downloading(0L, total)
            FileOutputStream(archivePart, false).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = response.source.readChunk(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 120L) {
                        _runtimeState.value = LocalRuntimeState.Downloading(downloaded, total)
                        lastEmit = now
                    }
                }
                output.flush()
            }
            _runtimeState.value = LocalRuntimeState.Downloading(downloaded, total)
        }
    }

    private fun pruneOldRuntimeVersions(keepTag: String) {
        val children = paths.runtimeDir.listFiles() ?: return
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isDirectory && child.name != keepTag && !child.name.startsWith("staging-")) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    private fun resolveSystemBinary(preferredPath: String, fallbackName: String): String {
        val preferred = File(preferredPath)
        return if (preferred.exists() && preferred.canExecute()) preferred.absolutePath else fallbackName
    }

    override fun close() {
        transport.close()
    }

    companion object {
        private const val LLAMA_SERVER_NAME = "llama-server"
        private const val VERIFIED_MARKER_NAME = ".verified"
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        fun defaultCommandRunner(command: List<String>): CommandOutput {
            val process = ProcessBuilder(command).start()
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            val outThread = Thread({
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        stdoutBuilder.append(reader.readText())
                    }
                }
            }, "llama-cmd-stdout").apply { isDaemon = true; start() }
            val errThread = Thread({
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        stderrBuilder.append(reader.readText())
                    }
                }
            }, "llama-cmd-stderr").apply { isDaemon = true; start() }

            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                runCatching { outThread.join(1_000) }
                runCatching { errThread.join(1_000) }
                return CommandOutput(-1, stdoutBuilder.toString(), "Command timed out.")
            }
            runCatching { outThread.join(1_000) }
            runCatching { errThread.join(1_000) }
            return CommandOutput(process.exitValue(), stdoutBuilder.toString(), stderrBuilder.toString())
        }
    }
}
