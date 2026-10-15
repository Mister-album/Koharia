package koharia.source.komga

import eu.kanade.tachiyomi.network.awaitSuccess
import koharia.connection.ConnectionAddressRouter
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A conservative configuration binding, not proof of a server's permanent account identity. */
internal class KomgaProgressAccount(
    sourceId: Long,
    address: String,
    internalAddress: String,
    mode: String,
    username: String,
    password: String,
    apiKey: String,
    userAgent: String? = null,
) {
    val key: String = MessageDigest.getInstance("SHA-256").run {
        listOf(sourceId.toString(), address, internalAddress, mode, username, password, apiKey).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            update(bytes)
        }
        digest().joinToString("") { "%02x".format(it) }
    }
    private val roots = listOf(address, internalAddress).mapNotNull { it.toHttpUrlOrNull() }
    val headers: Headers = Headers.Builder().apply {
        if (userAgent != null) set("User-Agent", userAgent)
        if (mode == "ApiKey" && apiKey.isNotBlank()) {
            set("X-API-Key", apiKey)
            set("X-Komga-Api-Key", apiKey)
        } else if (mode == "Credentials" && username.isNotBlank() && password.isNotBlank()) {
            set("Authorization", Credentials.basic(username, password))
        }
    }.build()

    fun requireCurrent(currentKey: String) {
        if (currentKey != key) throw KomgaProgressOwnerChanged()
    }

    fun requireDestination(request: Request) {
        if (roots.none { root ->
                request.url.scheme == root.scheme && request.url.host == root.host && request.url.port == root.port &&
                    request.url.encodedPath.startsWith(root.encodedPath.trimEnd('/') + "/")
            }
        ) {
            throw KomgaProgressOwnerChanged()
        }
    }
}

internal class KomgaProgressOwnerChanged :
    IOException("Komga progress owner changed"),
    ConnectionAddressRouter.NonRoutingFailure

/** The shared coroutine bridge wraps interceptor failures in IOException. Preserve owner rejection semantics. */
internal suspend fun Call.awaitKomgaProgressSuccess(): Response = try {
    awaitSuccess()
} catch (error: IOException) {
    throw generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<KomgaProgressOwnerChanged>().firstOrNull() ?: error
}

/** A request may also be bound to a particular Source instance, even if its credentials are unchanged. */
internal class KomgaProgressRequestGuard(private val validate: () -> Unit) {
    fun requireCurrent() = validate()
}

/** Network interceptors run again for redirects and HTTP followups, even with connection retries disabled. */
internal class KomgaProgressWriteAttempt {
    private val sent = AtomicBoolean()

    fun beforeExchange(request: Request) {
        if (request.method !in setOf("GET", "HEAD") && !sent.compareAndSet(false, true)) {
            throw KomgaProgressReplayRejected()
        }
    }
}

internal class KomgaProgressReplayRejected :
    IOException("Komga progress request requires explicit reconciliation before another write"),
    ConnectionAddressRouter.NonRoutingFailure

/** Check after authentication/routing and on every network exchange, including redirects. */
internal class KomgaProgressAccountInterceptor(private val currentKey: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        fun validate() {
            request.tag(KomgaProgressAccount::class.java)?.let {
                it.requireCurrent(currentKey())
                it.requireDestination(request)
            }
            request.tag(KomgaProgressRequestGuard::class.java)?.requireCurrent()
        }
        validate()
        request.tag(KomgaProgressWriteAttempt::class.java)?.beforeExchange(request)
        val response = chain.proceed(request)
        try {
            validate()
            return response
        } catch (error: Exception) {
            response.close()
            throw error
        }
    }
}
