package koharia.connection

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** One transport recovery for replayable reads; mutations retain retryOnConnectionFailure(false). */
class ConnectionReadRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (error: IOException) {
            if (!request.isConnectionReadOnly() || request.body?.isOneShot() == true ||
                request.body?.isDuplex() == true || chain.call().isCanceled() ||
                Thread.currentThread().isInterrupted || error is InterruptedIOException ||
                error is ProtocolException || error is SSLException ||
                generateSequence<Throwable>(error) { it.cause }
                    .any { it is ConnectionAddressRouter.NonRoutingFailure }
            ) {
                throw error
            }
            NetworkDiagnostics.record("ReadRecovery call=${NetworkDiagnostics.callId(chain.call())} attempt=2")
            return chain.proceed(request)
        }
    }
}
