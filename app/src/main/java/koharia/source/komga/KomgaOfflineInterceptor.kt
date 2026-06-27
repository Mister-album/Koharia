package koharia.source.komga

import android.content.Context
import eu.kanade.tachiyomi.util.system.isOnline
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class KomgaOfflineInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val isOnline = context.isOnline()

        // 如果没有网络，强制从缓存中读取，允许读取最多 7 天的旧缓存
        if (!isOnline) {
            logcat(LogPriority.INFO) { "无网络连接，强制使用本地缓存: ${request.url}" }
            val cacheControl = CacheControl.Builder()
                .onlyIfCached()
                .maxStale(7, TimeUnit.DAYS)
                .build()
            request = request.newBuilder()
                .cacheControl(cacheControl)
                .build()
        } else {
            // 如果有网络，但也尝试使用 ETag/Last-Modified 进行缓存验证
            // OkHttp 会自动处理，这里可以针对 Komga API 进行策略优化
        }

        var response: Response? = null
        var exception: IOException? = null
        var retryCount = 0
        val maxRetries = 3

        // 失败重试机制（仅在有网络时进行重试）
        while (retryCount < maxRetries) {
            try {
                response = chain.proceed(request)
                if (!isOnline && response.code == 504) {
                    val fallbackResponse = loadDiskCachedResponse(request)
                    if (fallbackResponse != null) {
                        response.close()
                        response = fallbackResponse
                    }
                }
                if (!isOnline && response.code == 504) {
                    response.close()
                    response = null
                    throw IOException(context.stringResource(MR.strings.exception_offline))
                }
                if (response.isSuccessful || !isOnline) {
                    break
                } else if (response.code >= 500) {
                    // 服务端错误，可以重试
                    response.close()
                    retryCount++
                    continue
                } else {
                    break
                }
            } catch (e: IOException) {
                exception = e
                if (!isOnline) {
                    break // 无网络不重试
                }
                retryCount++
                if (retryCount >= maxRetries) {
                    // 网络请求全部失败，尝试从本地缓存降级读取
                    logcat(LogPriority.INFO) { "网络请求失败，尝试降级读取本地缓存: ${request.url}" }
                    val fallbackCacheControl = CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(7, TimeUnit.DAYS)
                        .build()
                    val fallbackRequest = request.newBuilder()
                        .cacheControl(fallbackCacheControl)
                        .build()
                    try {
                        response = chain.proceed(fallbackRequest)
                    } catch (fallbackEx: IOException) {
                        // 忽略降级失败，保留原始异常
                    }
                    break
                }
                try {
                    Thread.sleep(1000L * retryCount) // 简单退避
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (response == null) {
            if (!isOnline) {
                throw IOException(context.stringResource(MR.strings.exception_offline))
            }
            throw exception ?: IOException("请求失败，已重试 $maxRetries 次。")
        }

        // 针对 API 请求写入缓存控制头，让 OkHttp 可以缓存元数据
        if (isOnline && response.isSuccessful) {
            val urlStr = request.url.toString()
            // 针对元数据接口缓存
            if (urlStr.contains("/api/v1/series") || urlStr.contains("/api/v1/books")) {
                response = response.newBuilder()
                    .header("Cache-Control", "public, max-age=${60 * 60}") // 缓存 1 小时
                    .build()
            }
        }

        return response
    }

    private fun loadDiskCachedResponse(request: okhttp3.Request): Response? {
        val url = request.url.toString()
        if (!url.contains("/api/v1/") || url.endsWith("/file") || PAGE_IMAGE_REGEX.containsMatchIn(url)) {
            return null
        }

        val cacheDir = File(context.cacheDir, "network_cache")
        val cacheKey = md5(url)
        val metadataFile = File(cacheDir, "$cacheKey.0")
        val bodyFile = File(cacheDir, "$cacheKey.1")
        if (!metadataFile.exists() || !bodyFile.exists()) {
            return null
        }

        val metadataLines = metadataFile.readLines()
        if (metadataLines.firstOrNull() != url) {
            return null
        }

        val statusCode = metadataLines.firstOrNull { it.startsWith("HTTP/") }
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 200
        if (statusCode !in 200..299) {
            return null
        }

        val contentType = metadataLines.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toMediaTypeOrNull()
            ?: "application/json".toMediaTypeOrNull()

        val headers = Headers.Builder()
            .add("Content-Type", contentType.toString())
            .add("X-Koharia-Offline-Cache", "disk")
            .build()

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .headers(headers)
            .body(bodyFile.readBytes().toResponseBody(contentType))
            .build()
    }

    private fun md5(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val PAGE_IMAGE_REGEX = Regex("/pages/\\d+(?:\\?.*)?$")
    }
}
