package koharia.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import eu.kanade.tachiyomi.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Changes transport endpoints without changing persisted book URLs or cache/account identities. */
class ConnectionAddressRouter(
    private val publicAddress: () -> String,
    private val internalAddress: () -> String,
    private val wifiNetwork: () -> Any?,
    private val probePath: String,
    private val clock: () -> Long = System::nanoTime,
    private val authenticateProbe: Boolean = true,
    private val diagnosticConnectionId: Long? = null,
    private val networkDescription: () -> String = { "injected" },
    private val diagnostics: ((String) -> Unit)? = if (BuildConfig.DEBUG) NetworkDiagnostics::record else null,
) : Interceptor {
    private val routerId = nextRouterId.incrementAndGet()

    /** Opt in only for replayable queries transported with POST, never for mutations. */
    object ReadOnlyRequest

    /** Authentication and request validation errors do not indicate an unavailable address. */
    interface NonRoutingFailure
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
        if (original.cacheControl.onlyIfCached) return proceedOriginal(chain, "cache-only")
        val public = normalize(publicAddress()) ?: return proceedOriginal(chain, "invalid-primary")
        val internal = normalize(internalAddress()) ?: return proceedOriginal(chain, "no-valid-internal")
        val network = wifiNetwork()
        diagnostic(chain) { "route-start ${networkDescription()}" }
        if (network == null) {
            synchronized(this) { lastRoute = null }
            return proceedOriginal(chain, "default-network-not-wifi")
        }
        if (public == internal) return proceedOriginal(chain, "same-address")
        if (!owns(public, original.url)) return proceedOriginal(chain, "outside-primary")
        val route = Route(network, public, internal)
        val waitStarted = System.nanoTime()
        val useInternal = synchronized(this) {
            diagnostic(chain) { "route-lock-acquired waitMs=${elapsedMillis(waitStarted)}" }
            if (route != lastRoute || clock() - checkedAt > TimeUnit.SECONDS.toNanos(30)) {
                diagnostic(chain) {
                    "probe-required reason=${if (route != lastRoute) "route-changed" else "expired"}"
                }
                internalAvailable = probe(chain, original, internal)
                lastRoute = route
                checkedAt = clock()
            } else {
                diagnostic(chain) {
                    "probe-cached available=$internalAvailable ageMs=${(clock() - checkedAt) / 1_000_000}"
                }
            }
            internalAvailable
        }
        if (!useInternal) return proceedOriginal(chain, "internal-unavailable")
        if (wifiNetwork() != network) return proceedOriginal(chain, "network-changed-after-probe")
        val routed = original.newBuilder()
            .url(remap(original.url, public, internal))
            .tag(InternalRoute::class.java, InternalRoute(internal) { diagnostic(chain) { it } })
            .build()
        val started = System.nanoTime()
        diagnostic(chain) { "route-selected target=internal connectTimeoutMs=1500" }
        val response = try {
            chain.withConnectTimeout(1500, TimeUnit.MILLISECONDS).proceed(routed)
        } catch (error: IOException) {
            diagnostic(chain) {
                "internal-failed elapsedMs=${elapsedMillis(started)} error=${NetworkDiagnostics.errorType(error)} " +
                    "canceled=${chain.call().isCanceled()} replayable=${original.isReplayable()} " +
                    "nonRouting=${error.hasNonRoutingFailure()}"
            }
            if (error.hasNonRoutingFailure()) throw error
            markUnavailable(route)
            if (chain.call().isCanceled() || !original.isReplayable()) throw error
            return proceedOriginal(chain, "internal-io-fallback")
        }
        diagnostic(chain) { "internal-headers status=${response.code} elapsedMs=${elapsedMillis(started)}" }
        if (response.code in FALLBACK_CODES && original.isReplayable()) {
            response.close()
            markUnavailable(route)
            return proceedOriginal(chain, "internal-http-${response.code}-fallback")
        }
        return response.newBuilder().request(original).build()
    }

    private fun proceedOriginal(chain: Interceptor.Chain, reason: String): Response {
        val started = System.nanoTime()
        diagnostic(chain) { "route-selected target=original reason=$reason" }
        return try {
            chain.proceed(chain.request()).also { response ->
                diagnostic(chain) { "original-headers status=${response.code} elapsedMs=${elapsedMillis(started)}" }
            }
        } catch (error: IOException) {
            diagnostic(chain) {
                "original-failed elapsedMs=${elapsedMillis(started)} error=${NetworkDiagnostics.errorType(error)} " +
                    "canceled=${chain.call().isCanceled()}"
            }
            throw error
        }
    }

    private inline fun diagnostic(chain: Interceptor.Chain, message: () -> String) {
        val sink = diagnostics ?: return
        runCatching {
            sink(
                "router=$routerId connectionId=${diagnosticConnectionId ?: "unspecified"} " +
                    "call=${NetworkDiagnostics.callId(chain.call())} ${message()}",
            )
        }
    }

    private fun Request.isReplayable() = method in READ_METHODS || tag(ReadOnlyRequest::class.java) != null

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
            .apply {
                if (!authenticateProbe) {
                    removeHeader("Authorization")
                    removeHeader("Proxy-Authorization")
                    removeHeader("token")
                    removeHeader("Cookie")
                }
            }
            .tag(InternalRoute::class.java, InternalRoute(internal) { diagnostic(chain) { it } })
            .build()
        val started = System.nanoTime()
        diagnostic(chain) { "probe-start connectTimeoutMs=1500 readTimeoutMs=1500 authenticated=$authenticateProbe" }
        return try {
            chain.withConnectTimeout(1500, TimeUnit.MILLISECONDS)
                .withReadTimeout(1500, TimeUnit.MILLISECONDS)
                .proceed(request).use {
                    val available = it.isSuccessful || it.code == 401 || it.code == 403
                    diagnostic(chain) {
                        "probe-end status=${it.code} available=$available elapsedMs=${elapsedMillis(started)}"
                    }
                    available
                }
        } catch (error: IOException) {
            diagnostic(chain) {
                "probe-failed elapsedMs=${elapsedMillis(started)} error=${NetworkDiagnostics.errorType(error)} " +
                    "canceled=${chain.call().isCanceled()} nonRouting=${error.hasNonRoutingFailure()}"
            }
            if (chain.call().isCanceled() || error.hasNonRoutingFailure()) throw error
            false
        }
    }

    private fun markUnavailable(route: Route) = synchronized(this) {
        if (lastRoute == route) {
            internalAvailable = false
            checkedAt = clock()
        }
    }

    private fun Throwable.hasNonRoutingFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it is NonRoutingFailure }

    private data class InternalRoute(val base: HttpUrl, val diagnostic: (String) -> Unit)

    companion object {
        const val INTERNAL_ADDRESS_KEY = "internal_address"
        private val READ_METHODS = setOf("GET", "HEAD")
        private val FALLBACK_CODES = setOf(408, 502, 503, 504)
        private val nextRouterId = AtomicLong()

        private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

        // Prevent redirects from sending connection credentials outside the configured LAN service.
        val redirectGuard = Interceptor { chain ->
            val request = chain.request()
            val route = request.tag(InternalRoute::class.java)
            if (route != null && !owns(route.base, request.url)) {
                route.diagnostic(
                    "redirect-rejected schemeChanged=${route.base.scheme != request.url.scheme} " +
                        "hostChanged=${route.base.host != request.url.host} " +
                        "portChanged=${route.base.port != request.url.port}",
                )
                throw IOException("Internal address redirect rejected")
            }
            chain.proceed(request)
        }

        fun forAndroid(
            context: Context,
            publicAddress: () -> String,
            internalAddress: () -> String,
            probePath: String,
            authenticateProbe: Boolean = true,
            diagnosticConnectionId: Long? = null,
        ): ConnectionAddressRouter {
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            val description = AtomicReference("unobserved")
            return ConnectionAddressRouter(
                publicAddress,
                internalAddress,
                {
                    val network = connectivity.activeNetwork
                    val capabilities = network?.let(connectivity::getNetworkCapabilities)
                    if (BuildConfig.DEBUG) {
                        fun hasTransport(transport: Int) = capabilities?.hasTransport(transport) == true
                        val validated = capabilities?.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                        ) == true
                        val wifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        val vpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        val cellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        val ethernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        description.set(
                            "active=${network != null} capabilities=${capabilities != null} " +
                                "wifi=$wifi vpn=$vpn cellular=$cellular ethernet=$ethernet validated=$validated",
                        )
                    }
                    network?.takeIf { capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                },
                probePath,
                authenticateProbe = authenticateProbe,
                diagnosticConnectionId = diagnosticConnectionId,
                networkDescription = description::get,
            )
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
