package com.wechatdigest.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaServiceTest {

    private val sampleTagsJson = """
        {
          "models": [
            {
              "name": "llama3.2:latest",
              "model": "llama3.2:latest",
              "modified_at": "2026-03-30T14:22:10.123456789Z",
              "size": 2019393189,
              "digest": "365c0bd3c000a23d653ef9d3b26fa589dd933488219c6cf2cb7a61d67eb1ee14",
              "details": {
                "parent_model": "",
                "format": "gguf",
                "family": "llama",
                "families": ["llama"],
                "parameter_size": "3.2B",
                "quantization_level": "Q4_K_M"
              }
            },
            {
              "name": "qwen2.5:7b",
              "model": "qwen2.5:7b",
              "size": 4700000000,
              "details": {
                "parameter_size": "7.6B"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseOllamaTagsResponse() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("/api/tags", request.url.encodedPath)
            respond(
                content = ByteReadChannel(sampleTagsJson),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val service = OllamaService(engine = mockEngine)
        val models = service.listModels("http://localhost:11434")

        assertEquals(2, models.size)
        assertEquals("llama3.2:latest", models[0].name)
        assertEquals("3.2B", models[0].parameterSize)
        assertEquals(2019393189L, models[0].sizeBytes)
        assertEquals("3.2B • 1.9 GB", models[0].subtitle)

        assertEquals("qwen2.5:7b", models[1].name)
        assertEquals("7.6B", models[1].parameterSize)
        assertEquals("7.6B • 4.4 GB", models[1].subtitle)
        service.close()
    }

    @Test
    fun testFormatBytes() {
        assertEquals("1.9 GB", LocalOllamaModel.formatBytes(2019393189L))
        assertEquals("500 MB", LocalOllamaModel.formatBytes(524288000L))
        val modelWithoutSize = LocalOllamaModel(name = "test")
        assertEquals("", modelWithoutSize.subtitle)
        val modelOnlyParam = LocalOllamaModel(name = "test", parameterSize = "8B")
        assertEquals("8B", modelOnlyParam.subtitle)
    }

    @Test
    fun testEmptyModelsResponse() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("""{"models": []}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val service = OllamaService(engine = mockEngine)
        val result = service.detectLocalOllama()

        assertTrue(result is OllamaDiscoveryResult.NoModels)
        assertEquals("http://localhost:11434", result.endpointUrl)
        service.close()
    }

    @Test
    fun testHttpServerError() = runTest {
        val mockEngine = MockEngine {
            respondError(HttpStatusCode.InternalServerError)
        }

        val service = OllamaService(engine = mockEngine)
        val result = service.detectLocalOllama()

        assertTrue(result is OllamaDiscoveryResult.Unreachable)
        assertEquals(2, result.attemptedUrls.size)
        service.close()
    }

    @Test
    fun testLocalhostFallbackTo127() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.host == "localhost") {
                throw IOException("Connection refused to ::1 localhost")
            } else {
                respond(
                    content = ByteReadChannel(sampleTagsJson),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }

        val service = OllamaService(engine = mockEngine)
        val result = service.detectLocalOllama()

        assertTrue(result is OllamaDiscoveryResult.Success)
        assertEquals("http://127.0.0.1:11434", result.endpointUrl)
        assertEquals(2, result.models.size)
        service.close()
    }

    @Test
    fun testNormalizeBaseUrl() {
        val service = OllamaService(engine = MockEngine { respond("") })
        assertEquals("http://localhost:11434", service.normalizeBaseUrl("http://localhost:11434/"))
        assertEquals("http://localhost:11434", service.normalizeBaseUrl("http://localhost:11434/api/tags/"))
        assertEquals("http://localhost:11434", service.normalizeBaseUrl("http://localhost:11434/api/chat"))
        assertEquals("http://custom:11434", service.normalizeBaseUrl("  http://custom:11434/api/tags  "))
        service.close()
    }
}
