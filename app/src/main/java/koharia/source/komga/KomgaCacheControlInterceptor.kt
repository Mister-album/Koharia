package koharia.source.komga

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response

class KomgaCacheControlInterceptor(
    context: Context,
) : Interceptor {
    private val metadataCacheStore = KomgaMetadataCacheStore(context.applicationContext)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.method != "GET" || !response.isSuccessful) {
            return response
        }

        if (!metadataCacheStore.isEligible(request)) {
            return response
        }

        val cacheableResponse = response.newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .build()

        return metadataCacheStore.save(request, cacheableResponse)
    }

    private companion object {
        const val MAX_AGE_SECONDS = 60 * 60
    }
}
