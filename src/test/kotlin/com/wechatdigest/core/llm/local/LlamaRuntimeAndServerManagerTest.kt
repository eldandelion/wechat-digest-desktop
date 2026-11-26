package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaRuntimeAndServerManagerTest {

    @TempDir
    lateinit var tempDir: File

    private class FakeProcess(
        private val exitCodeAfterExit: Int = 0,
        logText: String = "",
        var alive: Boolean = true,
        val onDestroy: () -> Unit = {}
    ) : Process() {
        private val inStream = ByteArrayInputStream(logText.toByteArray(Charsets.UTF_8))
        private val errStream = ByteArrayInputStream(ByteArray(0))
        private val outStream = ByteArrayOutputStream()
        var destroyed = false

        override fun getOutputStream(): OutputStream = outStream
        override fun getInputStream(): InputStream = inStream
        override fun getErrorStream(): InputStream = errStream
        override fun waitFor(): Int = exitCodeAfterExit
        override fun exitValue(): Int = exitCodeAfterExit
        override fun isAlive(): Boolean = alive

        override fun destroy() {
            destroyed = true
            alive = false
            onDestroy()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `MacArchitecture detects Apple Silicon, Intel x64, and unsupported OS`() {
        assertEquals(MacArchitecture.ARM64, MacArchitecture.detect("Mac OS X", "aarch64"))
        assertEquals(MacArchitecture.X64, MacArchitecture.detect("Mac OS X", "x86_64"))
        assertEquals(null, MacArchitecture.detect("Linux", "amd64"))
    }

    @Test
    fun `validateTarListing rejects path traversal, absolute paths, and escaping symlinks`() {
        val manager = LlamaRuntimeManager(paths = LocalModelPaths(tempDir))

        manager.validateTarListing(
            listOutput = "build/bin/\nbuild/bin/llama-server\nbuild/bin/libllama.dylib\nbuild/bin/libllama.0.dylib",
            verboseOutput = "drwxr-xr-x 0 user group 0 Nov 1 00:00 build/bin/\n" +
                "-rwxr-xr-x 0 user group 100 Nov 1 00:00 build/bin/llama-server\n" +
                "lrwxr-xr-x 0 user group 0 Nov 1 00:00 build/bin/libllama.0.dylib -> libllama.dylib"
        )

        assertFailsWith<IllegalArgumentException> {
            manager.validateTarListing("../outside.txt", "")
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateTarListing("/etc/passwd", "")
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateTarListing(
                listOutput = "bin/evil",
                verboseOutput = "lrwxr-xr-x 0 user group 0 Nov 1 00:00 bin/evil -> ../../outside"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateTarListing(
                listOutput = "bin/evil",
                verboseOutput = "lrwxr-xr-x 0 user group 0 Nov 1 00:00 bin/evil -> /tmp/escape"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateTarListing(
                listOutput = "a/b/hard",
                verboseOutput = "hrw-r--r-- 0 user group 0 Nov 1 00:00 a/b/hard link to ../../outside"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateTarListing(
                listOutput = "a/b\na/b/c",
                verboseOutput = "lrwxr-xr-x 0 user group 0 Nov 1 00:00 a/b -> ..\n" +
                    "lrwxr-xr-x 0 user group 0 Nov 1 00:00 a/b/c -> outside"
            )
        }
        manager.close()
    }

    @Test
    fun `ensureRuntimeInstalled rejects SHA-256 mismatch before calling tar`() = runBlocking {
        val paths = LocalModelPaths(tempDir)
        val tarInvoked = booleanArrayOf(false)
        val mockEngine = MockEngine {
            respond(ByteReadChannel("corrupt-archive-bytes".toByteArray()), HttpStatusCode.OK)
        }
        val manager = LlamaRuntimeManager(
            paths = paths,
            engine = mockEngine,
            archDetector = { MacArchitecture.ARM64 },
            commandRunner = {
                tarInvoked[0] = true
                CommandOutput(0, "", "")
            }
        )

        assertFailsWith<Exception> {
            manager.ensureRuntimeInstalled()
        }
        assertFalse(tarInvoked[0])
        assertTrue(manager.runtimeState.value is LocalRuntimeState.Failed)
        manager.close()
    }

    @Test
    fun `ensureRuntimeInstalled extracts, verifies, marks ready, and hits fast path on second call`() = runBlocking {
        val archiveBytes = "fake-tar-gz-content".toByteArray()
        val digest = sha256Hex(archiveBytes)
        val release = LlamaRuntimeRelease(
            tag = "b9999",
            arm64AssetName = "llama-b9999-bin-macos-arm64.tar.gz",
            arm64SizeBytes = archiveBytes.size.toLong(),
            arm64Sha256 = digest,
            x64AssetName = "llama-b9999-bin-macos-x64.tar.gz",
            x64SizeBytes = archiveBytes.size.toLong(),
            x64Sha256 = digest
        )

        var httpCalls = 0
        val mockEngine = MockEngine {
            httpCalls++
            respond(ByteReadChannel(archiveBytes), HttpStatusCode.OK)
        }
        val paths = LocalModelPaths(tempDir)
        val runtimeManager = LlamaRuntimeManager(
            paths = paths,
            release = release,
            engine = mockEngine,
            archDetector = { MacArchitecture.ARM64 },
            commandRunner = { cmd ->
                when {
                    "-tzf" in cmd -> CommandOutput(0, "llama-b9999/llama-server\n", "")
                    "-tvzf" in cmd -> CommandOutput(0, "-rwxr-xr-x 0 u g 10 Nov 1 00:00 llama-b9999/llama-server\n", "")
                    "-xzf" in cmd -> {
                        val targetDir = File(cmd[cmd.indexOf("-C") + 1], "llama-b9999").apply { mkdirs() }
                        File(targetDir, "llama-server").apply {
                            writeText("#!/bin/sh\necho ok\n")
                            setExecutable(true)
                        }
                        CommandOutput(0, "", "")
                    }
                    else -> CommandOutput(0, "", "")
                }
            }
        )

        val server1 = runtimeManager.ensureRuntimeInstalled()
        assertTrue(server1.exists())
        assertTrue(server1.canExecute())
        assertTrue(runtimeManager.runtimeState.value is LocalRuntimeState.Ready)
        assertEquals(1, httpCalls)

        val server2 = runtimeManager.ensureRuntimeInstalled()
        assertEquals(server1.canonicalPath, server2.canonicalPath)
        assertEquals(1, httpCalls)
        runtimeManager.close()
    }

    @Test
    fun `LlamaServerManager builds expected Metal and reasoning flags and reuses server`() = runBlocking {
        val paths = LocalModelPaths(tempDir)
        val fakeServerBin = File(paths.runtimeTagDir("b12679").apply { mkdirs() }, "llama-server").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        File(paths.runtimeTagDir("b12679"), ".verified").writeText(
            "b12679|arm64|${LocalModelCatalog.PINNED_RUNTIME.arm64Sha256}"
        )

        val ggufFile = File(tempDir, "model.gguf").apply {
            writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0) + ByteArray(64))
        }
        val resolvedModel = ResolvedLocalModel(
            id = "qwen3-8b-q4km",
            displayName = "Qwen3 8B",
            file = ggufFile,
            disableThinkingWithBudgetZero = true,
            isImported = false
        )

        val runtimeManager = LlamaRuntimeManager(
            paths = paths,
            archDetector = { MacArchitecture.ARM64 }
        )

        var healthChecks = 0
        val healthEngine = MockEngine {
            healthChecks++
            if (healthChecks == 1) {
                respond(ByteReadChannel("{\"status\":\"loading\"}"), HttpStatusCode.ServiceUnavailable)
            } else {
                respond(ByteReadChannel("{\"status\":\"ok\"}"), HttpStatusCode.OK)
            }
        }

        val startedCommands = mutableListOf<List<String>>()
        val spawnedProcesses = mutableListOf<FakeProcess>()
        val serverManager = LlamaServerManager(
            runtimeManager = runtimeManager,
            scope = this,
            healthEngine = healthEngine,
            archDetector = { MacArchitecture.ARM64 },
            processStarter = { cmd, _ ->
                startedCommands.add(cmd)
                FakeProcess().also { spawnedProcesses.add(it) }
            },
            portAllocator = { 18999 },
            healthPollIntervalMs = 5L,
            startupTimeoutMs = 2_000L,
            idleDelayMillisProvider = { 0L }
        )

        val config = LlmConfig(providerType = LlmProviderType.BUILT_IN_LOCAL, localModelId = resolvedModel.id)
        val firstUrl = serverManager.withRunningServer(resolvedModel, config) { url -> url }
        val secondUrl = serverManager.withRunningServer(resolvedModel, config) { url -> url }

        assertEquals("http://127.0.0.1:18999", firstUrl)
        assertEquals(firstUrl, secondUrl)
        assertEquals(1, startedCommands.size)
        val cmd = startedCommands.single()
        assertEquals(fakeServerBin.absolutePath, cmd[0])
        assertTrue("--host" in cmd && "127.0.0.1" in cmd)
        assertTrue("--n-gpu-layers" in cmd && "99" in cmd)
        assertTrue("--flash-attn" in cmd)
        assertEquals("on", cmd[cmd.indexOf("--flash-attn") + 1])
        assertTrue("--reasoning-budget" in cmd && "0" in cmd)
        assertTrue(serverManager.serverState.value is LocalServerState.Running)

        val x64Cmd = serverManager.buildCommand(fakeServerBin, resolvedModel, 18999, 8192, MacArchitecture.X64)
        assertEquals("0", x64Cmd[x64Cmd.indexOf("--n-gpu-layers") + 1])
        assertEquals("auto", x64Cmd[x64Cmd.indexOf("--flash-attn") + 1])

        serverManager.stopBlocking()
        assertTrue(spawnedProcesses.single().destroyed)
        assertTrue(serverManager.serverState.value is LocalServerState.Stopped)
        serverManager.close()
        runtimeManager.close()
    }

    @Test
    fun `LlamaServerManager fails fast if process crashes during startup and unloads when idle`() = runBlocking {
        val paths = LocalModelPaths(tempDir)
        val tagDir = paths.runtimeTagDir("b12679").apply { mkdirs() }
        File(tagDir, "llama-server").apply { writeText("ok"); setExecutable(true) }
        File(tagDir, ".verified").writeText("b12679|arm64|${LocalModelCatalog.PINNED_RUNTIME.arm64Sha256}")

        val runtimeManager = LlamaRuntimeManager(paths = paths, archDetector = { MacArchitecture.ARM64 })
        val ggufFile = File(tempDir, "model.gguf").apply {
            writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0) + ByteArray(64))
        }
        val model = ResolvedLocalModel("m", "Test", ggufFile, false, false)

        // Part A: crash on startup reports error cleanly
        val crashingServer = LlamaServerManager(
            runtimeManager = runtimeManager,
            scope = this,
            healthEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.ServiceUnavailable) },
            archDetector = { MacArchitecture.ARM64 },
            processStarter = { _, _ -> FakeProcess(exitCodeAfterExit = 137, logText = "ggml_metal_init failed", alive = false) },
            portAllocator = { 18998 },
            healthPollIntervalMs = 5L,
            startupTimeoutMs = 500L
        )
        assertFailsWith<Exception> {
            crashingServer.withRunningServer(model, LlmConfig(providerType = LlmProviderType.BUILT_IN_LOCAL)) { }
        }
        assertTrue(crashingServer.serverState.value is LocalServerState.Error)
        crashingServer.close()

        // Part B: idle unload keeps alive while request is in flight, then stops after idle delay
        var liveProcess: FakeProcess? = null
        val idleServer = LlamaServerManager(
            runtimeManager = runtimeManager,
            scope = this,
            healthEngine = MockEngine { respond(ByteReadChannel("{\"status\":\"ok\"}"), HttpStatusCode.OK) },
            archDetector = { MacArchitecture.ARM64 },
            processStarter = { _, _ -> FakeProcess().also { liveProcess = it } },
            portAllocator = { 18997 },
            healthPollIntervalMs = 5L,
            startupTimeoutMs = 1_000L,
            idleDelayMillisProvider = { 40L }
        )

        idleServer.withRunningServer(model, LlmConfig(providerType = LlmProviderType.BUILT_IN_LOCAL, localIdleUnloadMinutes = 1)) {
            delay(80L)
            assertFalse(liveProcess!!.destroyed, "Server should not unload while request is in flight")
        }
        val unloaded = withTimeoutOrNull(5_000L) {
            while (!liveProcess!!.destroyed || idleServer.serverState.value !is LocalServerState.Stopped) {
                delay(20L)
            }
            true
        }
        assertTrue(unloaded == true, "Timed out waiting for idle unload; destroyed=${liveProcess!!.destroyed}, state=${idleServer.serverState.value}")
        assertTrue(liveProcess!!.destroyed, "Idle server process should be destroyed")
        assertTrue(idleServer.serverState.value is LocalServerState.Stopped, "Expected Stopped state, got ${idleServer.serverState.value}")
        idleServer.close()
        runtimeManager.close()
    }

    @Test
    fun `ManagedLocalLlmClient enforces local concurrency 1 and delegates to OpenAI endpoint`() = runBlocking {
        val paths = LocalModelPaths(tempDir)
        val tagDir = paths.runtimeTagDir("b12679").apply { mkdirs() }
        File(tagDir, "llama-server").apply { writeText("ok"); setExecutable(true) }
        File(tagDir, ".verified").writeText("b12679|arm64|${LocalModelCatalog.PINNED_RUNTIME.arm64Sha256}")

        val curated = LocalModelCatalog.CURATED_MODELS.first()
        LocalModelPaths.ensureOwnerOnlyDirectory(paths.modelsDir)
        paths.modelFile(curated).writeBytes(
            byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0) + ByteArray(64)
        )

        val store = LocalModelStore(paths)
        val runtimeManager = LlamaRuntimeManager(paths = paths, archDetector = { MacArchitecture.ARM64 })
        val serverManager = LlamaServerManager(
            runtimeManager = runtimeManager,
            scope = this,
            healthEngine = MockEngine { respond(ByteReadChannel("{\"status\":\"ok\"}"), HttpStatusCode.OK) },
            archDetector = { MacArchitecture.ARM64 },
            processStarter = { _, _ -> FakeProcess() },
            portAllocator = { 19001 },
            healthPollIntervalMs = 5L,
            startupTimeoutMs = 1_000L,
            idleDelayMillisProvider = { 0L }
        )

        var seenUrl: String? = null
        var seenAuthHeader: String? = null
        val completionMock = MockEngine { req ->
            seenUrl = req.url.toString()
            seenAuthHeader = req.headers[HttpHeaders.Authorization]
            respond(
                content = ByteReadChannel("""{"choices":[{"message":{"content":"{\"ok\":true}"},"finish_reason":"stop"}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val config = LlmConfig(
            providerType = LlmProviderType.BUILT_IN_LOCAL,
            localModelId = curated.id,
            apiKey = "stale-secret-key"
        )
        var factoryCreations = 0
        val client = ManagedLocalLlmClient(config, store, serverManager) { cfg ->
            factoryCreations++
            com.wechatdigest.core.llm.KtorLlmClient(cfg, engine = completionMock)
        }

        assertEquals(1, client.recommendedConcurrency)
        val reply = client.chatCompletion("sys", "user")
        val reply2 = client.chatCompletion("sys", "user2")
        assertEquals("{\"ok\":true}", reply)
        assertEquals("{\"ok\":true}", reply2)
        assertEquals(1, factoryCreations, "ManagedLocalLlmClient must cache KtorLlmClient across calls")
        assertEquals("http://127.0.0.1:19001/v1/chat/completions", seenUrl)
        assertEquals(null, seenAuthHeader)

        // Direct KtorLlmClient with BUILT_IN_LOCAL must refuse remote endpoints fail-closed.
        val remoteBuiltIn = com.wechatdigest.core.llm.KtorLlmClient(
            LlmConfig(
                providerType = LlmProviderType.BUILT_IN_LOCAL,
                endpointUrl = "https://api.openai.com/v1",
                apiKey = "secret"
            ),
            engine = completionMock
        )
        assertFailsWith<IllegalStateException> {
            remoteBuiltIn.chatCompletion("sys", "user")
        }
        remoteBuiltIn.close()
        client.close()
        serverManager.close()
        runtimeManager.close()
    }
}
