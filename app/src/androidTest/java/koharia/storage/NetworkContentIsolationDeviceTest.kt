package koharia.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.storage.LibraryStorageRepository
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.NetworkStorageDraft
import koharia.source.local.prepareInitialLocalDirectories
import koharia.source.local.saveNetworkLibraryDraft
import koharia.source.local.withInitialBookshelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetworkContentIsolationDeviceTest {
    @Test
    fun samePathCoversAndPagesRemainOwnedAcrossRoots() = exercise(native = false)

    @Test
    fun nativeSamePathCoversAndPagesRemainOwnedAcrossRoots() = exercise(native = true)

    private fun exercise(native: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val args = InstrumentationRegistry.getArguments()
        val base = if (native) "" else checkNotNull(args.getString("contentAddress")).trimEnd('/')
        require(native || base.startsWith("http://127.0.0.1:") || base.startsWith("smb://127.0.0.1:"))
        val mode = if (native) {
            LibraryStorageMode.LOCAL
        } else {
            LibraryStorageMode.valueOf(
                checkNotNull(args.getString("contentMode")),
            )
        }
        require(native || mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val mangas = Injekt.get<MangaRepository>()
        val storage = Injekt.get<LibraryStorageRepository>()
        val covers = Injekt.get<CoverCache>()
        val inventory = profiles.profiles()
        val hadActive = preferences.activeConnectionId.isSet()
        val previousActive = preferences.activeConnectionId.get()
        val owned = mutableListOf<Long>()
        val books = mutableListOf<Manga>()
        val chapters = mutableListOf<Chapter>()
        val owners = mutableListOf<LocalFolderSource>()
        val colors = listOf(Color.rgb(230, 70, 60), Color.rgb(40, 170, 90))
        val root = if (native) {
            File(context.cacheDir, "native-content-${UUID.randomUUID()}").canonicalFile.also {
                check(it.parentFile == context.cacheDir.canonicalFile && !it.exists() && it.mkdirs())
            }
        } else {
            null
        }
        val originalFiles = mutableMapOf<File, ByteArray>()

        suspend fun cover(book: Manga): Int {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(book).memoryCachePolicy(CachePolicy.DISABLED)
                    .allowHardware(false).size(64, 96).build(),
            )
            check(result is SuccessResult)
            return result.image.asDrawable(context.resources).toBitmap().let { bitmap ->
                bitmap.getPixel(0, 0).also { bitmap.recycle() }
            }
        }
        suspend fun page(index: Int): Int {
            val owner = owners[index]
            val loader = LocalPageLoader(ReaderChapter(chapters[index]), owner, owner)
            try {
                val first = loader.getPages().single()
                return checkNotNull(first.stream).invoke().use { stream ->
                    checkNotNull(BitmapFactory.decodeStream(stream)).let { bitmap ->
                        bitmap.getPixel(0, 0).also { bitmap.recycle() }
                    }
                }
            } finally {
                loader.recycle()
            }
        }
        try {
            preferences.activeConnectionId.set(ConnectionPreferences.LOCAL_CONNECTION_ID)
            for (role in listOf("a", "b")) {
                val profile = profiles.add("local-folder", "Content root $role ${UUID.randomUUID()}")
                owned += profile.id
                val initialLibrary = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
                if (native) {
                    val directory = File(checkNotNull(root), role).apply { check(mkdirs()) }
                    val library =
                        prepareInitialLocalDirectories(
                            context,
                            initialLibrary,
                            directory.toURI().toString(),
                            directory.path,
                            Injekt.get<Json>(),
                        )
                    val series = File(directory, "Comics/Series").apply { check(mkdirs()) }
                    val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
                    val png = ByteArrayOutputStream().use { output ->
                        try {
                            bitmap.eraseColor(colors[if (role == "a") 0 else 1])
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                            output.toByteArray()
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val file = File(series, "book.cbz")
                    ZipOutputStream(file.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("001.png").apply { time = 0L })
                        zip.write(png)
                        zip.closeEntry()
                    }
                    originalFiles[file] = file.readBytes()
                    profiles.commitConfiguration(profile.id) { target ->
                        LocalLibraryPreferences(
                            profile.id,
                            Injekt.get<Json>(),
                            target,
                        ).setConfig(library.copy(setupCompleted = true))
                    }
                } else {
                    val config = NetworkStorageConfiguration(mode, "$base/root-$role/")
                    var savedId = profile.id
                    saveNetworkLibraryDraft(
                        context,
                        profile.id,
                        NetworkStorageDraft(config, "root", "fixture-227"),
                        config,
                        initialLibrary,
                        emptyMap(),
                        true,
                        onSaved = { savedId = it },
                    )
                    check(savedId == profile.id)
                }
                val source = withTimeout(10_000) {
                    while (sources.get(profile.id) !is LocalFolderSource) delay(25)
                    sources.get(profile.id) as LocalFolderSource
                }
                owners += source
                source.refreshLibrary().getOrThrow()
                val book = source.browseIndexedLibrary("").single()
                check(book.source == source.id)
                Injekt.get<SyncChaptersWithSource>().fetchAndSync(book, source, ChapterFetchOrigin.OPEN)
                chapters += Injekt.get<ChapterRepository>().getChapterByMangaId(book.id).single()
                mangas.update(MangaUpdate(book.id, favorite = true))
                books += mangas.getMangaById(book.id)
            }
            val runtimeA = if (native) null else NetworkStorageRuntime.get(context, owned[0])
            val runtimeB = if (native) null else NetworkStorageRuntime.get(context, owned[1])
            check(native || runtimeA!!.account != runtimeB!!.account)
            runtimeA?.records?.put(
                "progress",
                "fixture-content",
                StoragePendingProgress(
                    StorageProgressRecord("fixture-content", "v1", UUID.randomUUID().toString(), 1, 100, 1, 3),
                    pending = true,
                    requiresConfirmation = true,
                ),
                1,
            )
            val pending = runtimeA?.records?.snapshot("progress", "fixture-content")
            check(native || pending != null)
            val chapterRepository = Injekt.get<ChapterRepository>()
            if (native) chapterRepository.update(ChapterUpdate(chapters[0].id, bookmark = true))
            val chapterStates = books.map { chapterRepository.getChapterByMangaId(it.id) }
            val catalogue = owned.map { mangas.getMangaBySourceId(it) }
            for (index in listOf(0, 1, 0, 1)) {
                preferences.activeConnectionId.set(owned[index])
                check(cover(books[index]) == colors[index]) { "Cover belongs to a different root" }
                check(page(index) == colors[index]) { "Page belongs to a different root" }
                check(pending == runtimeA?.records?.snapshot("progress", "fixture-content"))
                check(runtimeB?.records?.snapshot("progress", "fixture-content") == null)
                owned.forEachIndexed { i, id -> check(catalogue[i] == mangas.getMangaBySourceId(id)) }
            }
            books.forEachIndexed { i, book ->
                check(chapterStates[i] == chapterRepository.getChapterByMangaId(book.id))
            }
            originalFiles.forEach { (file, bytes) -> check(file.readBytes().contentEquals(bytes)) }
            covers.deleteFromCache(books[0])
            preferences.activeConnectionId.set(owned[0])
            check(cover(books[1]) == colors[1])
            check(page(1) == colors[1])
        } finally {
            books.forEach { covers.deleteFromCache(it) }
            owned.forEach { id ->
                profiles.remove(id).getOrThrow()
                mangas.deleteMangaBySourceId(id)
                storage.removeConnection(id)
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
            root?.let { check(it.parentFile == context.cacheDir.canonicalFile && it.deleteRecursively()) }
        }
    }
}
