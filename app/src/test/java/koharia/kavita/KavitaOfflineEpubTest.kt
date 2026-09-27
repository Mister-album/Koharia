package koharia.kavita

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class KavitaOfflineEpubTest {
    private var downloads = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val resources = "/reader/api/Book/4/book-resources"
            val body = when {
                path.endsWith("authenticate") || path.endsWith("refresh-account") -> """
                    {"username":"fixture","token":"fixture-token","kavitaVersion":"0.9.1.4",
                    "roles":[${if (downloads) "\"Download\"" else "\"Read Only\""}]}
                """.trimIndent()
                path.endsWith("book-info") -> """{"pages":2,"bookTitle":"中文 & test"}"""
                path.endsWith("/chapters") -> """[{"title":"第一章","page":0,"part":"heading"}]"""
                path.endsWith("book-page") -> """
                    <div><style>img{background:url('$resources?apiKey=secret&amp;file=a.svg')}</style>
                    <h1 id="heading">中文</h1><p>text</p>
                    <img src="$resources?apiKey=secret&amp;file=a.svg">
                    <a kavita-page="1" kavita-part="heading">Next</a></div>
                """.trimIndent()
                path.endsWith("book-resources") -> """<svg xmlns="http://www.w3.org/2000/svg"/>"""
                else -> error(path)
            }
            exchange.requestBody.close()
            exchange.responseHeaders.add(
                "Content-Type",
                if (path.endsWith("book-resources")) "image/svg+xml" else "text/plain",
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val api = KavitaApiClient(
        OkHttpClient(),
        "http://127.0.0.1:${server.address.port}/reader/",
        "fixture-key",
        "fixture",
    )
    private val directory = File("../.test-artifacts/kavita/offline-epub-test").apply { mkdirs() }
    private val target = File(directory, "fixture.epub")
    private val catalog = KavitaCatalog(1, "a", MemoryKavitaRepository(), api) {}

    @AfterEach fun close() {
        api.close()
        server.stop(0)
        target.delete()
    }

    @Test fun exportPreservesPageOrderAnchorsAndResourcesWithoutCredentials() = runTest {
        KavitaOfflineEpub(api, catalog).write(4, target) {}
        ZipFile(target).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals("mimetype", entries.first().name)
            assertEquals(java.util.zip.ZipEntry.STORED, entries.first().method)
            assertEquals(1, entries.count { "/assets/" in it.name })
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            for (entry in entries.filter { it.name.endsWith(".html") || it.name.endsWith(".opf") }) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                factory.newDocumentBuilder().parse(bytes.inputStream())
                val text = bytes.toString(Charsets.UTF_8)
                assertFalse(text.contains("apiKey"))
                assertFalse(text.contains("secret"))
                assertFalse(text.contains("kavita.invalid"))
            }
            val html = zip.getInputStream(zip.getEntry("OEBPS/koharia-epub/page-0.html")).reader().readText()
            assertTrue(html.contains("//body/p[1]"))
            assertTrue(html.contains("page-1.html#heading"))
            assertTrue(html.contains("assets/"))
        }
    }

    @Test fun noDownloadPermissionRejectsOfflineExport() = runTest {
        downloads = false
        val error = runCatching { KavitaOfflineEpub(api, catalog).write(4, target) {} }.exceptionOrNull()
        assertEquals(KavitaException.Reason.PERMISSION, (error as? KavitaException)?.reason)
        assertFalse(target.exists())
        assertFalse(KavitaCapabilities(KavitaVersion(0, 9), setOf("Read Only")).writable)
    }
}
