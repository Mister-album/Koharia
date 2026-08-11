package koharia.source.komga

import android.content.Context
import eu.kanade.tachiyomi.util.system.isOnline
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.TimeUnit

class KomgaOfflineInterceptor(
    private val context: Context,
    private val cachedOnlyProvider: () -> Boolean,
) : Interceptor {
    private val metadataCacheStore = KomgaMetadataCacheStore(context.applicationContext)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val cachedOnly = cachedOnlyProvider()
        val canUseNetwork = shouldUseKomgaNetwork(cachedOnly, context.isOnline())
        val request = if (canUseNetwork) {
            originalRequest
        } else {
            logcat(LogPriority.INFO) {
                val reason = if (cachedOnly) "cached-only mode" else "no network"
                "Forcing Komga cache read ($reason): ${originalRequest.url}"
            }
            originalRequest.newBuilder()
                .cacheControl(
                    CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
                        .build(),
                )
                .build()
        }

        var response: Response? = null
        var exception: IOException? = null
        var retryCount = 0
        val maxRetries = 3

        while (retryCount < maxRetries) {
            try {
                response = chain.proceed(request)
                if (!canUseNetwork && response.code == 504) {
                    val fallbackResponse = metadataCacheStore.load(originalRequest)
                    if (fallbackResponse != null) {
                        response.close()
                        response = fallbackResponse
                    }
                }
                if (!canUseNetwork && response.code == 504) {
                    response.close()
                    response = null
                    throw IOException(context.stringResource(MR.strings.exception_offline))
                }
                if (response.isSuccessful || !canUseNetwork) {
                    break
                }
                if (response.code >= 500) {
                    response.close()
                    retryCount++
                    response = if (retryCount >= maxRetries) {
                        metadataCacheStore.load(originalRequest)
                    } else {
                        null
                    }
                    if (response != null) break
                    continue
                }
                break
            } catch (e: IOException) {
                exception = e
                if (!canUseNetwork) {
                    break
                }
                retryCount++
                if (retryCount >= maxRetries) {
                    logcat(LogPriority.INFO) { "Komga network request failed, trying local cache: ${request.url}" }
                    response = metadataCacheStore.load(originalRequest)
                    if (response != null) break

                    val fallbackRequest = request.newBuilder()
                        .cacheControl(
                            CacheControl.Builder()
                                .onlyIfCached()
                                .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
                                .build(),
                        )
                        .build()
                    try {
                        response = chain.proceed(fallbackRequest)
                    } catch (_: IOException) {
                        // Keep the original exception below.
                    }
                    break
                }
                try {
                    Thread.sleep(1000L * retryCount)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (response == null) {
            if (!canUseNetwork) {
                throw IOException(context.stringResource(MR.strings.exception_offline))
            }
            throw exception ?: IOException("Request failed after $maxRetries retries")
        }

        return response
    }
}

internal fun shouldUseKomgaNetwork(cachedOnly: Boolean, isOnline: Boolean): Boolean = !cachedOnly && isOnline
