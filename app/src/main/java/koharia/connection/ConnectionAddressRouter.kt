package koharia.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Changes transport endpoints without changing persisted book URLs or cache/account identities. */
class ConnectionAddressRouter(
    private val publicAddress: () -> String,
    private val internalAddress: () -> String,
    private val wifiNetwork: () -> Any?,
    private val probePath: String,
    private val clock: () -> Long = System::nanoTime,
) : Interceptor {
    private data class Route(val network: Any, val public: HttpUrl, val internal: HttpUrl)
    private var lastRoute: Route? = null
    private var internalAvailable = false
    private var checkedAt = 0L

    fun canonicalUrl(url: HttpUrl): HttpUrl {
        val public = normalize(publicAddress()) ?: return url
        val internal = normalize(internalAddress()) ?: return url
        return if (owns(internal, url)) remap(url, internal, public) else url
    }

    fun ownsInternal(url: HttpUrl): Boolean = normalize(internalAddress())?.let { owns(it, url) } == true

    fun canonicalResourcePath(path: String): String {
        val public = normalize(publicAddress()) ?: return path
        val internal = normalize(internalAddress()) ?: return path
        if (!path.startsWith('/') || internal.encodedPath == "/") return path
        if (public.encodedPath.length >= internal.encodedPath.length && path.startsWith(public.encodedPath)) return path
        val url = internal.resolve(path)?.takeIf { owns(internal, it) } ?: return path
        return canonicalUrl(url).toString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.cacheControl.onlyIfCached) return chain.proceed(original)
        val public = normalize(publicAddress()) ?: return chain.proceed(original)
        val internal = normalize(internalAddress()) ?: return chain.proceed(original)
        val network = wifiNetwork()
        if (network == null) {
            synchronized(this) { lastRoute = null }
            return chain.proceed(original)
        }
        if (public == internal || !owns(public, original.url)) return chain.proceed(original)
        val route = Route(network, public, internal)
        val useInternal = synchronized(this) {
            if (route != lastRoute || clock() - checkedAt > TimeUnit.SECONDS.toNanos(30)) {
                internalAvailable = probe(chain, original, internal)
                lastRoute = route
                checkedAt = clock()
            }
            internalAvailable
        }
        if (!useInternal || wifiNetwork() != network) return chain.proceed(original)
        val routed = original.newBuilder()
            .url(remap(original.url, public, internal))
            .tag(InternalRoute::class.java, InternalRoute(internal))
            .build()
        val response = try {
            chain.withConnectTimeout(1500, TimeUnit.MILLISECONDS).proceed(routed)
        } catch (error: IOException) {
            markUnavailable(route)
            if (chain.call().isCanceled() || original.method !in READ_METHODS) throw error
            return chain.proceed(original)
        }
        if (response.code in FALLBACK_CODES && original.method in READ_METHODS) {
            response.close()
            markUnavailable(route)
            return chain.proceed(original)
        }
        return response.newBuilder().request(original).build()
    }

    private fun probe(chain: Interceptor.Chain, original: Request, internal: HttpUrl): Boolean {
        val request = original.newBuilder()
            .url(checkNotNull(internal.resolve(probePath)))
            .get()
            .removeHeader("Range")
            .removeHeader("If-Range")
            .removeHeader("If-None-Match")
            .removeHeader("If-Modified-Since")
            .removeHeader("Content-Type")
            .header("Cache-Control", "no-store")
            .header("Accept", "application/json")
            .tag(InternalRoute::class.java, InternalRoute(internal))
            .build()
        return try {
            chain.withConnectTimeout(1500, TimeUnit.MILLISECONDS)
                .withReadTimeout(1500, TimeUnit.MILLISECONDS)
                .proceed(request).use { it.isSuccessful }
        } catch (error: IOException) {
            if (chain.call().isCanceled()) throw error
            false
        }
    }

    private fun markUnavailable(route: Route) = synchronized(this) {
        if (lastRoute == route) {
            internalAvailable = false
            checkedAt = clock()
        }
    }

    private data class InternalRoute(val base: HttpUrl)

    companion object {
        const val INTERNAL_ADDRESS_KEY = "internal_address"
        private val READ_METHODS = setOf("GET", "HEAD")
        private val FALLBACK_CODES = setOf(408, 502, 503, 504)

        // Prevent redirects from sending connection credentials outside the configured LAN service.
        val redirectGuard = Interceptor { chain ->
            val request = chain.request()
            val route = request.tag(InternalRoute::class.java)
            if (route != null && !owns(route.base, request.url)) throw IOException("Internal address redirect rejected")
            chain.proceed(request)
        }

        fun forAndroid(
            context: Context,
            publicAddress: () -> String,
            internalAddress: () -> String,
            probePath: String,
        ): ConnectionAddressRouter {
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            return ConnectionAddressRouter(publicAddress, internalAddress, {
                connectivity.activeNetwork?.takeIf { network ->
                    connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ==
                        true
                }
            }, probePath)
        }

        fun normalize(address: String): HttpUrl? = address.trim().toHttpUrlOrNull()
            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }
            ?.let { it.newBuilder().encodedPath(it.encodedPath.trimEnd('/') + "/").build() }

        fun owns(base: HttpUrl, url: HttpUrl): Boolean =
            base.scheme == url.scheme && base.host == url.host && base.port == url.port &&
                (url.encodedPath == base.encodedPath.trimEnd('/') || url.encodedPath.startsWith(base.encodedPath))

        fun remap(url: HttpUrl, from: HttpUrl, to: HttpUrl): HttpUrl = url.newBuilder()
            .scheme(to.scheme).host(to.host).port(to.port)
            .encodedPath(to.encodedPath + url.encodedPath.removePrefix(from.encodedPath.trimEnd('/')).trimStart('/'))
            .build()
    }
}
