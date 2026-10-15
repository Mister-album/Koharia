package koharia.connection

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URL

class KomgaMetadataSessionDeviceTest {
    @Test
    fun actualLateMetadataCannotCommitAfterSameIdentitySourceReplacement(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("metadataSessionAddress")
        assumeTrue(address != null)
        require(checkNotNull(address).matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<MangaRepository>()
        val update = Injekt.get<UpdateManga>()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val profile = profiles.add("komga", "Metadata session ${System.nanoTime()}")
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        fun control(path: String) = URL(address + "fixture/" + path).openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 3_000
        }.getInputStream().bufferedReader().use { it.readText() }
        try {
            profiles.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", address.trimEnd('/')).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture-old").putString("Password", "fixture-password").commit(),
                )
            }
            val original = sources.get(profile.id) as KomgaSource
            val local = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = profile.id, url = address + "api/v1/books/book", title = "Original"),
                ),
            ).single()
            val session = ConnectionSourceSession.capture(original)
            val responseTitle = CompletableDeferred<String>()
            val delayed = async {
                runCatching {
                    val remote = original.getMangaDetails(local.toSManga())
                    responseTitle.complete(remote.title)
                    update.awaitUpdateFromSource(local, remote, true, session::checkCurrent)
                }
            }
            try {
                withTimeout(10_000) { while (control("blocked") != "true") delay(25) }
                assertTrue(!delayed.isCompleted)
                profiles.commitConfiguration(profile.id) { draft ->
                    check(draft.edit().putString("fixture_session_revision", "replacement").commit())
                }
                val current = sources.get(profile.id) as KomgaSource
                assertNotSame(original, current)
                assertEquals("true", control("release"))
                assertTrue(withTimeout(10_000) { delayed.await() }.exceptionOrNull() is CancellationException)
                assertEquals("Obsolete title", withTimeout(10_000) { responseTitle.await() })
                assertEquals(local, repository.getMangaById(local.id))
                val currentSession = ConnectionSourceSession.capture(current)
                val fresh = current.getMangaDetails(local.toSManga())
                assertEquals("Current title", fresh.title)
                assertTrue(update.awaitUpdateFromSource(local, fresh, true, currentSession::checkCurrent))
                assertEquals("Current title", repository.getMangaById(local.id).title)
            } finally {
                control("release")
                withTimeout(10_000) { delayed.join() }
            }
        } finally {
            profiles.remove(profile.id).getOrThrow()
            repository.deleteMangaBySourceId(profile.id)
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
            foreground.close()
        }
    }
}
