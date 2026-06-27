package koharia.source.komga

import okhttp3.Interceptor
import okhttp3.Response

class KomgaCacheControlInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.method != "GET" || !response.isSuccessful) {
            return response
        }

        val url = request.url.toString()
        if (url.endsWith("/file")) {
            return response
        }

        val maxAgeSeconds = when {
            url.contains("/api/v1/client-settings/") -> 60 * 60
            url.contains("/api/v1/series") -> 60 * 60
            url.contains("/api/v1/books") && !url.contains("/pages/") -> 60 * 60
            url.contains("/api/v1/readlists") -> 60 * 60
            url.contains("/api/v1/libraries") -> 60 * 60
            url.contains("/api/v1/collections") -> 60 * 60
            url.contains("/api/v1/genres") -> 60 * 60
            url.contains("/api/v1/tags") -> 60 * 60
            url.contains("/api/v1/publishers") -> 60 * 60
            url.contains("/api/v1/authors") -> 60 * 60
            else -> return response
        }

        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$maxAgeSeconds")
            .build()
    }
}
