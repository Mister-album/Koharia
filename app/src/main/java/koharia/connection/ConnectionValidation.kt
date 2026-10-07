package koharia.connection

import kotlinx.coroutines.CancellationException
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Provider errors expose only classifications; credentials and protocol payloads stay with the provider. */
interface ConnectionValidationError {
    val validationReason: ConnectionAddressVerification.Reason?
    val validationStatus: Int? get() = null
}

object ConnectionValidation {
    enum class Endpoint { PUBLIC, INTERNAL }

    fun required(isNew: Boolean, connectionChanged: Boolean, needsValidation: Boolean = false): Boolean =
        isNew || connectionChanged || needsValidation

    fun client(networkClient: OkHttpClient, internalAddress: String = ""): OkHttpClient =
        networkClient.newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
            .connectionPool(ConnectionPool())
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .eventListener(EventListener.NONE)
            .dns(Dns.SYSTEM)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .addInterceptor(ReadRetry(ConnectionAddressRouter.normalize(internalAddress)))
            .build()

    suspend fun <T> at(
        endpoint: Endpoint,
        stage: ConnectionAddressVerification.Stage = ConnectionAddressVerification.Stage.AUTHENTICATION,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (error: Exception) {
        throw classify(error, endpoint)?.let { failure ->
            ConnectionAddressVerification.Failure(
                failure.reason,
                failure.marker,
                failure.endpoint,
                failure.status,
                failure,
                failure.stage ?: stage,
            )
        } ?: error
    }

    fun classify(error: Throwable, endpoint: Endpoint? = null): ConnectionAddressVerification.Failure? {
        if (error is CancellationException) return null
        if (error is ConnectionAddressVerification.Failure) {
            if (error.endpoint != null || endpoint == null) return error
            return ConnectionAddressVerification.Failure(
                error.reason,
                error.marker,
                endpoint,
                error.status,
                error,
                error.stage,
            )
        }
        val causes = generateSequence(error) { it.cause }.toList()
        causes.filterIsInstance<ConnectionAddressVerification.Failure>().firstOrNull()?.let {
            return classify(it, endpoint)
        }
        val reason = when {
            causes.any { it is SSLException } -> ConnectionAddressVerification.Reason.CERTIFICATE
            causes.any { it is SocketTimeoutException } -> ConnectionAddressVerification.Reason.TIMEOUT
            error is ConnectionValidationError -> error.validationReason ?: return null
            error is IOException -> ConnectionAddressVerification.Reason.UNAVAILABLE
            error is kotlinx.serialization.SerializationException -> ConnectionAddressVerification.Reason.RESPONSE
            else -> return null
        }
        return ConnectionAddressVerification.Failure(
            reason,
            endpoint = endpoint,
            status = (error as? ConnectionValidationError)?.validationStatus,
            cause = error,
        )
    }

    /** A single call timeout bounds both attempts. Mutations and authentication POSTs are never replayed. */
    class ReadRetry(private val internal: HttpUrl?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val endpoint = if (internal == null) {
                null
            } else if (ConnectionAddressRouter.owns(internal, request.url)) {
                Endpoint.INTERNAL
            } else {
                Endpoint.PUBLIC
            }
            val readOnly = request.method in setOf("GET", "HEAD", "PROPFIND") ||
                (request.method == "POST" && request.tag(ConnectionAddressRouter.ReadOnlyRequest::class.java) != null)
            fun proceed(request: okhttp3.Request): Response {
                val response = chain.proceed(request)
                if (!readOnly || !response.isSuccessful) return response
                return try {
                    val body = response.body
                    val contentType = body.contentType()
                    response.newBuilder().body(body.bytes().toResponseBody(contentType)).build()
                } catch (error: IOException) {
                    response.close()
                    throw error
                }
            }
            try {
                return proceed(request)
            } catch (error: IOException) {
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                val retryable = error !is ConnectionAddressVerification.Failure &&
                    causes.none { it is SSLException || it is SocketTimeoutException } && causes.any {
                        // OkHttp uses ProtocolException for an interrupted fixed-length response body.
                        it is EOFException || it is SocketException || it is ProtocolException
                    }
                if (!readOnly || !retryable || chain.call().isCanceled()) {
                    throw classify(error, endpoint) ?: error
                }
            }
            return try {
                proceed(request.newBuilder().header("Connection", "close").build())
            } catch (error: IOException) {
                throw classify(error, endpoint) ?: error
            }
        }
    }
}
