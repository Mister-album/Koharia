package koharia.connection

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.epub.progress.KomgaEpubProgressSyncService
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class KomgaDualAccountRoutingDeviceTest {
    @Test
    fun registeredSameAddressAccountsKeepComicTrackerAndEpubOwnership(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("dualAccountAddress")
        assumeTrue(address != null)
        val base = checkNotNull(address).trimEnd('/')
        require(base.startsWith("http://127.0.0.1:"))
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val mangas = Injekt.get<MangaRepository>()
        val epub = Injekt.get<KomgaEpubProgressSyncService>()
        val inventory = profiles.profiles()
        val active = preferences.activeConnectionId.get()
        val hadActive = preferences.activeConnectionId.isSet()
        val prefix = "Dual owner ${System.nanoTime()}"
        val owned = mutableSetOf<Long>()
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        try {
            val identities = listOf("first", "second").associateWith { role ->
                val profile = profiles.add("komga", "$prefix $role")
                owned += profile.id
                profiles.commitConfiguration(profile.id) { draft ->
                    check(
                        draft.edit().putString("Address", base).putString("AuthMode", "Credentials")
                            .putString("Username", "fixture-$role").putString("Password", "fixture-password").commit(),
                    )
                }
                val source = sources.get(profile.id) as KomgaSource
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = "$base/api/v1/readlists/list",
                            title = "$role list",
                        ),
                    ),
                ).single()
                source to manga
            }
            val first = identities.getValue("first").first
            val second = identities.getValue("second").first
            assertNotEquals(first.progressAccount().key, second.progressAccount().key)
            // Constructed from the first client's settings: every API call must still resolve its explicit owner.
            val api = KomgaApi(6, first.client)
            val book = "$base/api/v1/books/book"
            val series = "$base/api/v1/series/series"
            for (role in listOf("second", "first")) {
                val (source, manga) = identities.getValue(role)
                val otherRole = if (role == "first") "second" else "first"
                val (otherSource, otherManga) = identities.getValue(otherRole)
                preferences.activeConnectionId.set(otherSource.id)
                val expectedPage = if (role == "first") 2 else 7
                assertEquals(expectedPage, api.getSeriesBookProgress(series, source.id).single().readProgress!!.page)
                assertEquals(expectedPage - 1, checkNotNull(api.getBookProgress(book, source.id)).pageIndex)
                val initial = checkNotNull(epub.pullProgression(source.id, book).progression)
                assertEquals("initial-$role.xhtml", initial.locator.href.toString())
                val otherBefore = checkNotNull(epub.pullProgression(otherSource.id, book).progression)
                val lastRead = if (role == "first") 4.0 else 5.0
                val track = Track.create(6).apply {
                    manga_id = manga.id
                    tracking_url = manga.url
                    last_chapter_read = lastRead
                }
                assertEquals(lastRead, api.updateProgress(track).last_chapter_read, 0.0)
                val locator = checkNotNull(
                    Locator.fromJSON(
                        JSONObject(
                            """{"href":"updated-$role.xhtml","type":"application/xhtml+xml",
                                "locations":{"position":2,"totalProgression":0.5}}""",
                        ),
                    ),
                )
                epub.pushProgression(source.id, book, locator, emptyList(), Date())
                assertEquals(
                    "updated-$role.xhtml",
                    epub.pullProgression(source.id, book).progression!!.locator.href.toString(),
                )
                val otherAfter = checkNotNull(epub.pullProgression(otherSource.id, book).progression)
                assertEquals(otherBefore.locator.toJSON().toString(), otherAfter.locator.toJSON().toString())
                assertEquals(otherManga, mangas.getMangaById(otherManga.id))
                assertEquals(manga, mangas.getMangaById(manga.id))
                assertEquals(otherSource.id, preferences.activeConnectionId.get())
            }
            assertTrue(profiles.profiles().containsAll(inventory))
        } finally {
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
            for (id in owned) {
                check(profiles.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                profiles.remove(id).getOrThrow()
                mangas.deleteMangaBySourceId(id)
            }
            foreground.close()
        }
    }
}
