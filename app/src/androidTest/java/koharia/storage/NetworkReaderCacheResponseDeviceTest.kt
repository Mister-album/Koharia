package koharia.storage

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.defaultBookshelfId
import koharia.source.local.withInitialBookshelves
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetworkReaderCacheResponseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun recordsCacheContentionAndTargetPageReadiness(): Unit = runBlocking(Dispatchers.IO) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val arguments = InstrumentationRegistry.getArguments()
        val mode = LibraryStorageMode.valueOf(checkNotNull(arguments.getString("cacheReaderMode")))
        check(mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val base = checkNotNull(arguments.getString("cacheReaderAddress"))
        check(URI(base).host == "127.0.0.1")
        val user = arguments.getString("cacheReaderUser").orEmpty()
        val password = arguments.getString("cacheReaderPassword").orEmpty()
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val local = File(context.cacheDir, "cache-reader-${UUID.randomUUID()}").canonicalFile
        check(local.parentFile == context.cacheDir.canonicalFile && !local.exists() && local.mkdirs())
        val folder = "reader-${UUID.randomUUID()}"
        val parent = NetworkStorageRuntime.backend(NetworkStorageConfiguration(mode, base), base, user, password)
        var created = false
        val profile = profiles.add("local-folder", "Owned cache reader $mode")
        var probe: Probe? = null
        try {
            val shared = Injekt.get<SharedAppPreferences>()
            replace(connections.activeConnectionId, profile.id)
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().incognitoMode, true)
            replace(shared.basePreferences().downloadedOnly, false)
            replace(shared.downloadPreferences().removeAfterReadSlots, -1)
            val reader = shared.readerPreferences()
            replace(reader.showNavigationOverlayNewUser, false)
            replace(reader.showNavigationOverlayOnStart, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            val seed = File(local, "book.cbz")
            createComic(seed)
            val original = digest(seed)
            parent.createDirectory(folder)
            created = true
            val address = base.trimEnd('/') + "/$folder/"
            val initial = NetworkStorageConfiguration(mode = mode, address = address, persistentIdentity = false)
            val backend = NetworkStorageRuntime.backend(initial, address, user, password)
            val rootIdentity = try {
                val identity = StorageIdentity.ensure(backend)
                backend.createDirectory("Series")
                seed.inputStream().use { backend.write("Series/book.cbz", it, seed.length()) }
                identity
            } finally {
                backend.close()
            }
            val config = initial.copy(rootIdentity = rootIdentity)
            val account = NetworkStoragePreferences.account(config, user)
            val uri = DocumentsContract.buildDocumentUri(
                NetworkStorageRuntime.authority(context),
                "${profile.id}|$account|",
            )
            val library = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            profiles.commitConfiguration(profile.id) { draft ->
                NetworkStoragePreferences(profile.id, draft).save(config, user, password)
                LocalLibraryPreferences(profile.id, Json, draft).setConfig(
                    library.copy(
                        setupCompleted = true,
                        roots = listOf(
                            LocalLibraryRootConfig(
                                id = "root",
                                treeUri = uri.toString(),
                                contentType = LocalLibraryContentType.COMICS,
                                bookshelfId = library.defaultBookshelfId(LocalLibraryContentType.COMICS),
                            ),
                        ),
                    ),
                )
            }
            val source = Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
            source.refreshLibrary().getOrThrow()
            val manga = mangas.getMangaBySourceId(profile.id).single()
            Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            NetworkStorageRuntime.removeCachedContent(context, profile.id)
            val cacheRoot = File(context.filesDir, "library-storage-cache")
            val namespace = File(cacheRoot, storageDigest("${profile.id}/$account/$rootIdentity"))
            check(!namespace.exists())
            val index = StorageCacheIndex.get(cacheRoot)
            val scansBefore = index.scans
            val observation = Probe(
                index,
                InstrumentationRegistry.getArguments().getString("cacheReaderSampleMain") == "true",
            )
            probe = observation
            observation.start()
            val pageTimes = JSONArray()
            val opening = System.nanoTime()
            observation.phase.set("opening")
            FixtureActivityLauncher.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id, profile.id),
            ).use { scenario ->
                lateinit var activity: ReaderActivity
                scenario.onActivity { activity = it }
                awaitPage(activity, manga.id, chapter.id, profile.id, 0)
                pageTimes.put(JSONObject().put("phase", "first").put("ms", (System.nanoTime() - opening) / 1e6))
                for ((phase, pages) in listOf("forward" to (1..11).toList(), "reverse" to (10 downTo 0).toList())) {
                    for (page in pages) {
                        observation.phase.set("$phase-$page")
                        val started = System.nanoTime()
                        withContext(Dispatchers.Main) {
                            val state = activity.viewModel.state.value
                            state.viewer!!.moveToPage(state.currentChapter!!.pages!![page])
                        }
                        awaitPage(activity, manga.id, chapter.id, profile.id, page)
                        pageTimes.put(
                            JSONObject().put("phase", phase).put("page", page)
                                .put("ms", (System.nanoTime() - started) / 1e6),
                        )
                    }
                }
                observation.phase.set("closing")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                withTimeout(15000) { while (!activity.isDestroyed) delay(50) }
            }
            observation.stop()
            val payload = namespace.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            check(payload > 0 && index.scans - scansBefore <= 1)
            check(digest(seed) == original)
            val remoteEntry = parent.stat("$folder/Series/book.cbz")
            check(remoteEntry.size == seed.length())
            val remoteHash = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            while (offset < remoteEntry.size) {
                val size = minOf(262144L, remoteEntry.size - offset).toInt()
                val bytes = parent.read(remoteEntry, offset, size)
                check(bytes.size == size)
                remoteHash.update(bytes)
                offset += size
            }
            check(remoteHash.digest().toList() == original)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "cache_reader",
                        observation.report().put("mode", mode.name).put("pages", pageTimes)
                            .put("cache_bytes", payload).put("source_bytes", seed.length())
                            .put("scans", index.scans - scansBefore).toString(),
                    )
                },
            )
        } finally {
            probe?.stop()
            try {
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
            } finally {
                try {
                    if (created) {
                        suspend fun removeOwned(path: String) {
                            check(path == folder || path.startsWith("$folder/"))
                            parent.list(path).forEach { child ->
                                check(child.path.startsWith("$folder/"))
                                if (child.directory) removeOwned(child.path) else parent.delete(child)
                            }
                            parent.delete(parent.stat(path))
                        }
                        removeOwned(folder)
                    }
                } finally {
                    parent.close()
                    restores.asReversed().forEach { it() }
                    check(local.parentFile == context.cacheDir.canonicalFile && local.deleteRecursively())
                }
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("cache_reader_cleanup", "$mode;preferences-restored")
                },
            )
        }
    }

    private suspend fun awaitPage(activity: ReaderActivity, manga: Long, chapter: Long, source: Long, page: Int) =
        withTimeout(30000) {
            while (true) {
                val ready = withContext(Dispatchers.Main) {
                    val state = activity.viewModel.state.value
                    state.manga?.id == manga && state.manga.source == source &&
                        state.currentChapter?.chapter?.id == chapter && state.totalPages == 12 &&
                        state.currentPage == page + 1 &&
                        state.currentChapter?.pages?.get(page)?.status == Page.State.Ready &&
                        descendants(activity.window.decorView).filterIsInstance<PagerPageHolder>()
                            .filter { it.page.index == page }
                            .any { holder ->
                                descendants(holder).filterIsInstance<SubsamplingScaleImageView>().any { it.isReady }
                            }
                }
                if (ready) break
                delay(25)
            }
        }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun createComic(file: File) {
        val random = Random(240)
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(12) { page ->
                val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
                try {
                    val pixels = IntArray(512 * 768) { random.nextInt() or -0x1000000 }
                    bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 768)
                    zip.putNextEntry(ZipEntry("%03d.png".format(page)))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun digest(file: File): List<Byte> {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(262144)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().toList()
    }

    private class Probe(private val index: StorageCacheIndex, private val sampleMain: Boolean) {
        val phase = AtomicReference("preparing")
        private val active = AtomicBoolean(false)
        private val waits = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val main = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val posted = AtomicBoolean(false)
        private val pendingSince = AtomicLong()
        private val stacks = mutableListOf<JSONObject>()
        private val handler = Handler(Looper.getMainLooper())
        private val lock = StorageCacheIndex::class.java.getDeclaredField("lock").apply { isAccessible = true }
            .get(index) as ReentrantLock
        private lateinit var observer: Thread
        private lateinit var pulse: Thread
        private var epoch = 0L
        fun start() {
            check(active.compareAndSet(false, true))
            epoch = System.nanoTime()
            observer = Thread({
                while (active.get()) {
                    val current = phase.get()
                    val contended = lock.isLocked
                    val cpu = Debug.threadCpuTimeNanos()
                    val started = System.nanoTime()
                    index.access { }
                    val elapsed = System.nanoTime() - started
                    waits += JSONObject().put("phase", current).put("ms", elapsed / 1e6)
                        .put("cpu_ms", (Debug.threadCpuTimeNanos() - cpu) / 1e6).put("locked_before", contended)
                        .put("start_ms", (started - epoch) / 1e6)
                    Thread.sleep(5)
                }
            }, "owned-cache-observer").apply { start() }
            pulse = Thread({
                var lastSample = 0L
                while (active.get()) {
                    if (posted.compareAndSet(false, true)) {
                        val started = System.nanoTime()
                        pendingSince.set(started)
                        val current = phase.get()
                        handler.post {
                            if (active.get()) {
                                main +=
                                    JSONObject().put("phase", current).put("ms", (System.nanoTime() - started) / 1e6)
                                        .put("start_ms", (started - epoch) / 1e6)
                            }
                            posted.set(false)
                        }
                    }
                    val now = System.nanoTime()
                    val pending = pendingSince.get()
                    if (sampleMain && posted.get() && pending > 0 && now - pending > 100_000_000 &&
                        now - lastSample > 100_000_000 && stacks.size < 160
                    ) {
                        lastSample = now
                        val frames = Looper.getMainLooper().thread.stackTrace
                        stacks += JSONObject().put("phase", phase.get()).put("start_ms", (now - epoch) / 1e6)
                            .put("pending_ms", (now - pending) / 1e6)
                            .put("frames", JSONArray(frames.take(40).map { it.toString() }))
                    }
                    Thread.sleep(16)
                }
            }, "owned-main-probe").apply { start() }
        }
        fun stop() {
            if (!active.getAndSet(false)) return
            observer.join(10000)
            pulse.join(10000)
            check(!observer.isAlive && !pulse.isAlive)
        }
        fun report(): JSONObject {
            check(!active.get() && waits.isNotEmpty() && main.isNotEmpty())
            fun distribution(values: List<Double>): JSONObject {
                val sorted = values.sorted()
                return JSONObject().put("count", sorted.size).put("p50_ms", sorted[sorted.size / 2])
                    .put("p95_ms", sorted[((sorted.size - 1) * 0.95).toInt()])
                    .put("max_ms", sorted.last())
            }
            val mainRows = synchronized(main) { main.toList() }
            val phases = JSONObject()
            mainRows.groupBy { it.getString("phase").substringBefore('-') }.forEach { (phase, rows) ->
                phases.put(phase, distribution(rows.map { it.getDouble("ms") }))
            }
            return JSONObject().put("access", distribution(waits.map { it.getDouble("ms") }))
                .put("main_dispatch", distribution(mainRows.map { it.getDouble("ms") }))
                .put("main_by_phase", phases)
                .put("main_stacks", JSONArray(stacks))
                .put("top_main", JSONArray(mainRows.sortedByDescending { it.getDouble("ms") }.take(10)))
                .put("locked_before_count", waits.count { it.getBoolean("locked_before") })
                .put("top_access", JSONArray(waits.sortedByDescending { it.getDouble("ms") }.take(10)))
        }
    }
}
