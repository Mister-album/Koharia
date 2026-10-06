package koharia.suwayomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionProfileManager
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiConnectionProvider
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI

/** Opt-in recovery when an emulator kills instrumentation before the fixture's finally block. */
@RunWith(AndroidJUnit4::class)
class SuwayomiFixtureCleanupTest {
    @Test
    fun removeOnlyInterruptedLocalhostFixtures(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("suwayomiCleanupAborted") == "true")
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager: ConnectionProfileManager = Injekt.get()
        val mangas: MangaRepository = Injekt.get()
        val downloads: DownloadManager = Injekt.get()
        val profiles = manager.profiles().associateBy { it.id }
        val expectedAccount = SuwayomiIdentity.account("http://127.0.0.1:14567", SuwayomiAuthMode.NONE, "")
        val preferenceIds = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .mapNotNull { Regex("source_([0-9]+)\\.xml").matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() }
        for (id in preferenceIds + profiles.keys) {
            val profile = profiles[id]
            if (profile != null) {
                if (profile.providerId != SuwayomiConnectionProvider.ID || profile.name != "Suwayomi fixture") continue
                val preferences = SuwayomiPreferences(id)
                if (preferences.mode != SuwayomiAuthMode.NONE || preferences.username.isNotEmpty() ||
                    preferences.password.isNotEmpty() ||
                    runCatching { preferences.account }.getOrNull() != expectedAccount
                ) {
                    continue
                }
            }
            val owned = mangas.getMangaBySourceId(id)
            val fixtures = owned.filter {
                it.title == "Koharia integration fixture" &&
                    SuwayomiIdentity.fromMangaUrl(it.url)?.account == expectedAccount
            }
            if (fixtures.isEmpty() && profile == null) continue
            check(fixtures.size == owned.size) { "A fixture source contains unrelated manga; preserve it" }
            val source = Injekt.get<SourceManager>().get(id) as? SuwayomiSource
            downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == id })
            source?.let { current -> fixtures.forEach { downloads.deleteManga(it, current) } }
            source?.close()
            if (profile != null) manager.remove(id).getOrThrow()
            Injekt.get<SuwayomiRepository>().removeConnection(id)
            fixtures.forEach { mangas.deleteMangaById(it.id) }
        }
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directories = root.listFiles().orEmpty().filter { Regex("suwayomi-test-[0-9]+").matches(it.name) }
        val storage = Injekt.get<StoragePreferences>().baseStorageDirectory
        val configured = runCatching { File(URI(storage.get())).canonicalFile }.getOrNull()
        for (directory in directories) {
            check(directory.canonicalFile.parentFile == root)
            if (directory.canonicalFile == configured) storage.delete()
            assertTrue(directory.deleteRecursively())
        }
        println("Interrupted Suwayomi fixtures cleaned; other profiles and device data retained")
    }
}
