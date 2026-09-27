package koharia.source.local

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.LibraryConnectionProfile
import koharia.core.archive.ArchiveReader
import koharia.core.archive.archiveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.imageEntries
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Opt-in diagnosis: supply an archive in a dedicated fixture external-files directory. */
@RunWith(AndroidJUnit4::class)
class LargeLocalArchiveDeviceTest {
    @Test
    fun suppliedCompatibilityArchivesMatchExtractedEntryHashes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val path = InstrumentationRegistry.getArguments().getString("archiveFixtures")
        assumeTrue("Supply archiveFixtures for format compatibility tests", path != null)
        val directory = File(checkNotNull(path)).canonicalFile
        check(directory.toPath().startsWith(checkNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        val manifest = JSONArray(File(directory, "manifest.json").readText())
        for (index in 0 until manifest.length()) {
            val expected = manifest.getJSONObject(index)
            val file = File(directory, expected.getString("file")).canonicalFile
            check(file.parentFile == directory)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            checkNotNull(UniFile.fromUri(context, uri)).archiveReader(context).use { reader ->
                val entries = expected.getJSONObject("entries")
                assertEquals(entries.length(), reader.useEntries { sequence -> sequence.count { it.isFile } })
                for (name in entries.keys()) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    checkNotNull(reader.getInputStream(name)).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    assertEquals(
                        "${file.name}: $name",
                        entries.getString(name),
                        digest.digest().joinToString("") {
                            "%02x".format(it)
                        },
                    )
                }
            }
        }
    }

    @Test
    fun suppliedArchiveEnumerationAndDecoderDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("largeArchivePath")
        assumeTrue("Supply largeArchivePath to run this diagnostic", path != null)
        val archive = File(checkNotNull(path)).canonicalFile
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        check(archive.toPath().startsWith(external.toPath()))
        val root = checkNotNull(archive.parentFile?.parentFile)
        check(root.name == "scan-root")
        val report = File(root.parentFile, "native-diagnostic.txt")
        fun record(message: String) = report.appendText("$message\n")
        report.writeText("bytes=${archive.length()} is64Bit=${Process.is64Bit()}\n")
        val sampleNames = arguments.getString("inspectEntries").orEmpty().split(',').filter { it.isNotBlank() }
        var start = SystemClock.elapsedRealtime()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", archive)
        checkNotNull(UniFile.fromUri(context, uri)).archiveReader(context).use { reader ->
            val count = reader.imageEntries().size
            record("contentUriEnumerationMs=${SystemClock.elapsedRealtime() - start} images=$count")
        }
        start = SystemClock.elapsedRealtime()
        ZipFile(archive).use { zip ->
            val count = zip.entries().asSequence().count { !it.isDirectory && ImageUtil.isImage(it.name) }
            record("javaZipEnumerationMs=${SystemClock.elapsedRealtime() - start} images=$count")
            for (name in sampleNames) {
                val entry = checkNotNull(zip.getEntry(name))
                val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                record("javaZip entry=$name bytes=${entry.size} decoded=${bitmap != null}")
                bitmap?.recycle()
                val codecResult = runCatching {
                    val decoder = zip.getInputStream(entry).use { ImageDecoder.newInstance(it) }
                    if (decoder == null) {
                        "decoder=null"
                    } else {
                        try {
                            val decoded = decoder.decode(sampleSize = 1)
                            val result = "decoded=${decoded != null} width=${decoder.width} height=${decoder.height}"
                            decoded?.recycle()
                            result
                        } finally {
                            decoder.recycle()
                        }
                    }
                }.fold({ it }, { "error=${it.javaClass.simpleName}: ${it.message}" })
                record("readerCodec entry=$name $codecResult")
            }
        }
        try {
            start = SystemClock.elapsedRealtime()
            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    val count = reader.useEntries { entries ->
                        entries.count { it.isFile && ImageUtil.isImage(it.name) }
                    }
                    record("nativeExtensionEnumerationMs=${SystemClock.elapsedRealtime() - start} images=$count")
                    for (name in sampleNames) {
                        start = SystemClock.elapsedRealtime()
                        val bitmap = checkNotNull(reader.getInputStream(name)).use { BitmapFactory.decodeStream(it) }
                        record(
                            "native entry=$name decodeMs=${SystemClock.elapsedRealtime() - start} decoded=${bitmap != null}",
                        )
                        bitmap?.recycle()
                    }
                }
            }
        } catch (error: Throwable) {
            record(error.stackTraceToString())
            throw error
        }
    }

    @Test
    fun suppliedArchiveScansAndDecodesRepresentativePages() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("largeArchivePath")
        assumeTrue("Supply largeArchivePath to run this diagnostic", path != null)
        val archive = File(checkNotNull(path)).canonicalFile
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        check(archive.toPath().startsWith(external.toPath()))
        check(archive.isFile)
        val root = checkNotNull(archive.parentFile?.parentFile)
        check(root.name == "scan-root")
        val report = File(root.parentFile, "archive-diagnostic.txt")
        val sourceId = 9_710_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val sync: SyncChaptersWithSource = Injekt.get()
        fun record(message: String) = report.appendText("$message\n")
        report.writeText("bytes=${archive.length()} is64Bit=${Process.is64Bit()}\n")
        try {
            val base = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            preferences.setConfig(
                base.copy(
                    setupCompleted = true,
                    roots = listOf(
                        LocalLibraryRootConfig(
                            id = "archive-diagnostic",
                            treeUri = root.toURI().toString(),
                            contentType = LocalLibraryContentType.COMICS,
                            bookshelfId = base.defaultBookshelfId(LocalLibraryContentType.COMICS),
                        ),
                    ),
                ),
            )
            val source = LocalFolderSource(
                context,
                sourceId,
                "Large archive diagnostic",
                LibraryConnectionProfile(sourceId, LocalFolderConnectionProvider.ID, "Large archive diagnostic"),
            )
            var start = SystemClock.elapsedRealtime()
            source.refreshLibrary().getOrThrow()
            record("scanMs=${SystemClock.elapsedRealtime() - start}")
            val manga = mangas.getMangaBySourceId(sourceId).single()
            sync.await(source.getChapterList(manga.toSManga()), manga, source, manualFetch = false)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            val loader = LocalPageLoader(ReaderChapter(chapter), source, source)
            try {
                record("enumerationStarted")
                start = SystemClock.elapsedRealtime()
                val pages = loader.getPages()
                record("enumerationMs=${SystemClock.elapsedRealtime() - start} pages=${pages.size}")
                assertTrue(pages.isNotEmpty())
                arguments.getString("expectedPages")?.toInt()?.let { assertEquals(it, pages.size) }
                for (index in listOf(0, 1, pages.size / 4, pages.size / 2, pages.lastIndex).distinct()) {
                    start = SystemClock.elapsedRealtime()
                    val bitmap = checkNotNull(pages[index].stream).invoke().use { BitmapFactory.decodeStream(it) }
                    assertNotNull("Cannot decode page $index", bitmap)
                    checkNotNull(bitmap).let {
                        record(
                            "page=$index decodeMs=${SystemClock.elapsedRealtime() - start} width=${it.width} height=${it.height}",
                        )
                        it.recycle()
                    }
                }
                record("PASS")
            } finally {
                loader.recycle()
            }
        } catch (error: Throwable) {
            record(error.stackTraceToString())
            throw error
        } finally {
            mangas.deleteMangaBySourceId(sourceId)
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
        }
    }
}
