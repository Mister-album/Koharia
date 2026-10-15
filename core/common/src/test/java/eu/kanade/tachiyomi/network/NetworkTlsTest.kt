package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NetworkTlsTest {
    @Test
    fun untrustedCertificatesAndWrongHostnamesAreRejectedBeforeHttpCredentialsAreSent() {
        val parent = File("build/tmp").canonicalFile.apply { mkdirs() }
        val root = File(parent, "network-tls-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        val clients = mutableListOf<OkHttpClient>()
        var server: HttpsServer? = null
        mockkStatic(CookieManager::class, ContextCompat::class)
        try {
            val password = "temporary-fixture-password"
            val keyStoreFile = File(root, "fixture.p12")
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/$executable").absolutePath,
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStoreFile.absolutePath, "-storepass", password,
            ).redirectErrorStream(true).redirectOutput(File(root, "keytool.log")).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                error("Fixture certificate generation timed out")
            }
            check(process.exitValue() == 0) { "Fixture certificate generation failed" }
            val keys = KeyStore.getInstance("PKCS12").apply {
                keyStoreFile.inputStream().use { load(it, password.toCharArray()) }
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keys, password.toCharArray())
            }
            val serverTls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val received = AtomicInteger()
            val fixture = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator = HttpsConfigurator(serverTls)
                createContext("/fixture") { exchange ->
                    received.incrementAndGet()
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }.also { server = it }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
            val controlTls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            val control = OkHttpClient.Builder().sslSocketFactory(controlTls.socketFactory, manager).build()
                .also { clients += it }
            val url = "https://127.0.0.1:${fixture.address.port}/fixture"
            control.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(1, received.get())

            val cookies = mockk<CookieManager>(relaxed = true)
            every { CookieManager.getInstance() } returns cookies
            every { cookies.getCookie(any()) } returns ""
            every { ContextCompat.getMainExecutor(any()) } returns Executor { it.run() }
            val context = mockk<Context>()
            every { context.getExternalFilesDir(any()) } returns File(root, "external")
            every { context.filesDir } returns File(root, "files")
            every { context.cacheDir } returns File(root, "cache")
            val client = NetworkHelper(context, NetworkPreferences(InMemoryPreferenceStore())).nonCloudflareClient
                .also { clients += it }
            val request = Request.Builder().url(url).header("Authorization", "Bearer dummy-tls-canary").build()
            assertThrows(SSLHandshakeException::class.java) { client.newCall(request).execute().close() }
            assertEquals(1, received.get())

            // Trust only this fixture certificate to test the inherited hostname verifier independently.
            val wrongHost = client.newBuilder().sslSocketFactory(controlTls.socketFactory, manager)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                }).build().also { clients += it }
            val wrongRequest = request.newBuilder().url("https://wrong.fixture.invalid:${fixture.address.port}/fixture")
                .build()
            assertThrows(SSLPeerUnverifiedException::class.java) { wrongHost.newCall(wrongRequest).execute().close() }
            assertEquals(1, received.get())
        } finally {
            clients.forEach {
                it.cache?.close()
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
            server?.stop(0)
            unmockkStatic(CookieManager::class, ContextCompat::class)
            check(root.canonicalFile.parentFile == parent && root.deleteRecursively())
        }
    }
}
