package koharia.kavita

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Opt-in live smoke test. Uses only the publicly advertised demo and read-only API calls. */
class KavitaDemoTest {
    @Test fun officialDemoReadOnlyContract() = runBlocking {
        assumeTrue(System.getenv("KAVITA_DEMO_TEST") == "true")
        val network = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val homepage = network.newCall(Request.Builder().url("https://www.kavitareader.com/").build())
            .execute().use {
                require(it.isSuccessful)
                it.body.string()
            }
        val demoLink = Jsoup.parse(homepage).select("a[href]").map { it.attr("href") }
            .first { it.startsWith("https://demo.kavitareader.com/login?apiKey=") }.toHttpUrl()
        val key = requireNotNull(demoLink.queryParameter("apiKey"))
        KavitaApiClient(network, "https://demo.kavitareader.com/", key, "demo-readonly").use { api ->
            val account = api.getAccount()
            assertTrue(KavitaVersion.parse(account.kavitaVersion)!! >= KavitaVersion(0, 8))
            assertFalse(api.capabilities().downloads)
            assertFalse(api.capabilities().writable)
            val catalog = KavitaCatalog(1, "demo", MemoryKavitaRepository(), api) {}
            val libraries = catalog.libraries()
            assertTrue(libraries.isNotEmpty())
            val formats = mutableSetOf<Int>()
            for (library in libraries) {
                val shelf = catalog.page(1, KavitaFilter(listOf(KavitaFilterStatement(19, 0, library.id.toString()))))
                val series = shelf.items.firstOrNull() ?: continue
                assertEquals(library.id, series.libraryId)
                catalog.metadata(series.id)
                val volumes = catalog.volumes(series.id)
                val chapter = volumes.flatMap { it.chapters }.first()
                assertEquals(chapter.id, catalog.chapter(chapter.id).id)
                formats += series.format
                if (series.format == 3) {
                    assertTrue(catalog.book(chapter.id).pages > 0)
                    catalog.toc(chapter.id)
                    api.client.newCall(api.request("Book/${chapter.id}/book-page", "page" to 0)).execute().use {
                        assertEquals(200, it.code)
                        val html = rewriteKavitaHtml(it.body.string(), api, chapter.id)
                        assertFalse(html.contains(key))
                        assertFalse(html.contains("apiKey="))
                        assertTrue(html.contains("data-koharia-kavita-path"))
                    }
                }
            }
            assertTrue(3 in formats)
        }
    }
}
