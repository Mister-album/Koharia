package koharia.epub.service

import koharia.epub.cache.EpubCacheManager
import koharia.epub.injectEpubParagraphIndentStyle
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.mediatype.MediaType
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class KomgaReadiumHttpClient(
    private val sourceManager: SourceManager = Injekt.get(),
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get(),
    private val epubCacheManager: EpubCacheManager = Injekt.get(),
) {

    private val requestLogCount = AtomicInteger(0)
    private val clients = ConcurrentHashMap<Long, DefaultHttpClient>()

    suspend fun cachedResource(
        sourceId: Long,
        publicationKey: String,
        url: String,
    ): ByteArray? = epubCacheManager.getResource(sourceId, publicationKey, url)?.bytes

    fun create(
        sourceId: Long,
        publisherStylesOverride: Boolean? = null,
        publicationKey: String = "source:$sourceId",
        persistCache: Boolean = true,
    ): HttpClient {
        val cachedOnlyPreference = scopedPreferenceStoreFactory.basePreferences(sourceId).downloadedOnly
        // Keep one Readium transport per server so opening the pagination scanner or an adjacent
        // EPUB reuses the same connection pool and TLS session. Cache policy remains per reader
        // in the wrapper below.
        val client = clients.getOrPut(sourceId) { createClient(sourceId) }
        val cachedClient = EpubResourceCacheHttpClient(
            delegate = client,
            cacheManager = epubCacheManager,
            sourceId = sourceId,
            publicationKey = publicationKey,
            persistCache = persistCache,
            cachedOnlyProvider = cachedOnlyPreference::get,
        )
        return ParagraphIndentNormalizingHttpClient(cachedClient) {
            !(
                publisherStylesOverride
                    ?: scopedPreferenceStoreFactory.epubLayoutPreferences(sourceId).publisherStyles.get()
                )
        }
    }

    private fun createClient(sourceId: Long): DefaultHttpClient {
        return DefaultHttpClient().apply {
            callback = object : DefaultHttpClient.Callback {
                override suspend fun onStartRequest(request: HttpRequest) = Try.success(
                    request.copy {
                        val source = sourceManager.get(sourceId) as? KomgaSource
                        val baseUrl = source?.baseUrl?.trimEnd('/').orEmpty()
                        val shouldInjectHeaders = baseUrl.isNotEmpty() &&
                            request.url.toString().startsWith(baseUrl)
                        source?.currentReadiumHeaders()
                            .takeIf { shouldInjectHeaders }
                            ?.let { headers ->
                                headers.names().forEach { name ->
                                    setHeader(name, headers.values(name))
                                }
                            }

                        if (requestLogCount.getAndIncrement() < 20) {
                            logcat(LogPriority.DEBUG) {
                                "EPUB http request url=${request.url} injectHeaders=$shouldInjectHeaders baseUrl=$baseUrl"
                            }
                        }
                    },
                )
            }
        }
    }
}

internal fun shouldUseKomgaReadiumNetwork(cachedOnly: Boolean): Boolean = !cachedOnly

private class EpubResourceCacheHttpClient(
    private val delegate: HttpClient,
    private val cacheManager: EpubCacheManager,
    private val sourceId: Long,
    private val publicationKey: String,
    private val persistCache: Boolean,
    private val cachedOnlyProvider: () -> Boolean,
) : HttpClient {

    // Disk writes are intentionally bounded. Readium may request several resources at once, and
    // launching an unbounded IO job per response used to contend with the visible WebView.
    private val prefetchScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(PREFETCH_CONCURRENCY),
    )

    override suspend fun stream(request: HttpRequest): org.readium.r2.shared.util.http.HttpTry<HttpStreamResponse> {
        if (isCacheable(request)) {
            cacheManager.getResource(sourceId, publicationKey, request.url.toString())?.let { cached ->
                return Try.success(
                    HttpStreamResponse(
                        response = HttpResponse(
                            request = request,
                            url = request.url,
                            statusCode = HttpStatus(200),
                            headers = emptyMap(),
                            mediaType = cached.mediaType?.let { value -> MediaType(value) },
                        ),
                        body = ByteArrayInputStream(cached.bytes),
                    ),
                )
            }
        }

        if (!shouldUseKomgaReadiumNetwork(cachedOnlyProvider())) {
            return Try.failure(HttpError.IO(IOException("Komga network access disabled in cached-only mode")))
        }

        if (!isCacheable(request)) return delegate.stream(request)

        return delegate.stream(request).map { response ->
            if (response.response.statusCode != HttpStatus(200) ||
                response.response.contentLength?.let { it > EpubCacheManager.MAX_RESOURCE_BYTES } == true
            ) {
                return@map response
            }
            val bytes = response.body.use { it.readBytes() }
            if (persistCache) {
                prefetchScope.launch {
                    prefetchSafely(request.url.toString()) {
                        cacheManager.putResource(
                            sourceId = sourceId,
                            publicationKey = publicationKey,
                            url = request.url.toString(),
                            mediaType = response.response.mediaType?.toString(),
                            bytes = bytes,
                        )
                    }
                }
            }
            HttpStreamResponse(response.response, ByteArrayInputStream(bytes))
        }
    }

    private fun isCacheable(request: HttpRequest): Boolean {
        if (request.method != HttpRequest.Method.GET) return false
        if (request.headers.keys.any { it.equals("Range", ignoreCase = true) }) return false
        val url = request.url.toString().substringBefore('?')
        return url.contains("/manifest/epub") ||
            url.endsWith("/positions") ||
            url.contains("/resource/")
    }

    private suspend fun prefetchSafely(
        url: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) {
                "Failed to prefetch EPUB resource url=${url.substringBefore('?')}"
            }
        }
    }

    private companion object {
        const val PREFETCH_CONCURRENCY = 2
    }
}

private class ParagraphIndentNormalizingHttpClient(
    private val delegate: HttpClient,
    private val shouldNormalize: () -> Boolean,
) : HttpClient {

    override suspend fun stream(request: HttpRequest): org.readium.r2.shared.util.http.HttpTry<HttpStreamResponse> {
        return delegate.stream(request).map { streamResponse ->
            val path = request.url.toString().substringBefore('?')
            val isHtml = htmlExtensions.any { path.endsWith(it, ignoreCase = true) }
            val isFullGet = request.method == HttpRequest.Method.GET &&
                request.headers.keys.none { it.equals("Range", ignoreCase = true) }
            if (!isHtml || !isFullGet || !shouldNormalize()) {
                return@map streamResponse
            }

            val source = streamResponse.body.use { it.readBytes() }.toString(Charsets.UTF_8)
            val matches = leadingParagraphIndent.findAll(source).count()
            val paragraphCount = paragraphTag.findAll(source).count()
            val declaredTextIndentCount = declaredTextIndent.findAll(source).count()
            val normalized = leadingParagraphIndent
                .replace(source) { match -> match.groupValues[1] }
                .injectEpubParagraphIndentStyle()
            val normalizedBytes = normalized.toByteArray(Charsets.UTF_8)
            val normalizedResponse = streamResponse.response.copy(
                headers = streamResponse.response.headers
                    .filterKeys { !it.equals("Content-Length", ignoreCase = true) },
            )

            logcat(LogPriority.DEBUG) {
                "EPUB normalized paragraph indents url=${request.url} removedSpaces=$matches " +
                    "paragraphs=$paragraphCount declaredTextIndents=$declaredTextIndentCount injectedIndentStyle=true"
            }
            HttpStreamResponse(
                response = normalizedResponse,
                body = ByteArrayInputStream(normalizedBytes),
            )
        }
    }

    private companion object {
        val leadingParagraphIndent = Regex("""(<p\b[^>]*>)\u3000+""", RegexOption.IGNORE_CASE)
        val htmlExtensions = listOf(".xhtml", ".html", ".htm")
        val paragraphTag = Regex("""<p\b""", RegexOption.IGNORE_CASE)
        val declaredTextIndent = Regex("""text-indent\s*:""", RegexOption.IGNORE_CASE)
    }
}
