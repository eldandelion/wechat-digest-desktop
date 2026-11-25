package com.wechatdigest.core.llm.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.util.toMap
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class LocalHttpHeadResult(
    val statusCode: Int,
    val headers: Map<String, List<String>>
) {
    fun firstHeader(name: String): String? {
        return headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }
}

internal interface LocalHttpByteSource {
    suspend fun readChunk(buffer: ByteArray): Int
}

internal data class LocalHttpStreamResult(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val source: LocalHttpByteSource
) {
    fun firstHeader(name: String): String? {
        return headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }
}

/**
 * Abstraction over streaming HTTP downloads so production uses JDK 21 [JdkHttpClient] with
 * macOS system proxy support while tests can inject Ktor `MockEngine`.
 */
internal interface LocalHttpTransport : AutoCloseable {
    suspend fun headNoRedirects(url: String): LocalHttpHeadResult?
    suspend fun <T> getStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        block: suspend (LocalHttpStreamResult) -> T
    ): T
}

internal fun createLocalHttpTransport(engine: HttpClientEngine?): LocalHttpTransport {
    return if (engine != null) {
        KtorEngineLocalHttpTransport(engine)
    } else {
        JavaNetLocalHttpTransport()
    }
}

/**
 * Production HTTP transport backed by JDK 21 `java.net.http.HttpClient` honoring macOS System Proxy
 * (HTTP/HTTPS proxies such as Clash/Surge), `HTTPS_PROXY` env vars, and `302`/`308` redirects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class JavaNetLocalHttpTransport(
    private val proxySelector: ProxySelector = SystemAwareHttpProxySelector()
) : LocalHttpTransport {

    private val headClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .followRedirects(JdkHttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(proxySelector)
        .build()

    private val streamClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .followRedirects(JdkHttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(proxySelector)
        .build()

    override suspend fun headNoRedirects(url: String): LocalHttpHeadResult? {
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", DEFAULT_USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .build()
            val response = sendCancellable(headClient, request, HttpResponse.BodyHandlers.discarding())
            LocalHttpHeadResult(response.statusCode(), response.headers().map())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun <T> getStream(
        url: String,
        headers: Map<String, String>,
        block: suspend (LocalHttpStreamResult) -> T
    ): T {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("User-Agent", DEFAULT_USER_AGENT)
        headers.forEach { (key, value) ->
            builder.header(key, value)
        }
        val response = withTimeout(HEADER_TIMEOUT_MS) {
            sendCancellable(streamClient, builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }
        val stream = response.body()
        return try {
            val source = object : LocalHttpByteSource {
                override suspend fun readChunk(buffer: ByteArray): Int {
                    currentCoroutineContext().ensureActive()
                    return stream.read(buffer)
                }
            }
            block(LocalHttpStreamResult(response.statusCode(), response.headers().map(), source))
        } finally {
            runCatching { stream.close() }
        }
    }

    private suspend fun <T> sendCancellable(
        client: JdkHttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> = suspendCancellableCoroutine { cont ->
        val future = client.sendAsync(request, handler)
        cont.invokeOnCancellation {
            future.cancel(true)
        }
        future.whenComplete { response, error ->
            if (error != null) {
                val cause = (error as? CompletionException)?.cause ?: error
                if (cont.isActive) {
                    cont.resumeWithException(cause)
                } else {
                    closeIfClosable(response?.body())
                }
            } else if (cont.isActive) {
                cont.resume(response) { _, value, _ ->
                    closeIfClosable(value.body())
                }
            } else {
                closeIfClosable(response?.body())
            }
        }
    }

    private fun closeIfClosable(body: Any?) {
        if (body is InputStream) {
            runCatching { body.close() }
        }
    }

    override fun close() {
        // JdkHttpClient manages its internal executor/pool cleanly when dereferenced.
    }

    companion object {
        private const val HEADER_TIMEOUT_MS = 30_000L
        private const val DEFAULT_USER_AGENT = "WeChatDigest/1.0"
    }
}

/**
 * Resolves HTTP proxies cleanly on macOS even when JVM started before `java.net.useSystemProxies`
 * took effect, ignoring SOCKS-only entries that `java.net.http.HttpClient` cannot use.
 */
internal class SystemAwareHttpProxySelector(
    private val envProvider: (String) -> String? = { System.getenv(it) },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val scutilRunner: () -> String? = ::readMacScutilProxy,
    private val defaultSelectorProvider: () -> ProxySelector? = { runCatching { getDefault() }.getOrNull() }
) : ProxySelector() {

    init {
        runCatching {
            if (System.getProperty("java.net.useSystemProxies").isNullOrBlank()) {
                System.setProperty("java.net.useSystemProxies", "true")
            }
        }
    }

    @Volatile
    private var cachedScutilConfig: Pair<Long, ScutilProxyConfig>? = null

    override fun select(uri: URI?): List<Proxy> {
        val target = uri ?: return listOf(Proxy.NO_PROXY)
        val host = target.host?.lowercase(Locale.ROOT) ?: return listOf(Proxy.NO_PROXY)
        if (isLoopbackHost(host)) {
            return listOf(Proxy.NO_PROXY)
        }

        val jvmProxies = runCatching { defaultSelectorProvider()?.select(target) }.getOrNull().orEmpty()
        val httpProxies = jvmProxies.filter { it.type() == Proxy.Type.HTTP }
        if (httpProxies.isNotEmpty()) {
            return httpProxies
        }

        val scheme = target.scheme?.lowercase(Locale.ROOT) ?: "https"
        val envProxy = parseEnvProxy(if (scheme == "https") envProvider("HTTPS_PROXY") ?: envProvider("https_proxy") else envProvider("HTTP_PROXY") ?: envProvider("http_proxy"))
        if (envProxy != null && !matchesNoProxyEnv(host)) {
            return listOf(envProxy)
        }

        if (osNameProvider().lowercase(Locale.ROOT).contains("mac")) {
            val scutil = getCachedScutilConfig()
            if (scutil != null && !scutil.isBypassed(host)) {
                val proxy = if (scheme == "https") scutil.httpsProxy else scutil.httpProxy
                if (proxy != null) {
                    return listOf(proxy)
                }
            }
        }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
        runCatching { getDefault()?.connectFailed(uri, sa, ioe) }
    }

    private fun matchesNoProxyEnv(host: String): Boolean {
        val noProxy = (envProvider("NO_PROXY") ?: envProvider("no_proxy")).orEmpty()
        if (noProxy.isBlank()) return false
        return noProxy.split(',').any { entry ->
            val trimmed = entry.trim().lowercase(Locale.ROOT).trimStart('.')
            trimmed.isNotEmpty() && (host == trimmed || host.endsWith(".$trimmed"))
        }
    }

    private fun parseEnvProxy(raw: String?): Proxy? {
        if (raw.isNullOrBlank()) return null
        val normalized = if ("://" in raw.trim()) raw.trim() else "http://${raw.trim()}"
        val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it in 1..65535 } ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
    }

    private fun getCachedScutilConfig(): ScutilProxyConfig? {
        val now = System.currentTimeMillis()
        val cached = cachedScutilConfig
        if (cached != null && now - cached.first < SCUTIL_CACHE_TTL_MS) {
            return cached.second
        }
        val output = runCatching { scutilRunner() }.getOrNull() ?: return null
        val parsed = parseScutilProxyOutput(output)
        cachedScutilConfig = now to parsed
        return parsed
    }

    private fun isLoopbackHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
        val looksLikeIp = host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || ':' in host
        if (!looksLikeIp) return false
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    internal data class ScutilProxyConfig(
        val httpProxy: Proxy?,
        val httpsProxy: Proxy?,
        val exceptions: List<String>
    ) {
        fun isBypassed(host: String): Boolean {
            return exceptions.any { pattern ->
                val p = pattern.trim().lowercase(Locale.ROOT)
                when {
                    p.isEmpty() -> false
                    p.startsWith("*") && p.endsWith("*") -> host.contains(p.trim('*'))
                    p.startsWith("*") -> host.endsWith(p.removePrefix("*"))
                    p.endsWith("*") -> host.startsWith(p.removeSuffix("*"))
                    else -> host == p || host.endsWith(".$p")
                }
            }
        }
    }

    companion object {
        private const val SCUTIL_CACHE_TTL_MS = 10_000L

        internal fun parseScutilProxyOutput(output: String): ScutilProxyConfig {
            var httpEnabled = false
            var httpHost: String? = null
            var httpPort: Int? = null
            var httpsEnabled = false
            var httpsHost: String? = null
            var httpsPort: Int? = null
            val exceptions = mutableListOf<String>()
            var inExceptions = false

            for (rawLine in output.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("ExceptionsList")) {
                    inExceptions = true
                    continue
                }
                if (inExceptions) {
                    if (line == "}") {
                        inExceptions = false
                    } else if (':' in line) {
                        val value = line.substringAfter(':').trim()
                        if (value.isNotEmpty()) exceptions.add(value)
                    }
                    continue
                }
                if (':' !in line) continue
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':').trim()
                when (key) {
                    "HTTPEnable" -> httpEnabled = value == "1"
                    "HTTPProxy" -> httpHost = value
                    "HTTPPort" -> httpPort = value.toIntOrNull()
                    "HTTPSEnable" -> httpsEnabled = value == "1"
                    "HTTPSProxy" -> httpsHost = value
                    "HTTPSPort" -> httpsPort = value.toIntOrNull()
                }
            }

            val resolvedHttp = if (httpEnabled && !httpHost.isNullOrBlank() && httpPort in 1..65535) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(httpHost, httpPort!!))
            } else null
            val resolvedHttps = if (httpsEnabled && !httpsHost.isNullOrBlank() && httpsPort in 1..65535) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(httpsHost, httpsPort!!))
            } else resolvedHttp
            return ScutilProxyConfig(resolvedHttp, resolvedHttps, exceptions)
        }

        private fun readMacScutilProxy(): String? {
            val scutil = java.io.File("/usr/sbin/scutil").takeIf { it.canExecute() }?.absolutePath ?: return null
            val process = ProcessBuilder(scutil, "--proxy").start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            return if (process.exitValue() == 0) stdout else null
        }
    }
}

/**
 * Ktor-backed transport used primarily for deterministic `MockEngine` unit tests.
 */
internal class KtorEngineLocalHttpTransport(
    engine: HttpClientEngine
) : LocalHttpTransport {

    private val metadataClient = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            requestTimeoutMillis = 15_000L
            socketTimeoutMillis = 15_000L
        }
    }

    private val downloadClient = HttpClient(engine) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 60_000L
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
        }
    }

    override suspend fun headNoRedirects(url: String): LocalHttpHeadResult? {
        return try {
            val response = metadataClient.head(url)
            LocalHttpHeadResult(response.status.value, response.headers.toMap())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun <T> getStream(
        url: String,
        headers: Map<String, String>,
        block: suspend (LocalHttpStreamResult) -> T
    ): T {
        var result: Any? = UNSET
        downloadClient.prepareGet(url) {
            headers.forEach { (key, value) ->
                if (!key.equals(HttpHeaders.UserAgent, ignoreCase = true)) {
                    header(key, value)
                }
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val source = object : LocalHttpByteSource {
                override suspend fun readChunk(buffer: ByteArray): Int {
                    currentCoroutineContext().ensureActive()
                    if (channel.isClosedForRead) return -1
                    return channel.readAvailable(buffer, 0, buffer.size)
                }
            }
            result = block(LocalHttpStreamResult(response.status.value, response.headers.toMap(), source))
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun close() {
        metadataClient.close()
        downloadClient.close()
    }

    private object UNSET
}
