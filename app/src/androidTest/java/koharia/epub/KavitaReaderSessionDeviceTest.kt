package koharia.epub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionLocalEpubProgressAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapterRef
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

@RunWith(AndroidJUnit4::class)
class KavitaReaderSessionDeviceTest {
    @Test
    fun sameSourceReloadInvalidatesEpubAndReflowBindings(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        var source: KavitaSource? = null
        try {
            KavitaPreferences(id).save(
                "http://127.0.0.1:1",
                "fixture-key",
                KavitaAccount(id = 1, username = "fixture", roles = listOf("Admin"), kavitaVersion = "0.8.0"),
            )
            val owner = KavitaSource(context, LibraryConnectionProfile(id, "kavita", "Reader session fixture"))
            source = owner
            val opened = owner.session()
            val epub = Chapter.create().copy(
                id = -1,
                mangaId = -2,
                url = opened.identity.chapter(KavitaChapterRef(1, 2, 3, 4, 3)),
            )
            val pdf = epub.copy(url = opened.identity.chapter(KavitaChapterRef(1, 2, 3, 5, 4)))
            val book = owner.captureEpubProgressSession(epub)
            val reflow = owner.captureReflowProgressSession(pdf)
            val pages = owner.capturePageProgressSession(pdf)
            val local = book.adapter as ConnectionLocalEpubProgressAdapter
            val locator = checkNotNull(
                Locator.fromJSON(
                    JSONObject(
                        """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"position":1}}""",
                    ),
                ),
            )
            owner.reload()
            assertTrue(owner.session() !== opened)
            suspend fun rejected(operation: suspend () -> Unit) {
                assertTrue(runCatching { operation() }.exceptionOrNull() is CancellationException)
            }
            rejected { book.checkCurrent() }
            rejected { book.adapter.getCachedEpubProgress(epub.id) }
            rejected { book.adapter.refreshEpubProgress(epub.mangaId, epub) }
            rejected { book.adapter.pullEpubProgress(epub.url) }
            rejected { book.adapter.reviewEpubProgress(epub.url) }
            rejected { book.adapter.pushEpubProgress(epub.url, locator, emptyList(), Date()) }
            rejected { local.recordLocalEpubProgress(epub.url, locator, Date()) }
            rejected { local.acceptRemoteEpubProgress(epub.url, locator, Date()) }
            rejected { local.confirmLocalEpubProgress(epub.url, locator, Date()) }
            rejected { local.isEpubProgressConfirmed(epub.url, locator, Date()) }
            rejected { pages.checkCurrent() }
            rejected { pages.adapter.beginReadingSession(pdf.url) }
            rejected { pages.adapter.pullPageProgress(pdf.url, pdf.memo) }
            rejected { pages.adapter.pushPageProgress(pdf.url, 1, 10) }
            rejected { pages.adapter.recordLocalPageProgress(pdf.url, 1, 10, 100) }
            rejected { pages.adapter.acceptRemotePageProgress(pdf.url, 1, 10, 100) }
            rejected { pages.adapter.confirmLocalPageProgress(pdf.url, 1, 10, 100) }
            rejected { reflow.checkCurrent() }
            rejected { reflow.adapter.pullPageProgress(pdf.url, pdf.memo) }
            rejected { reflow.adapter.reviewPageProgress(pdf.url, pdf.memo) }
            rejected { reflow.adapter.isPageProgressConfirmed(pdf.url, 1, 10, 100) }
            rejected { reflow.adapter.pushPageProgress(pdf.url, 1, 10) }
            rejected { reflow.adapter.recordLocalPageProgress(pdf.url, 1, 10, 100) }
            rejected { reflow.adapter.acceptRemotePageProgress(pdf.url, 1, 10, 100) }
            rejected { reflow.adapter.confirmLocalPageProgress(pdf.url, 1, 10, 100) }
            val reopened = owner.captureEpubProgressSession(epub)
            reopened.checkCurrent()
            assertNull(reopened.adapter.getCachedEpubProgress(epub.id))
        } finally {
            source?.close()
            check(preferences.edit().clear().commit())
        }
    }
}
