package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmJsonUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Manages the on-demand `llama-server` child process on `127.0.0.1` with health polling,
 * in-flight request protection, model hot-swap, idle auto-unload, and guaranteed teardown.
 */
class LlamaServerManager(
    private val runtimeManager: LlamaRuntimeManager,
    private val scope: CoroutineScope,
    healthEngine: HttpClientEngine? = null,
    private val archDetector: () -> MacArchitecture? = { MacArchitecture.detect() },
    private val processStarter: (List<String>, File) -> Process = ::defaultProcessStarter,
    private val portAllocator: () -> Int = ::allocateLoopbackPort,
    private val healthPollIntervalMs: Long = 250L,
    private val startupTimeoutMs: Long = 90_000L,
    private val idleDelayMillisProvider: (Int) -> Long = { mins -> mins * 60_000L }
) : AutoCloseable {

    private data class ActiveProcess(
        val modelId: String,
        val modelName: String,
        val canonicalModelPath: String,
        val contextSize: Int,
        val disableThinking: Boolean,
        val endpointUrl: String,
        val process: Process,
        val recentLogs: ArrayDeque<String>
    )

    private val ownsEngine = healthEngine == null
    private val resolvedHealthEngine: HttpClientEngine = healthEngine ?: CIO.create()

    private val healthClient = HttpClient(resolvedHealthEngine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000L
            requestTimeoutMillis = 2_000L
            socketTimeoutMillis = 2_000L
        }
    }

    private val lifecycleMutex = Mutex()
    private val stateLock = Any()

    private var inFlightRequests: Int = 0
    private var isClosed: Boolean = false
    private var idleUnloadJob: Job? = null

    @Volatile
    private var activeProcess: ActiveProcess? = null

    private val _serverState = MutableStateFlow<LocalServerState>(LocalServerState.Stopped)
    val serverState: StateFlow<LocalServerState> = _serverState.asStateFlow()

    /**
     * Builds exact CLI arguments for launching `llama-server` cleanly without shell invocation.
     */
    internal fun buildCommand(
        executable: File,
        model: ResolvedLocalModel,
        port: Int,
        contextSize: Int,
        arch: MacArchitecture
    ): List<String> {
        val clampedCtx = contextSize.coerceIn(2048, 32768)
        val ngl = if (arch.supportsMetal) "99" else "0"
        return buildList {
            add(executable.absolutePath)
            add("--model")
            add(model.file.absolutePath)
            add("--host")
            add("127.0.0.1")
            add("--port")
            add(port.toString())
            add("--ctx-size")
            add(clampedCtx.toString())
            add("--parallel")
            add("1")
            add("--n-gpu-layers")
            add(ngl)
            add("--flash-attn")
            add(if (arch.supportsMetal) "on" else "auto")
            add("--jinja")
            if (model.disableThinkingWithBudgetZero) {
                add("--reasoning-budget")
                add("0")
            }
        }
    }

    /**
     * Ensures `llama-server` is running [model] before running [block] with its base HTTP URL
     * (`http://127.0.0.1:<port>`), guarding against idle unload until all concurrent callers finish.
     */
    suspend fun <T> withRunningServer(
        model: ResolvedLocalModel,
        config: LlmConfig,
        block: suspend (baseUrl: String) -> T
    ): T {
        synchronized(stateLock) {
            if (isClosed) {
                throw CancellationException("LlamaServerManager is closed.")
            }
            inFlightRequests++
            idleUnloadJob?.cancel()
            idleUnloadJob = null
        }

        return try {
            val baseUrl = lifecycleMutex.withLock {
                ensureServerRunningLocked(model, config)
            }
            block(baseUrl)
        } finally {
            synchronized(stateLock) {
                inFlightRequests = (inFlightRequests - 1).coerceAtLeast(0)
                if (inFlightRequests == 0 && !isClosed) {
                    scheduleIdleUnloadLocked(config.localIdleUnloadMinutes)
                }
            }
        }
    }

    private suspend fun ensureServerRunningLocked(
        model: ResolvedLocalModel,
        config: LlmConfig
    ): String {
        if (synchronized(stateLock) { isClosed }) {
            throw CancellationException("LlamaServerManager is closed.")
        }
        val canonicalModelPath = model.file.canonicalPath
        val clampedCtx = config.localContextSize.coerceIn(2048, 32768)
        val current = activeProcess
        if (current != null &&
            current.process.isAlive &&
            current.canonicalModelPath == canonicalModelPath &&
            current.contextSize == clampedCtx &&
            current.disableThinking == model.disableThinkingWithBudgetZero
        ) {
            _serverState.value = LocalServerState.Running(model.id, model.displayName, current.endpointUrl)
            return current.endpointUrl
        }

        stopLocked()

        val arch = archDetector()
            ?: throw UnsupportedOperationException("Built-in local LLM supports macOS (Apple Silicon / Intel x64) only.")
        _serverState.value = LocalServerState.Starting(model.id, model.displayName)

        return try {
            val executable = runtimeManager.ensureRuntimeInstalled()
            currentCoroutineContext().ensureActive()
            if (synchronized(stateLock) { isClosed }) {
                throw CancellationException("LlamaServerManager is closed.")
            }

            val port = portAllocator()
            val endpointUrl = "http://127.0.0.1:$port"
            val command = buildCommand(executable, model, port, clampedCtx, arch)

            val process = withContext(NonCancellable + Dispatchers.IO) {
                processStarter(command, executable.parentFile)
            }
            val logs = ArrayDeque<String>(MAX_LOG_LINES)
            val active = ActiveProcess(
                modelId = model.id,
                modelName = model.displayName,
                canonicalModelPath = canonicalModelPath,
                contextSize = clampedCtx,
                disableThinking = model.disableThinkingWithBudgetZero,
                endpointUrl = endpointUrl,
                process = process,
                recentLogs = logs
            )
            // Register atomically under stateLock and re-check isClosed/cancellation so stopBlocking()
            // can never miss an in-flight process launch.
            val abortedDuringSpawn = synchronized(stateLock) {
                if (isClosed) {
                    true
                } else {
                    activeProcess = active
                    false
                }
            }
            if (abortedDuringSpawn || !currentCoroutineContext().isActive) {
                stopLocked(fallbackProcess = process)
                throw CancellationException("Local model startup cancelled.")
            }

            drainProcessOutput(process, logs)
            waitForHealthReady(active)
            _serverState.value = LocalServerState.Running(model.id, model.displayName, endpointUrl)
            endpointUrl
        } catch (e: CancellationException) {
            stopLocked()
            throw e
        } catch (e: Exception) {
            stopLocked()
            val msg = LlmJsonUtils.sanitizeLogMessage(e.message)
            System.err.println("[LlamaServerManager] Startup failed: $msg")
            _serverState.value = LocalServerState.Error(msg)
            throw e
        }
    }

    private fun drainProcessOutput(process: Process, logs: ArrayDeque<String>) {
        thread(isDaemon = true, name = "llama-server-logs") {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(logs) {
                            if (logs.size >= MAX_LOG_LINES) {
                                logs.removeFirst()
                            }
                            logs.addLast(LlmJsonUtils.sanitizeLogMessage(line))
                        }
                    }
                }
            }
        }
    }

    private suspend fun waitForHealthReady(active: ActiveProcess) {
        val deadlineMs = System.currentTimeMillis() + startupTimeoutMs
        val healthUrl = "${active.endpointUrl}/health"
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!active.process.isAlive) {
                val exitCode = runCatching { active.process.exitValue() }.getOrNull()
                val tail = synchronized(active.recentLogs) {
                    active.recentLogs.toList().takeLast(4).joinToString(" | ")
                }
                val suffix = if (tail.isNotBlank()) " ($tail)" else ""
                throw IOException("llama-server exited unexpectedly (code=$exitCode)$suffix")
            }

            val healthy = try {
                val resp = healthClient.get(healthUrl)
                resp.status == HttpStatusCode.OK
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (healthy) return

            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() >= deadlineMs) {
                throw IOException("Timed out waiting for local model server to become ready.")
            }
            delay(healthPollIntervalMs)
        }
    }

    private fun scheduleIdleUnloadLocked(idleUnloadMinutes: Int) {
        val delayMs = idleDelayMillisProvider(idleUnloadMinutes)
        if (delayMs <= 0L) return
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(delayMs)
            lifecycleMutex.withLock {
                val shouldStop = synchronized(stateLock) {
                    !isClosed && inFlightRequests == 0
                }
                if (shouldStop) {
                    stopLocked()
                }
            }
        }
    }

    /**
     * Gracefully terminates the active `llama-server` process if running.
     */
    suspend fun stop() {
        synchronized(stateLock) {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
        }
        lifecycleMutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked(fallbackProcess: Process? = null) {
        val current = synchronized(stateLock) {
            activeProcess.also { activeProcess = null }
        }
        val procToKill = current?.process ?: fallbackProcess
        if (procToKill != null) {
            withContext(NonCancellable + Dispatchers.IO) {
                terminateProcess(procToKill, STOP_GRACE_TIMEOUT_MS)
            }
        }
        if (procToKill != null || _serverState.value !is LocalServerState.Error) {
            _serverState.value = LocalServerState.Stopped
        }
    }

    /**
     * Synchronous process teardown safe to invoke from JVM shutdown hooks.
     */
    fun stopBlocking(timeoutMs: Long = STOP_GRACE_TIMEOUT_MS) {
        val current = synchronized(stateLock) {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
            activeProcess.also { activeProcess = null }
        }
        if (current != null) {
            terminateProcess(current.process, timeoutMs)
        }
        _serverState.value = LocalServerState.Stopped
    }

    /**
     * Marks this manager permanently closed and synchronously terminates any active server process.
     */
    fun shutdownBlocking(timeoutMs: Long = STOP_GRACE_TIMEOUT_MS) {
        val current = synchronized(stateLock) {
            isClosed = true
            idleUnloadJob?.cancel()
            idleUnloadJob = null
            activeProcess.also { activeProcess = null }
        }
        if (current != null) {
            terminateProcess(current.process, timeoutMs)
        }
        _serverState.value = LocalServerState.Stopped
    }

    private fun terminateProcess(process: Process, timeoutMs: Long) {
        if (!process.isAlive) return
        runCatching { process.destroy() }
        val exited = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited && process.isAlive) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1_000L, TimeUnit.MILLISECONDS) }
        }
    }

    override fun close() {
        shutdownBlocking()
        healthClient.close()
        if (ownsEngine) {
            runCatching { resolvedHealthEngine.close() }
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 64
        private const val STOP_GRACE_TIMEOUT_MS = 3_000L

        fun allocateLoopbackPort(): Int {
            return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
                socket.localPort
            }
        }

        fun defaultProcessStarter(command: List<String>, workingDir: File): Process {
            return ProcessBuilder(command).apply {
                directory(workingDir)
                redirectErrorStream(true)
            }.start().also { proc ->
                runCatching { proc.outputStream.close() }
            }
        }
    }
}
