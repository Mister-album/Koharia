package koharia.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiAuthMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class PageProgressSessionDeviceTest {
    @Test
    fun productionPageProvidersCannotOmitOpeningSessions() {
        checkFixture()
        Injekt.get<ConnectionRegistry>().availableProviders().forEach { provider ->
            val id = System.nanoTime()
            val preferences = sourcePreferences("source_$id")
            check(preferences.all.isEmpty())
            val source = provider.createSource(LibraryConnectionProfile(id, provider.id, "Session contract fixture"))
            try {
                if (source is ConnectionPageProgressAdapter) {
                    assertTrue(provider.id, source is ConnectionPageProgressSessionAdapter)
                    assertTrue(provider.id, source is ConnectionProgressSyncStatusAdapter)
                }
            } finally {
                (source as? AutoCloseable)?.close()
                check(preferences.edit().clear().commit())
            }
        }
    }

    @Test
    fun sameSourceReloadRejectsEverySmangaPageOperation(): Unit = runBlocking(Dispatchers.IO) {
        withSource("smanga") { id ->
            SmangaPreferences(id).save("http://127.0.0.1:1", "fixture", "fixture", 1)
            val source = SmangaSource(checkFixture(), LibraryConnectionProfile(id, "smanga", "Session fixture"))
            try {
                val chapter = Chapter.create().copy(url = "${source.session().prefix}chapter/1/2/3.pages")
                val opened = source.capturePageProgressSession(chapter)
                source.reload()
                rejected(opened, chapter)
                source.capturePageProgressSession(chapter).checkCurrent()
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun sameSourceReloadRejectsEverySuwayomiPageOperation(): Unit = runBlocking(Dispatchers.IO) {
        withSource("suwayomi") { id ->
            SuwayomiPreferences(id).save("http://127.0.0.1:1", "", SuwayomiAuthMode.NONE, "", "")
            val source = SuwayomiSource(checkFixture(), LibraryConnectionProfile(id, "suwayomi", "Session fixture"))
            try {
                val chapter = Chapter.create().copy(url = source.session().identity.chapterUrl(1, 2))
                val opened = source.capturePageProgressSession(chapter)
                source.reload()
                rejected(opened, chapter)
                source.capturePageProgressSession(chapter).checkCurrent()
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun lanraragiReloadRestoreAndCredentialChangesRejectCapturedPages(): Unit = runBlocking(Dispatchers.IO) {
        withSource("lanraragi") { id ->
            val preferences = LanraragiPreferences(id)
            preferences.save("http://127.0.0.1:1", "fixture")
            val source = LanraragiSource(checkFixture(), LibraryConnectionProfile(id, "lanraragi", "Session fixture"))
            try {
                val chapter = Chapter.create().copy(url = "/lanraragi/$id/archive/fixture")
                val opened = source.capturePageProgressSession(chapter)
                source.reload()
                rejected(opened, chapter)
                val afterReload = source.capturePageProgressSession(chapter)
                source.prepareReadingStateRestore(listOf(chapter.url))
                rejected(afterReload, chapter)
                val afterRestore = source.capturePageProgressSession(chapter)
                preferences.save(preferences.address, "replacement")
                rejected(afterRestore, chapter)
                source.capturePageProgressSession(chapter).checkCurrent()
            } finally {
                source.close()
            }
        }
    }

    private suspend fun rejected(
        session: ConnectionProgressSession<ConnectionReaderPageProgressAdapter>,
        chapter: Chapter,
    ) {
        val operations: List<suspend () -> Unit> = listOf(
            { session.checkCurrent() },
            { session.adapter.beginReadingSession(chapter.url) },
            { session.adapter.pullPageProgress(chapter.url, chapter.memo) },
            { session.adapter.pushPageProgress(chapter.url, 1, 10) },
            { session.adapter.recordLocalPageProgress(chapter.url, 1, 10, 100) },
            { session.adapter.confirmLocalPageProgress(chapter.url, 1, 10, 100) },
            { session.adapter.acceptRemotePageProgress(chapter.url, 1, 10, 100) },
            {
                session.adapter.commitLocalPageProgress(chapter.url, 1, 10, 100, false) {
                    error("obsolete chapter write")
                }
            },
            {
                session.adapter.confirmPageComparison(
                    chapter.url,
                    ConnectionPageProgressSnapshot(
                        chapter.url, 1, 10, false, null, false, false, chapter.memo, null, null,
                    ),
                    1,
                    10,
                    100,
                    ConnectionPageProgressDecision.COMPARED,
                ) { error("obsolete comparison commit") }
            },
        )
        operations.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is CancellationException) }
    }

    private fun checkFixture() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "app.koharia.dev.devicefixture")
    }

    private suspend fun withSource(provider: String, block: suspend (Long) -> Unit) {
        checkFixture()
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty()) { "Fixture preferences already exist for $provider" }
        try {
            block(id)
        } finally {
            check(preferences.edit().clear().commit())
        }
    }
}
