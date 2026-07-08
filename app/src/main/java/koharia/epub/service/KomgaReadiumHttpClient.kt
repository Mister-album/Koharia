package koharia.epub.service

import koharia.source.komga.KomgaSource
import logcat.LogPriority
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpRequest
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

class KomgaReadiumHttpClient(
    private val sourceManager: SourceManager = Injekt.get(),
) {

    private val requestLogCount = AtomicInteger(0)

    fun create(sourceId: Long): DefaultHttpClient {
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
        return client
    }
}
