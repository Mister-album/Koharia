package koharia.testing

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.function.Consumer

object LanraragiCoverIsolationFixture {
    @Keep
    @JvmStatic
    fun run(context: Context, status: Consumer<String>): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val version = "v80_test_cover_${UUID.randomUUID()}"
        val address = "http://127.0.0.1:38719/$version/lrr/"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<MangaRepository>()
        val covers = Injekt.get<CoverCache>()
        val hadActive = preferences.activeConnectionId.isSet()
        val previousActive = preferences.activeConnectionId.get()
        val inventory = profiles.profiles()
        val owned = mutableListOf<Long>()
        val books = mutableListOf<Manga>()
        val owners = mutableListOf<LanraragiSource>()
        val snapshots = mutableListOf<List<LanraragiEntry>>()
        suspend fun pixel(manga: Manga, coverModel: Boolean): Int {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(if (coverModel) manga.asMangaCover() else manga)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .allowHardware(false)
                    .size(64, 96)
                    .build(),
            )
            check(result is SuccessResult) { "Owned fixture cover decode failed" }
            return result.image.asDrawable(context.resources).toBitmap().let { image ->
                image.getPixel(0, 0).also { image.recycle() }
            }
        }
        fun offline() {
            val request = URI("http://127.0.0.1:38719/_fixture/control?version=$version&offline=true")
                .toURL().openConnection() as HttpURLConnection
            try {
                request.connectTimeout = 5_000
                request.readTimeout = 5_000
                assertEquals(200, request.responseCode)
                request.inputStream.use { it.readBytes() }
            } finally {
                request.disconnect()
            }
        }
        try {
            preferences.activeConnectionId.set(ConnectionPreferences.LOCAL_CONNECTION_ID)
            for (role in listOf("a", "b")) {
                val profile = profiles.add("lanraragi", "Cover $role ${UUID.randomUUID()}")
                owned += profile.id
                profiles.commitConfiguration(profile.id) { draft ->
                    LanraragiPreferences(profile.id, draft).save(address, "fixture-$role")
                }
                val source = withTimeout(10_000) {
                    while (sources.get(profile.id) !is LanraragiSource) delay(25)
                    sources.get(profile.id) as LanraragiSource
                }
                owners += source
                source.refreshLibrary().getOrThrow()
                val entries = source.repository.entries(source.id)
                snapshots += entries
                val entry = entries.first { it.kind == LanraragiEntry.Kind.ARCHIVE }
                assertTrue(entry.title.startsWith("$role:"))
                val manga = source.materialize(entry)
                repository.update(MangaUpdate(manga.id, favorite = true))
                books += repository.getMangaById(manga.id)
            }
            assertEquals(books[0].thumbnailUrl, books[1].thumbnailUrl)
            preferences.activeConnectionId.set(owned[0])
            assertEquals(Color.rgb(230, 70, 60), pixel(books[0], coverModel = false))
            preferences.activeConnectionId.set(owned[1])
            assertEquals(Color.rgb(40, 170, 90), pixel(books[1], coverModel = true))
            offline()
            preferences.activeConnectionId.set(owned[0])
            assertEquals(Color.rgb(230, 70, 60), pixel(books[0], coverModel = true))
            preferences.activeConnectionId.set(owned[1])
            assertEquals(Color.rgb(40, 170, 90), pixel(books[1], coverModel = false))
            owners.forEachIndexed { index, source ->
                assertEquals(snapshots[index], source.repository.entries(source.id))
            }
            assertEquals(1, covers.deleteFromCache(books[0]))
            assertEquals(Color.rgb(40, 170, 90), pixel(books[1], coverModel = true))
            status.accept("two-accounts;two-models;offline;delete-owned-only")
        } finally {
            books.forEach { covers.deleteFromCache(it) }
            owned.forEach { id ->
                profiles.remove(id).getOrThrow()
                repository.deleteMangaBySourceId(id)
            }
            if (hadActive) {
                preferences.activeConnectionId.set(
                    previousActive,
                )
            } else {
                preferences.activeConnectionId.delete()
            }
            preferences.flush()
            assertTrue(profiles.profiles().containsAll(inventory))
            status.accept("owned-covers-profiles-sql-removed;preferences-restored")
        }
    }

    @Keep
    @JvmStatic
    fun verifyCleanup(context: Context) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val root = File(context.cacheDir, "cover-cleanup-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val isolated = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = File(root, type ?: "external").also { it.mkdirs() }
        }
        try {
            val cache = CoverCache(isolated)
            val url = "https://fixture.invalid/shared-cover.png"
            val first = Manga.create().copy(id = 1, source = 11, thumbnailUrl = url)
            val second = first.copy(id = 2, source = 22)
            val removed = first.copy(id = 3, source = 33)
            val legacy = File(isolated.getExternalFilesDir("covers"), DiskUtil.hashKeyForDisk(url))
            legacy.writeText("unscoped-legacy")
            val firstFile = checkNotNull(cache.getCoverFile(first.source, url))
            val secondFile = checkNotNull(cache.getCoverFile(second.source, url))
            val removedFile = checkNotNull(cache.getCoverFile(removed.source, url))
            check(firstFile != secondFile && firstFile != legacy && secondFile != legacy)
            firstFile.writeText("first")
            secondFile.writeText("second")
            removedFile.writeText("orphan")
            assertEquals(1, cache.deleteFromCache(first))
            assertEquals("second", secondFile.readText())
            assertEquals("unscoped-legacy", legacy.readText())
            firstFile.writeText("first")
            // An explicit orphan cleanup may remove obsolete unscoped files, never the active owner's file.
            assertEquals(3, cache.deleteOrphaned(listOf(second)))
            assertEquals("second", secondFile.readText())
            check(!firstFile.exists() && !removedFile.exists() && !legacy.exists())
            assertEquals(null, cache.getCoverFile(second.source, null))
        } finally {
            check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
        }
    }

    private fun assertTrue(value: Boolean) = check(value)

    private fun assertEquals(expected: Any?, actual: Any?) = check(expected == actual) {
        "Expected $expected, actual $actual"
    }
}
