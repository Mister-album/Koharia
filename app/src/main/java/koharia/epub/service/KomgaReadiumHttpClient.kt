package koharia.epub.service

import koharia.epub.injectEpubParagraphIndentStyle
import koharia.source.komga.KomgaSource
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import logcat.LogPriority
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class KomgaReadiumHttpClient(
    private val sourceManager: SourceManager = Injekt.get(),
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get(),
) {

    private val requestLogCount = AtomicInteger(0)

    fun create(sourceId: Long, publisherStylesOverride: Boolean? = null): HttpClient {
        val client = DefaultHttpClient()
        client.callback = object : DefaultHttpClient.Callback {
            override suspend fun onStartRequest(request: HttpRequest) = Try.success(
                request.copy {
                    val source = sourceManager.get(sourceId) as? KomgaSource
                    val baseUrl = source?.baseUrl?.trimEnd('/').orEmpty()
                    val shouldInjectHeaders = request.url.toString().startsWith(baseUrl)
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
        return ParagraphIndentNormalizingHttpClient(client) {
            !(publisherStylesOverride
                ?: scopedPreferenceStoreFactory.epubLayoutPreferences(sourceId).publisherStyles.get())
        }
    }
}

private class ParagraphIndentNormalizingHttpClient(
    private val delegate: HttpClient,
    private val shouldNormalize: () -> Boolean,
) : HttpClient {

    override suspend fun stream(request: HttpRequest): org.readium.r2.shared.util.http.HttpTry<HttpStreamResponse> {
        return delegate.stream(request).map { streamResponse ->
            if (!request.url.toString().substringBefore('?').endsWith(".xhtml") || !shouldNormalize()) {
                return@map streamResponse
            }

            val source = streamResponse.body.use { it.readBytes() }.toString(Charsets.UTF_8)
            val matches = leadingParagraphIndent.findAll(source).count()
            val paragraphCount = paragraphTag.findAll(source).count()
            val declaredTextIndentCount = declaredTextIndent.findAll(source).count()
            val normalized = leadingParagraphIndent
                .replace(source) { match -> match.groupValues[1] }
                .injectEpubParagraphIndentStyle()

            logcat(LogPriority.DEBUG) {
                "EPUB normalized paragraph indents url=${request.url} removedSpaces=$matches " +
                    "paragraphs=$paragraphCount declaredTextIndents=$declaredTextIndentCount injectedIndentStyle=true"
            }
            HttpStreamResponse(
                response = streamResponse.response,
                body = ByteArrayInputStream(
                    normalized.toByteArray(Charsets.UTF_8),
                ),
            )
        }
    }

    private companion object {
        val leadingParagraphIndent = Regex("""(<p\b[^>]*>)\u3000+""", RegexOption.IGNORE_CASE)
        val paragraphTag = Regex("""<p\b""", RegexOption.IGNORE_CASE)
        val declaredTextIndent = Regex("""text-indent\s*:""", RegexOption.IGNORE_CASE)
    }
}
