package koharia.testing

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.annotation.Keep
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.function.Consumer

object LanraragiContentIsolationFixture {
    @Keep
    @JvmStatic
    fun run(context: Context, status: Consumer<String>): Unit = runBlocking(Dispatchers.IO) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val version = "v80_test_content_${UUID.randomUUID()}"
        val address = "http://127.0.0.1:38729/$version/lrr/"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val mangas = Injekt.get<MangaRepository>()
        val hadActive = preferences.activeConnectionId.isSet()
        val previousActive = preferences.activeConnectionId.get()
        val inventory = profiles.profiles()
        val owned = mutableListOf<Long>()
        val owners = mutableListOf<LanraragiSource>()
        val chapters = mutableListOf<Chapter>()
        val snapshots = mutableListOf<List<LanraragiEntry>>()
        val loaders = mutableListOf<HttpPageLoader>()
        val root = File(context.cacheDir, "content-isolation-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(
                type: String?,
            ) = File(root, "external/${type.orEmpty()}").apply { mkdirs() }
        }
        var ownedCache: ChapterCache? = null
        fun pixel(page: ReaderPage): Int = checkNotNull(page.stream).invoke().use { stream ->
            checkNotNull(BitmapFactory.decodeStream(stream)).let { bitmap ->
                bitmap.getPixel(0, 0).also { bitmap.recycle() }
            }
        }
        try {
            val cache = ChapterCache(isolated, Injekt.get<Json>()).also { ownedCache = it }
            preferences.activeConnectionId.set(ConnectionPreferences.LOCAL_CONNECTION_ID)
            for (role in listOf("a", "b")) {
                val profile = profiles.add("lanraragi", "Content $role ${UUID.randomUUID()}")
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
                check(entry.title.startsWith("$role:"))
                val manga = source.materialize(entry)
                val chapter = source.getChapterList(manga.toSManga()).single()
                chapters +=
                    Chapter.create().copy(id = manga.id, mangaId = manga.id, url = chapter.url, name = chapter.name)
            }
            suspend fun load(index: Int, offline: Boolean): ReaderPage {
                preferences.activeConnectionId.set(owned[index])
                val readerChapter = ReaderChapter(chapters[index])
                val loader = HttpPageLoader(readerChapter, owners[index], cache).also { loaders += it }
                val pages = loader.getPages()
                check(pages.size == 3)
                readerChapter.state = ReaderChapter.State.Loaded(pages)
                pages.forEach { it.chapter = readerChapter }
                if (offline) loader.setNetworkRequestsDeferred(true)
                loader.setActivePage(pages.first())
                val ready = withTimeout(15_000) {
                    pages.first().statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                }
                check(ready == Page.State.Ready) { "Owned cached page did not become readable" }
                return pages.first()
            }
            val first = load(0, offline = false)
            check(pixel(first) == Color.rgb(230, 70, 60))
            val second = load(1, offline = false)
            check(first.imageUrl == second.imageUrl)
            check(pixel(second) == Color.rgb(40, 170, 90)) { "Second account received another account's cached page" }
            withTimeout(5_000) {
                while (chapters.any { cache.getPageListFromCache(it) == null }) delay(25)
            }
            val request = URI("http://127.0.0.1:38729/_fixture/control?version=$version&offline=true")
                .toURL().openConnection() as HttpURLConnection
            try {
                request.connectTimeout = 5_000
                request.readTimeout = 5_000
                check(request.responseCode == 200)
                request.inputStream.use { it.readBytes() }
            } finally {
                request.disconnect()
            }
            val reopenedFirst = load(0, offline = true)
            val reopenedSecond = load(1, offline = true)
            check(pixel(reopenedFirst) == Color.rgb(230, 70, 60))
            check(pixel(reopenedSecond) == Color.rgb(40, 170, 90))
            cache.clear()
            check(pixel(first) == Color.rgb(230, 70, 60))
            check(pixel(second) == Color.rgb(40, 170, 90))
            owners.forEachIndexed { index, source -> check(snapshots[index] == source.repository.entries(source.id)) }
            status.accept("two-account-pages;offline-new-loaders;active-leases-retained")
        } finally {
            loaders.forEach { it.recycle() }
            ownedCache?.let { cache ->
                val disk = ChapterCache::class.java.getDeclaredField("diskCache").apply { isAccessible = true }
                    .get(cache) as DiskLruCache
                disk.close()
            }
            owned.forEach { id ->
                profiles.remove(id).getOrThrow()
                mangas.deleteMangaBySourceId(id)
            }
            if (hadActive) {
                preferences.activeConnectionId.set(
                    previousActive,
                )
            } else {
                preferences.activeConnectionId.delete()
            }
            preferences.flush()
            check(profiles.profiles().containsAll(inventory))
            check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
            status.accept("owned-cache-profiles-sql-removed;preferences-restored")
        }
    }
}
