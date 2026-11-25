package com.wechatdigest.core.llm.local

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelDownloadAndStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun validGgufPayload(extraSize: Int = 128): ByteArray {
        val header = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0)
        val body = ByteArray(extraSize) { (it * 31 + 7).toByte() }
        return header + body
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun testSpec(payload: ByteArray, id: String = "test-model", filename: String = "test-model.gguf"): LocalModelSpec =
        LocalModelSpec(
            id = id,
            displayName = "Test Model",
            parameterLabel = "3B",
            quantization = "Q4_K_M",
            repoId = "test-org/test-repo",
            filename = filename,
            sizeBytes = payload.size.toLong(),
            sha256 = sha256Hex(payload),
            minRamGb = 8,
            recommendedRamGb = 8,
            description = "Test GGUF spec"
        )

    @Test
    fun `validateGgufFile accepts valid header and rejects invalid files`() {
        val paths = LocalModelPaths(tempDir)
        val store = LocalModelStore(paths)

        val valid = File(tempDir, "valid.gguf").apply { writeBytes(validGgufPayload()) }
        val wrongMagic = File(tempDir, "wrong.gguf").apply { writeBytes("NOTGGUF_0123456789abcdef".toByteArray()) }
        val tooSmall = File(tempDir, "tiny.gguf").apply { writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 1)) }

        assertTrue(store.validateGgufFile(valid))
        assertFalse(store.validateGgufFile(wrongMagic))
        assertFalse(store.validateGgufFile(tooSmall))
        assertFalse(store.validateGgufFile(File(tempDir, "missing.gguf")))
    }

    @Test
    fun `importGgufFile validates, deduplicates, persists, and removeImportedModel keeps external file`() {
        val paths = LocalModelPaths(tempDir)
        val externalDir = File(tempDir, "external").apply { mkdirs() }
        val externalModel = File(externalDir, "my-custom-qwen.gguf").apply { writeBytes(validGgufPayload(256)) }
        val notGguf = File(externalDir, "model.bin").apply { writeBytes(validGgufPayload(256)) }

        val store = LocalModelStore(paths)
        assertTrue(store.importGgufFile(notGguf).isFailure)

        val firstImport = store.importGgufFile(externalModel).getOrThrow()
        assertEquals("my-custom-qwen", firstImport.displayName)
        assertEquals(externalModel.canonicalPath, firstImport.filePath)
        assertEquals(1, store.importedModels.value.size)

        // Importing same file again returns existing entry without duplicating.
        val secondImport = store.importGgufFile(externalModel).getOrThrow()
        assertEquals(firstImport.id, secondImport.id)
        assertEquals(1, store.importedModels.value.size)

        // Reload from disk in fresh store instance.
        val reloadedStore = LocalModelStore(paths)
        val resolved = reloadedStore.resolveModel(firstImport.id)
        assertNotNull(resolved)
        assertEquals(externalModel.canonicalPath, resolved.file.canonicalPath)

        // Removing registration leaves user's external .gguf file intact.
        assertTrue(reloadedStore.removeImportedModel(firstImport.id))
        assertTrue(reloadedStore.importedModels.value.isEmpty())
        assertTrue(externalModel.exists())
        assertNull(reloadedStore.resolveModel(firstImport.id))
    }

    @Test
    fun `catalog discovery and deleteCatalogModel manage files strictly inside modelsDir`() {
        val paths = LocalModelPaths(tempDir)
        LocalModelPaths.ensureOwnerOnlyDirectory(paths.modelsDir)
        val curated = LocalModelCatalog.CURATED_MODELS.first()
        val targetFile = paths.modelFile(curated).apply { writeBytes(validGgufPayload(64)) }
        val partFile = paths.partialModelFile(curated).apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val store = LocalModelStore(paths)
        assertTrue(curated.id in store.downloadedModelIds.value)
        assertNotNull(store.resolveModel(curated.id))

        assertTrue(store.deleteCatalogModel(curated.id))
        assertFalse(targetFile.exists())
        assertFalse(partFile.exists())
        assertFalse(curated.id in store.downloadedModelIds.value)
    }

    @Test
    fun `downloadModel succeeds with 200 OK, verifies SHA-256, and removes part file`() = runTest {
        val payload = validGgufPayload(2048)
        val spec = testSpec(payload)
        val paths = LocalModelPaths(tempDir)
        val store = LocalModelStore(paths)

        val mockEngine = MockEngine { request ->
            assertEquals("hf-mirror.com", request.url.host)
            assertEquals("/test-org/test-repo/resolve/main/test-model.gguf", request.url.encodedPath)
            if (request.method == HttpMethod.Head) {
                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        "X-Linked-Etag" to listOf("\"${spec.sha256}\""),
                        "X-Linked-Size" to listOf("${spec.sizeBytes}")
                    )
                )
            } else {
                respond(
                    content = ByteReadChannel(payload),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                )
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val downloaded = manager.downloadModel(spec, LocalModelMirror.HF_MIRROR).getOrThrow()

        assertTrue(downloaded.exists())
        assertTrue(downloaded.readBytes().contentEquals(payload))
        assertFalse(paths.partialModelFile(spec).exists())
        assertTrue(manager.downloadStates.value.isEmpty())
        manager.close()
    }

    @Test
    fun `downloadModel resumes existing partial file via HTTP Range 206`() = runTest {
        val payload = validGgufPayload(1024)
        val spec = testSpec(payload)
        val paths = LocalModelPaths(tempDir)
        LocalModelPaths.ensureOwnerOnlyDirectory(paths.modelsDir)
        val splitPoint = 300
        paths.partialModelFile(spec).writeBytes(payload.copyOfRange(0, splitPoint))

        val store = LocalModelStore(paths)
        var seenRangeHeader: String? = null
        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            } else {
                seenRangeHeader = request.headers[HttpHeaders.Range]
                val slice = payload.copyOfRange(splitPoint, payload.size)
                respond(
                    content = ByteReadChannel(slice),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange,
                        "bytes $splitPoint-${payload.size - 1}/${payload.size}"
                    )
                )
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val result = manager.downloadModel(spec, LocalModelMirror.HF_OFFICIAL).getOrThrow()

        assertEquals("bytes=$splitPoint-", seenRangeHeader)
        assertTrue(result.readBytes().contentEquals(payload))
        assertFalse(paths.partialModelFile(spec).exists())
        manager.close()
    }

    @Test
    fun `downloadModel truncates cleanly when server ignores Range with 200 OK and retries on 416`() = runTest {
        val payload = validGgufPayload(512)
        val spec = testSpec(payload)
        val paths = LocalModelPaths(tempDir)
        LocalModelPaths.ensureOwnerOnlyDirectory(paths.modelsDir)
        paths.partialModelFile(spec).writeBytes(payload.copyOfRange(0, 128))
        val store = LocalModelStore(paths)

        var getCount = 0
        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            } else {
                getCount++
                if (getCount == 1) {
                    respond(ByteReadChannel(""), HttpStatusCode.RequestedRangeNotSatisfiable)
                } else {
                    respond(
                        content = ByteReadChannel(payload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                    )
                }
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val result = manager.downloadModel(spec, LocalModelMirror.HF_OFFICIAL).getOrThrow()

        assertEquals(2, getCount)
        assertTrue(result.readBytes().contentEquals(payload))
        manager.close()
    }

    @Test
    fun `downloadModel fails closed on SHA-256 mismatch and deletes corrupt partial`() = runTest {
        val payload = validGgufPayload(512)
        val spec = testSpec(payload)
        val corrupted = payload.copyOf().apply { this[size - 1] = (this[size - 1].toInt() xor 0xFF).toByte() }
        val paths = LocalModelPaths(tempDir)
        val store = LocalModelStore(paths)

        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            } else {
                respond(ByteReadChannel(corrupted), HttpStatusCode.OK)
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val result = manager.downloadModel(spec, LocalModelMirror.HF_OFFICIAL)

        assertTrue(result.isFailure)
        assertFalse(paths.modelFile(spec).exists())
        assertFalse(paths.partialModelFile(spec).exists())
        assertTrue(manager.downloadStates.value[spec.id] is LocalModelDownloadState.Failed)
        manager.close()
    }

    @Test
    fun `downloadModel fails pre-flight when X-Linked-Etag disagrees with pinned SHA-256`() = runTest {
        val payload = validGgufPayload(512)
        val spec = testSpec(payload)
        val paths = LocalModelPaths(tempDir)
        val store = LocalModelStore(paths)
        val wrongEtag = "0".repeat(64)

        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.Found,
                    headers = headersOf("X-Linked-Etag" to listOf("\"$wrongEtag\""))
                )
            } else {
                error("GET should not be called when X-Linked-Etag fails pre-flight check")
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val result = manager.downloadModel(spec, LocalModelMirror.HF_OFFICIAL)

        assertTrue(result.isFailure)
        assertFalse(Files.exists(paths.modelFile(spec).toPath()))
        manager.close()
    }

    @Test
    fun `downloadModel preserves partial file when HTTP stream truncates early`() = runTest {
        val payload = validGgufPayload(1024)
        val spec = testSpec(payload)
        val truncatedSlice = payload.copyOfRange(0, 400)
        val paths = LocalModelPaths(tempDir)
        val store = LocalModelStore(paths)

        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            } else {
                respond(
                    content = ByteReadChannel(truncatedSlice),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                )
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val result = manager.downloadModel(spec, LocalModelMirror.HF_OFFICIAL)

        assertTrue(result.isFailure)
        assertFalse(paths.modelFile(spec).exists())
        // Truncated `.part` must be preserved for HTTP Range resumption instead of deleted.
        assertTrue(paths.partialModelFile(spec).exists())
        assertEquals(400L, paths.partialModelFile(spec).length())
        manager.close()
    }

    @Test
    fun `ModelScope mirror builds expected master URL and proxy selector reads macOS scutil output`() {
        val spec = LocalModelCatalog.CURATED_MODELS.first()
        assertEquals(
            "https://modelscope.cn/models/${spec.repoId}/resolve/master/${spec.filename}",
            LocalModelCatalog.downloadUrl(spec, LocalModelMirror.MODELSCOPE)
        )

        val scutilText = """
            <dictionary> {
              ExceptionsList : <array> {
                0 : *jd.com
                1 : localhost
                2 : 192.168.*
              }
              HTTPEnable : 1
              HTTPPort : 7890
              HTTPProxy : 127.0.0.1
              HTTPSEnable : 1
              HTTPSPort : 7890
              HTTPSProxy : 127.0.0.1
              SOCKSEnable : 1
              SOCKSPort : 7890
              SOCKSProxy : 127.0.0.1
            }
        """.trimIndent()

        val selector = SystemAwareHttpProxySelector(
            envProvider = { null },
            osNameProvider = { "Mac OS X" },
            scutilRunner = { scutilText },
            defaultSelectorProvider = { null }
        )

        val hfProxies = selector.select(java.net.URI.create("https://huggingface.co/test"))
        assertEquals(1, hfProxies.size)
        assertEquals(java.net.Proxy.Type.HTTP, hfProxies.first().type())
        val addr = hfProxies.first().address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", addr.hostString)
        assertEquals(7890, addr.port)

        assertEquals(listOf(java.net.Proxy.NO_PROXY), selector.select(java.net.URI.create("http://127.0.0.1:8080/health")))
        assertEquals(listOf(java.net.Proxy.NO_PROXY), selector.select(java.net.URI.create("https://item.jd.com/1")))
    }

    @Test
    fun `JavaNetLocalHttpTransport follows 308 redirects and resumes Range 206 streams`() = kotlinx.coroutines.runBlocking {
        val payload = validGgufPayload(1024)
        val shaHex = sha256Hex(payload)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/redirect") { exchange ->
                val target = "http://127.0.0.1:${server.address.port}/file.gguf"
                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("X-Linked-Etag", "\"$shaHex\"")
                    exchange.responseHeaders.add("X-Linked-Size", payload.size.toString())
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    exchange.responseHeaders.add("Location", target)
                    exchange.sendResponseHeaders(308, -1)
                }
                exchange.close()
            }
            server.createContext("/file.gguf") { exchange ->
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val start = rangeHeader.removePrefix("bytes=").substringBefore('-').toInt()
                    val slice = payload.copyOfRange(start, payload.size)
                    exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.use { it.write(slice) }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
                exchange.close()
            }
            server.start()

            val baseUrl = "http://127.0.0.1:${server.address.port}/redirect"
            val transport = JavaNetLocalHttpTransport()
            val head = transport.headNoRedirects(baseUrl)
            assertNotNull(head)
            assertEquals(302, head.statusCode)
            assertEquals("\"$shaHex\"", head.firstHeader("X-Linked-Etag"))

            val out = java.io.ByteArrayOutputStream()
            transport.getStream(baseUrl, mapOf("Range" to "bytes=256-")) { stream ->
                assertEquals(206, stream.statusCode)
                val buf = ByteArray(128)
                while (true) {
                    val read = stream.source.readChunk(buf)
                    if (read < 0) break
                    if (read > 0) out.write(buf, 0, read)
                }
            }
            assertTrue(out.toByteArray().contentEquals(payload.copyOfRange(256, payload.size)))
            transport.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `startDownload immediately emits Starting state before network initialization completes`() = kotlinx.coroutines.runBlocking {
        val payload = validGgufPayload(256)
        val spec = testSpec(payload)
        val paths = LocalModelPaths(tempDir)
        val store = LocalModelStore(paths)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                gate.await()
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            } else {
                respond(
                    content = ByteReadChannel(payload),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, payload.size.toString())
                )
            }
        }

        val manager = LocalModelDownloadManager(store, engine = mockEngine, minDiskBufferBytes = 0L)
        val pausedScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
        val job = manager.startDownload(pausedScope, spec, LocalModelMirror.HF_OFFICIAL)
        // Synchronous Starting state must be visible immediately after startDownload returns.
        assertEquals(LocalModelDownloadState.Starting, manager.downloadStates.value[spec.id])

        // Cancelling during Starting must cleanly clear download state back to idle/absent.
        manager.cancelDownload(spec.id)
        job.join()
        assertFalse(spec.id in manager.downloadStates.value)
        gate.complete(Unit)
        manager.close()
    }
}
