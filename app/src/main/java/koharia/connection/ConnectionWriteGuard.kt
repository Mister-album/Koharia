package koharia.connection

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class ConnectionWritesPaused :
    IOException("Remote writes are paused for this connection"),
    ConnectionAddressRouter.NonRoutingFailure

fun Throwable.hasPausedConnectionWrites(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is ConnectionWritesPaused }

/** Reused by API clients and their derived transfer clients, including retained old source instances. */
class ConnectionWriteGuard(
    private val validationReadOnly: Boolean = DevReadOnlyValidation.enabled,
    private val paused: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (blocks(request)) {
            throw ConnectionWritesPaused()
        }
        val response = chain.proceed(request)
        if (blocks(request)) {
            response.close()
            throw ConnectionWritesPaused()
        }
        return response
    }

    private fun blocks(request: Request): Boolean = !request.isConnectionReadOnly() &&
        (
            paused() || (
                validationReadOnly && request.tag(
                    DevReadOnlyValidation.ReaderContentRequest::class.java,
                ) == null
                )
            )
}

internal fun Request.isConnectionReadOnly() = method in setOf("GET", "HEAD", "OPTIONS") ||
    tag(ConnectionAddressRouter.ReadOnlyRequest::class.java) != null

fun OkHttpClient.withConnectionWriteGuard(connectionId: Long, retryReads: Boolean = true): OkHttpClient =
    withConnectionWriteGuard({ Injekt.get<ConnectionIdentityChanges>().isPaused(connectionId) }, retryReads)

internal fun OkHttpClient.withConnectionWriteGuard(paused: () -> Boolean): OkHttpClient =
    withConnectionWriteGuard(paused, retryReads = true)

internal fun OkHttpClient.withConnectionWriteGuard(
    paused: () -> Boolean,
    retryReads: Boolean,
): OkHttpClient = newBuilder()
    // A failed transport cannot tell whether a remote mutation already committed.
    .retryOnConnectionFailure(false)
    .apply {
        if (retryReads && interceptors().none { it is ConnectionReadRetryInterceptor }) {
            addInterceptor(ConnectionReadRetryInterceptor())
        }
    }
    .addNetworkInterceptor(ConnectionWriteGuard(paused = paused))
    .build()

fun Request.Builder.connectionReadOnly(): Request.Builder =
    tag(ConnectionAddressRouter.ReadOnlyRequest::class.java, ConnectionAddressRouter.ReadOnlyRequest)
