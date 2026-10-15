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
class ConnectionWriteGuard(private val paused: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isReadOnly() && (DevReadOnlyValidation.enabled || paused())) throw ConnectionWritesPaused()
        val response = chain.proceed(request)
        if (!request.isReadOnly() && (DevReadOnlyValidation.enabled || paused())) {
            response.close()
            throw ConnectionWritesPaused()
        }
        return response
    }

    private fun Request.isReadOnly() = method in setOf("GET", "HEAD", "OPTIONS") ||
        tag(ConnectionAddressRouter.ReadOnlyRequest::class.java) != null
}

fun OkHttpClient.withConnectionWriteGuard(connectionId: Long): OkHttpClient =
    withConnectionWriteGuard { Injekt.get<ConnectionIdentityChanges>().isPaused(connectionId) }

internal fun OkHttpClient.withConnectionWriteGuard(paused: () -> Boolean): OkHttpClient = newBuilder()
    // A failed transport cannot tell whether a remote mutation already committed.
    .retryOnConnectionFailure(false)
    .addNetworkInterceptor(ConnectionWriteGuard(paused))
    .build()

fun Request.Builder.connectionReadOnly(): Request.Builder =
    tag(ConnectionAddressRouter.ReadOnlyRequest::class.java, ConnectionAddressRouter.ReadOnlyRequest)
