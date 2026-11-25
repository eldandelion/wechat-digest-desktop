package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.LlmJsonUtils
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads curated GGUF models from Hugging Face, ModelScope, or HF-Mirror with HTTP `Range`
 * resume, pre-flight `X-Linked-Etag` / disk checks, full SHA-256 verification, and atomic placement.
 */
class LocalModelDownloadManager(
    private val store: LocalModelStore,
    engine: HttpClientEngine? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val minDiskBufferBytes: Long = DEFAULT_DISK_BUFFER_BYTES
) : AutoCloseable {

    private val transport: LocalHttpTransport = createLocalHttpTransport(engine)

    private val _downloadStates = MutableStateFlow<Map<String, LocalModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, LocalModelDownloadState>> = _downloadStates.asStateFlow()

    private val jobsLock = Any()
    private val inFlightModelIds = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Launches an asynchronous download for [spec] on [scope], deduplicated atomically per model ID.
     */
    fun startDownload(
        scope: CoroutineScope,
        spec: LocalModelSpec,
        mirror: LocalModelMirror,
        onCompleted: ((Result<File>) -> Unit)? = null
    ): Job {
        val jobToStart = synchronized(jobsLock) {
            activeJobs[spec.id]?.takeIf { it.isActive }?.let { existing -> return existing }
            lateinit var createdJob: Job
            createdJob = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                val result = downloadModel(spec, mirror)
                onCompleted?.invoke(result)
            }
            activeJobs[spec.id] = createdJob
            createdJob.invokeOnCompletion { activeJobs.remove(spec.id, createdJob) }
            createdJob
        }
        jobToStart.start()
        return jobToStart
    }

    fun cancelDownload(modelId: String): Job? {
        return synchronized(jobsLock) {
            activeJobs.remove(modelId)
        }?.also { it.cancel() }
    }

    fun cancelAll() {
        val jobs = synchronized(jobsLock) {
            val copy = activeJobs.values.toList()
            activeJobs.clear()
            copy
        }
        jobs.forEach { it.cancel() }
    }

    /**
     * Downloads [spec] from [mirror] using HTTP `Range` resumption, validates SHA-256 and GGUF
     * magic header, and atomically activates it inside [LocalModelPaths.modelsDir].
     */
    suspend fun downloadModel(
        spec: LocalModelSpec,
        mirror: LocalModelMirror
    ): Result<File> = withContext(dispatcher) {
        if (!inFlightModelIds.add(spec.id)) {
            return@withContext Result.failure(IllegalStateException("Download already in progress for ${spec.displayName}."))
        }

        try {
            LocalModelPaths.ensureOwnerOnlyDirectory(store.paths.modelsDir)
            val finalFile = store.paths.modelFile(spec)
            if (store.validateGgufFile(finalFile) &&
                sha256HexEqualsConstantTime(computeFileSha256HexCancellable(finalFile), spec.sha256)
            ) {
                _downloadStates.update { it - spec.id }
                store.refresh()
                return@withContext Result.success(finalFile)
            }

            val partFile = store.paths.partialModelFile(spec)
            if (partFile.exists() && partFile.isFile && partFile.length() > spec.sizeBytes) {
                partFile.delete()
            }
            val existingPartBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
            val remainingBytes = (spec.sizeBytes - existingPartBytes).coerceAtLeast(0L)
            val freeSpace = store.paths.modelsDir.usableSpace
            if (freeSpace > 0L && freeSpace < remainingBytes + minDiskBufferBytes) {
                val needed = formatModelBytes(remainingBytes + minDiskBufferBytes)
                val available = formatModelBytes(freeSpace)
                val msg = "Not enough free disk space (need $needed, $available available)."
                _downloadStates.update { it + (spec.id to LocalModelDownloadState.Failed(msg)) }
                return@withContext Result.failure(IOException(msg))
            }

            val url = LocalModelCatalog.downloadUrl(spec, mirror)
            verifyRemoteMetadataIfPresent(url, spec)

            downloadWithResume(url, spec, partFile)

            val actualLength = partFile.length()
            if (actualLength < spec.sizeBytes) {
                throw IOException(
                    "Download stopped early (${formatModelBytes(actualLength)} / ${formatModelBytes(spec.sizeBytes)}). Click Download to resume."
                )
            }
            if (actualLength > spec.sizeBytes) {
                partFile.delete()
                throw IOException("Downloaded file size exceeded expected length for ${spec.filename}.")
            }

            currentCoroutineContext().ensureActive()
            _downloadStates.update { it + (spec.id to LocalModelDownloadState.Verifying) }
            val actualSha = computeFileSha256HexCancellable(partFile)
            if (!sha256HexEqualsConstantTime(actualSha, spec.sha256)) {
                partFile.delete()
                throw IOException("SHA-256 verification failed for ${spec.filename}.")
            }
            if (!store.validateGgufFile(partFile)) {
                partFile.delete()
                throw IOException("Downloaded file failed GGUF header validation.")
            }

            currentCoroutineContext().ensureActive()
            LocalModelPaths.atomicMove(partFile, finalFile)
            store.refresh()
            _downloadStates.update { it - spec.id }
            Result.success(finalFile)
        } catch (e: CancellationException) {
            _downloadStates.update { it - spec.id }
            throw e
        } catch (e: Exception) {
            val sanitized = LlmJsonUtils.sanitizeLogMessage(e.message)
            System.err.println("[LocalModelDownloadManager] Download failed for ${spec.id}: $sanitized")
            _downloadStates.update { it + (spec.id to LocalModelDownloadState.Failed(sanitized)) }
            Result.failure(e)
        } finally {
            inFlightModelIds.remove(spec.id)
        }
    }

    private suspend fun verifyRemoteMetadataIfPresent(url: String, spec: LocalModelSpec) {
        val response = transport.headNoRedirects(url) ?: return
        val remoteEtag = response.firstHeader("X-Linked-Etag")
            ?.trim()
            ?.trim('"')
            ?.lowercase()
        if (!remoteEtag.isNullOrBlank() && SHA256_HEX_REGEX.matches(remoteEtag)) {
            if (!sha256HexEqualsConstantTime(remoteEtag, spec.sha256)) {
                throw IOException("Remote file checksum mismatch before download for ${spec.filename}.")
            }
        }
        val remoteSize = response.firstHeader("X-Linked-Size")?.trim()?.toLongOrNull()
        if (remoteSize != null && remoteSize > 0L && remoteSize != spec.sizeBytes) {
            throw IOException("Remote file size mismatch for ${spec.filename} (expected ${spec.sizeBytes}, got $remoteSize).")
        }
    }

    private suspend fun downloadWithResume(
        url: String,
        spec: LocalModelSpec,
        partFile: File
    ) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val existingOffset = if (partFile.exists() && partFile.isFile) {
                val len = partFile.length()
                if (len in 1 until spec.sizeBytes) len else 0L
            } else {
                0L
            }
            if (partFile.exists() && partFile.length() == spec.sizeBytes) {
                return
            }

            val reqHeaders = if (existingOffset > 0L) {
                mapOf(HttpHeaders.Range to "bytes=$existingOffset-")
            } else {
                emptyMap()
            }

            var retryFromZero = false
            transport.getStream(url, reqHeaders) { response ->
                when (response.statusCode) {
                    HttpStatusCode.RequestedRangeNotSatisfiable.value -> {
                        partFile.delete()
                        if (attempt >= 1) {
                            throw IOException("Server rejected byte range for ${spec.filename}.")
                        }
                        retryFromZero = true
                    }
                    HttpStatusCode.PartialContent.value -> {
                        val rangeHeader = response.firstHeader(HttpHeaders.ContentRange)
                        val rangeStart = parseContentRangeStart(rangeHeader)
                        val appendMode = when {
                            rangeStart == existingOffset && existingOffset > 0L -> true
                            rangeStart == 0L -> false
                            else -> throw IOException("Unexpected Content-Range header: $rangeHeader")
                        }
                        val startingBytes = if (appendMode) existingOffset else 0L
                        val totalBytes = parseContentRangeTotal(rangeHeader) ?: spec.sizeBytes
                        streamBodyToPartFile(spec.id, response.source, partFile, appendMode, startingBytes, totalBytes)
                    }
                    in 200..299 -> {
                        val headerLen = response.firstHeader(HttpHeaders.ContentLength)?.toLongOrNull()
                        val totalBytes = headerLen ?: spec.sizeBytes
                        streamBodyToPartFile(spec.id, response.source, partFile, appendMode = false, startingBytes = 0L, totalBytes = totalBytes)
                    }
                    else -> {
                        throw IOException("Download HTTP ${response.statusCode} for ${spec.filename}.")
                    }
                }
            }

            if (!retryFromZero) return
            attempt++
        }
    }

    private suspend fun streamBodyToPartFile(
        modelId: String,
        source: LocalHttpByteSource,
        partFile: File,
        appendMode: Boolean,
        startingBytes: Long,
        totalBytes: Long
    ) {
        if (!partFile.exists()) {
            partFile.createNewFile()
        }
        LocalModelPaths.setOwnerOnlyPermissions(partFile, isDirectory = false)

        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var bytesWritten = startingBytes
        var lastEmitMs = 0L
        val startedMs = System.currentTimeMillis()
        val initialBytes = startingBytes

        _downloadStates.update {
            it + (modelId to LocalModelDownloadState.Downloading(bytesWritten, totalBytes, 0L))
        }

        FileOutputStream(partFile, appendMode).use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.readChunk(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                bytesWritten += read

                val now = System.currentTimeMillis()
                if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                    val elapsedSec = ((now - startedMs).coerceAtLeast(1L)) / 1000.0
                    val bytesPerSec = ((bytesWritten - initialBytes) / elapsedSec).toLong().coerceAtLeast(0L)
                    _downloadStates.update {
                        it + (modelId to LocalModelDownloadState.Downloading(bytesWritten, totalBytes, bytesPerSec))
                    }
                    lastEmitMs = now
                }
            }
            output.flush()
        }
        _downloadStates.update {
            it + (modelId to LocalModelDownloadState.Downloading(bytesWritten, totalBytes, 0L))
        }
    }

    private fun parseContentRangeStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val match = CONTENT_RANGE_REGEX.find(header.trim()) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val match = CONTENT_RANGE_REGEX.find(header.trim()) ?: return null
        return match.groupValues[3].toLongOrNull()
    }

    override fun close() {
        cancelAll()
        transport.close()
    }

    companion object {
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 150L
        private const val DEFAULT_DISK_BUFFER_BYTES = 512L * 1024 * 1024
        private val SHA256_HEX_REGEX = Regex("^[a-f0-9]{64}$")
        private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
    }
}
