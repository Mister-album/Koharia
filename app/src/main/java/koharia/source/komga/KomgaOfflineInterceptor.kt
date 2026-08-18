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

        return try {
            val response = chain.proceed(request)
            if (!canUseNetwork && response.code == 504) {
                metadataCacheStore.load(originalRequest)?.also { response.close() }
                    ?: run {
                        response.close()
                        throw IOException(context.stringResource(MR.strings.exception_offline))
                    }
            } else if (canUseNetwork && response.code >= 500) {
                metadataCacheStore.load(originalRequest)?.also { response.close() } ?: response
            } else {
                response
            }
        } catch (error: IOException) {
            if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) throw error
            if (!canUseNetwork) throw IOException(context.stringResource(MR.strings.exception_offline), error)

            logcat(LogPriority.INFO) { "Komga network request failed, trying local cache: ${request.url}" }
            metadataCacheStore.load(originalRequest) ?: throw error
        }
    }
}

internal fun shouldUseKomgaNetwork(cachedOnly: Boolean, isOnline: Boolean): Boolean = !cachedOnly && isOnline
