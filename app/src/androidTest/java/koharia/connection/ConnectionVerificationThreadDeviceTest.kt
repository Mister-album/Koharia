package koharia.connection

import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real TLS shutdown, called from Android's main dispatcher; all endpoints and credentials are fixtures. */
@RunWith(AndroidJUnit4::class)
class ConnectionVerificationThreadDeviceTest {
    @Before fun requireFixture() {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }

    @Test fun lanraragiDualAddressVerificationReturnsToMainAfterTlsCleanup() = runBlocking {
        Fixture().use { fixture ->
            onStrictMain {
                ConnectionAddressVerification(fixture.client).verify(
                    ConnectionAddressVerification.Provider.LANRARAGI,
                    fixture.base + "public/",
                    fixture.base + "internal/",
                    headers,
                )
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
            }
            fixture.awaitClosedConnections()
            assertEquals(listOf("/public/api/plugins/metadata", "/internal/api/plugins/metadata"), fixture.paths)
        }
    }

    @Test fun komgaVerificationAlsoCleansTlsOffMain() = runBlocking {
        Fixture().use { fixture ->
            onStrictMain {
                ConnectionAddressVerification(fixture.client).verify(
                    ConnectionAddressVerification.Provider.KOMGA,
                    fixture.base + "public/",
                    "",
                    headers,
                )
            }
            fixture.awaitClosedConnections()
            assertEquals(listOf("/public/api/v1/client-settings/user/list"), fixture.paths)
        }
    }

    @Test fun authenticationFailureSurvivesTlsCleanupOnMainCaller() = runBlocking {
        Fixture(rejectInternal = true).use { fixture ->
            onStrictMain {
                val error = runCatching {
                    ConnectionAddressVerification(fixture.client).verify(
                        ConnectionAddressVerification.Provider.LANRARAGI,
                        fixture.base + "public/",
                        fixture.base + "internal/",
                        headers,
                    )
                }.exceptionOrNull()
                assertTrue(error is ConnectionAddressVerification.Failure)
                error as ConnectionAddressVerification.Failure
                assertEquals(ConnectionAddressVerification.Reason.AUTHENTICATION, error.reason)
                assertEquals(ConnectionValidation.Endpoint.INTERNAL, error.endpoint)
                assertEquals(401, error.status)
            }
            fixture.awaitClosedConnections()
        }
    }

    @Test fun cancellingMainCallerReleasesConnectionsAndPreservesCancellation() = runBlocking {
        Fixture(blockInternal = true).use { fixture ->
            onStrictMain {
                val job = launch {
                    ConnectionAddressVerification(fixture.client).verify(
                        ConnectionAddressVerification.Provider.LANRARAGI,
                        fixture.base + "public/",
                        fixture.base + "internal/",
                        headers,
                    )
                }
                withTimeout(10_000) {
                    while (fixture.paths.none { it.startsWith("/internal/") }) delay(10)
                }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
            fixture.awaitClosedConnections()
        }
    }

    private suspend fun onStrictMain(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        withContext(Dispatchers.Main) {
            val previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(previous).detectNetwork().penaltyDeathOnNetwork().build(),
            )
            try {
                withTimeout(20_000) { block() }
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }

    private class Fixture(val rejectInternal: Boolean = false, val blockInternal: Boolean = false) : Closeable {
        private val store = KeyStore.getInstance("PKCS12").apply {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("connection-verification/localhost-fixture.p12").use {
                    load(it, "fixture-password".toCharArray())
                }
        }
        private val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-password".toCharArray())
        }
        private val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }
        private val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        private val server = tls.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val connections = ConcurrentHashMap.newKeySet<Socket>()
        val paths = CopyOnWriteArrayList<String>()
        val base = "https://127.0.0.1:${server.localPort}/"
        val client = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust.trustManagers.single() as X509TrustManager).build()

        init {
            workers.execute {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        connections.add(socket)
                        workers.execute { serve(socket) }
                    } catch (error: IOException) {
                        if (!server.isClosed) throw error
                    }
                }
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use {
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream().bufferedReader()
                    while (true) {
                        val request = input.readLine()?.split(' ') ?: break
                        assertEquals("GET", request[0])
                        val path = request[1]
                        paths.add(path)
                        while (true) {
                            if (input.readLine().isNullOrEmpty()) break
                        }
                        if (blockInternal && path.startsWith("/internal/")) {
                            input.read() // Wait for cancellation to close the socket, without sending a response.
                            break
                        }
                        val status = if (rejectInternal && path.startsWith("/internal/")) 401 else 200
                        val body = if (path.endsWith("/metadata")) "[]" else "{}"
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body"
                                    ).toByteArray(),
                            )
                            flush()
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing/cancelling a fixture TLS connection may reset it instead of sending close_notify.
            } finally {
                connections.remove(socket)
            }
        }

        suspend fun awaitClosedConnections() {
            assertFalse(paths.isEmpty())
            withTimeout(5_000) { while (connections.isNotEmpty()) delay(10) }
        }

        override fun close() {
            server.close()
            connections.forEach { it.close() }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private companion object {
        val headers = Headers.Builder().add("Authorization", "Bearer fixture-key").build()
    }
}
