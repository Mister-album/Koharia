package koharia.connection

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.kavita.KavitaException
import koharia.smanga.SmangaException
import koharia.storage.StorageFailure
import koharia.suwayomi.SuwayomiException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.EOFException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class ConnectionValidationTest {
    @Test
    fun `provider authentication and permission errors share classifications without replacing specialized errors`() {
        val authentication = listOf(
            SmangaException(SmangaException.Reason.AUTH, 401),
            KavitaException(KavitaException.Reason.AUTHENTICATION, 401),
            SuwayomiException(SuwayomiException.Reason.AUTH, 401),
            StorageFailure(StorageFailure.Reason.AUTH),
        )
        val permission = listOf(
            SmangaException(SmangaException.Reason.PERMISSION, 403),
            KavitaException(KavitaException.Reason.PERMISSION, 403),
            SuwayomiException(SuwayomiException.Reason.AUTH, 403),
            StorageFailure(StorageFailure.Reason.PERMISSION),
        )
        authentication.forEach {
            val result = ConnectionValidation.classify(it, ConnectionValidation.Endpoint.INTERNAL)!!
            assertEquals(ConnectionAddressVerification.Reason.AUTHENTICATION, result.reason)
            assertEquals(ConnectionValidation.Endpoint.INTERNAL, result.endpoint)
        }
        permission.forEach {
            assertEquals(ConnectionAddressVerification.Reason.PERMISSION, ConnectionValidation.classify(it)!!.reason)
        }
        assertNull(ConnectionValidation.classify(SmangaException(SmangaException.Reason.OPDS_DISABLED)))
        assertNull(ConnectionValidation.classify(KavitaException(KavitaException.Reason.VERSION)))
        assertNull(ConnectionValidation.classify(SuwayomiException(SuwayomiException.Reason.VERSION)))
        assertNull(ConnectionValidation.classify(StorageFailure(StorageFailure.Reason.CONFLICT)))
    }

    @Test
    fun `verification transport isolates connection pools credentials and logging`() {
        val base = OkHttpClient.Builder().addInterceptor { error("Inherited logging must not run") }.build()
        val client = ConnectionValidation.client(base)
        assertNotSame(base.connectionPool, client.connectionPool)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertEquals(8_000, client.callTimeoutMillis)
        assertEquals(1, client.interceptors.size)
        assertTrue(client.networkInterceptors.isEmpty())
    }

    @Test
    fun `only explicitly read only operations can replay an interrupted connection`() {
        for (method in listOf("GET", "HEAD", "PROPFIND", "POST", "PATCH", "DELETE")) {
            val request = Request.Builder().url("https://fixture.invalid/")
                .method(method, if (method in listOf("POST", "PATCH")) "{}".toRequestBody() else null).build()
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns request
            every { chain.call().isCanceled() } returns false
            val response = mockk<Response>()
            every { response.isSuccessful } returns false
            every { chain.proceed(any()) } throws EOFException() andThen response
            val interceptor = ConnectionValidation.ReadRetry(null)
            if (method in listOf("GET", "HEAD", "PROPFIND")) {
                assertEquals(response, interceptor.intercept(chain))
                verify(exactly = 2) { chain.proceed(any()) }
            } else {
                assertThrows<ConnectionAddressVerification.Failure> { interceptor.intercept(chain) }
                verify(exactly = 1) { chain.proceed(any()) }
            }
        }
    }

    @Test
    fun `certificate and timeout failures are classified and never retried`() {
        for ((error, expected) in listOf(
            SSLHandshakeException("certificate") to ConnectionAddressVerification.Reason.CERTIFICATE,
            SocketTimeoutException("timeout") to ConnectionAddressVerification.Reason.TIMEOUT,
        )) {
            error.initCause(EOFException())
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns Request.Builder().url("https://fixture.invalid/").build()
            every { chain.call().isCanceled() } returns false
            every { chain.proceed(any()) } throws error
            val failure = assertThrows<ConnectionAddressVerification.Failure> {
                ConnectionValidation.ReadRetry(null).intercept(chain)
            }
            assertEquals(expected, failure.reason)
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    @Test
    fun `a read cannot replay a failed nested authentication or mutation`() {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns Request.Builder().url("https://fixture.invalid/").build()
        every { chain.call().isCanceled() } returns false
        every { chain.proceed(any()) } throws ConnectionAddressVerification.Failure(
            ConnectionAddressVerification.Reason.UNAVAILABLE,
            cause = EOFException(),
            stage = ConnectionAddressVerification.Stage.WRITE,
        )
        assertThrows<ConnectionAddressVerification.Failure> { ConnectionValidation.ReadRetry(null).intercept(chain) }
        verify(exactly = 1) { chain.proceed(any()) }
    }
}
