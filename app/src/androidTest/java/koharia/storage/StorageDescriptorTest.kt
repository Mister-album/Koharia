package koharia.storage

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.core.archive.ArchiveReader
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class StorageDescriptorTest {
    @Test fun obsoleteSessionRollsBackDatabaseWrite(): Unit = runBlocking {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val repository = Injekt.get<LibraryStorageRepository>()
        val id = Long.MAX_VALUE - System.nanoTime()
        try {
            var checks = 0
            val result = runCatching {
                repository.put(
                    id,
                    "fixture",
                    "root",
                    "directories",
                    koharia.domain.storage.StorageRecord("", "stale", 1),
                ) {
                    if (++checks == 2) throw kotlinx.coroutines.CancellationException("Obsolete fixture session")
                }
            }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(null, repository.get(id, "fixture", "root", "directories", ""))
        } finally {
            repository.removeConnection(id)
        }
    }

    @Test fun webdavDescriptorReadsArchiveBeforeCompleteDownload() = exercise(LibraryStorageMode.WEBDAV)

    @Test fun smbDescriptorReadsArchiveBeforeCompleteDownload() = exercise(LibraryStorageMode.SMB)

    private fun exercise(mode: LibraryStorageMode): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = Long.MAX_VALUE - System.nanoTime()
        val folder = "android-${UUID.randomUUID()}"
        val baseAddress = if (mode == LibraryStorageMode.WEBDAV) {
            "http://127.0.0.1:18765/"
        } else {
            "smb://127.0.0.1:18445/library/"
        }
        val baseConfig = NetworkStorageConfiguration(mode, baseAddress)
        val parent = NetworkStorageRuntime.backend(baseConfig, baseAddress, "", "")
        parent.createDirectory(folder)
        val address = baseAddress + folder + "/"
        val draft = baseConfig.copy(address = address)
        val backend = NetworkStorageRuntime.backend(draft, address, "", "")
        try {
            val rootId = try {
                StorageIdentity.ensure(backend)
            } catch (failure: StorageFailure) {
                if (failure.reason == StorageFailure.Reason.UNVERIFIED) {
                    val marker = backend.stat(".koharia/storage-id")
                    val text = backend.read(marker, 0, marker.size.toInt()).decodeToString()
                    throw AssertionError("Fixture identity mismatch: ${text.toCharArray().map { it.code }}", failure)
                }
                throw failure
            }
            NetworkStoragePreferences(id).save(draft.copy(rootIdentity = rootId), "", "")
            val output = ByteArrayOutputStream()
            val first = "first readable page".encodeToByteArray()
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in listOf("001.txt" to first, "002.bin" to ByteArray(8 * 1024 * 1024) { 7 })) {
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val bytes = output.toByteArray()
            backend.write("book.cbz", bytes.inputStream(), bytes.size.toLong())
            val runtime = NetworkStorageRuntime.get(context, id)
            runtime.snapshot.directory("", true)
            val uri = runtime.uri("book.cbz")
            val started = android.os.SystemClock.elapsedRealtime()
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                val tail = ByteArray(22)
                assertEquals(
                    tail.size,
                    Os.pread(descriptor.fileDescriptor, tail, 0, tail.size, bytes.size.toLong() - 22),
                )
                assertArrayEquals(bytes.takeLast(22).toByteArray(), tail)
                ArchiveReader(descriptor).use { archive ->
                    assertArrayEquals(first, archive.getInputStream("001.txt")!!.use { it.readBytes() })
                }
            }
            val cacheRoot = java.io.File(context.filesDir, "library-storage-cache")
            val namespace = storageDigest("$id/${runtime.account}/$rootId")
            val ownCache = java.io.File(cacheRoot, namespace)
            val downloadedBytes = ownCache.walkTopDown().filter {
                it.isFile && it.extension == "block"
            }.sumOf { it.length() }
            assertTrue("Reading must begin before a complete download", downloadedBytes in 1 until bytes.size.toLong())
            assertTrue(ownCache.walkTopDown().none { it.name == "complete.bin" })
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply {
                    putString("storage_protocol", mode.name)
                    putLong("first_archive_entry_ms", android.os.SystemClock.elapsedRealtime() - started)
                    putLong("archive_cached_bytes", downloadedBytes)
                    putLong("archive_total_bytes", bytes.size.toLong())
                },
            )
            val epub = epubFixture()
            backend.write("book.epub", epub.inputStream(), epub.size.toLong())
            val pdf = pdfFixture()
            backend.write("book.pdf", pdf.inputStream(), pdf.size.toLong())
            runtime.snapshot.directory("", true)
            val http = DefaultHttpClient()
            val retriever = AssetRetriever(context.contentResolver, http)
            val asset = retriever.retrieve(checkNotNull(runtime.uri("book.epub").toAbsoluteUrl()), MediaType.EPUB)
                .getOrElse { error(it.message) }
            val opener =
                PublicationOpener(
                    DefaultPublicationParser(
                        context.applicationContext as android.app.Application,
                        http,
                        retriever,
                        null,
                    ),
                )
            val publication = opener.open(asset, allowUserInteraction = false).getOrElse { error(it.message) }
            try {
                val chapter = checkNotNull(publication.get(publication.readingOrder.first())).read()
                    .getOrElse { error(it.message) }
                assertTrue(chapter.decodeToString().contains("first readable chapter"))
            } finally {
                publication.close()
            }
            context.contentResolver.openFileDescriptor(runtime.uri("book.pdf"), "r")!!.use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            60,
                            60,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        try {
                            page.render(
                                bitmap,
                                null,
                                null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            val allBytes = ownCache.walkTopDown().filter { it.isFile && it.extension == "block" }.sumOf { it.length() }
            assertTrue(
                "EPUB and PDF should open progressively",
                allBytes < downloadedBytes + minOf(epub.size, pdf.size),
            )
            val zip64 = zip64Fixture(bytes)
            backend.write("zip64.cbz", zip64.inputStream(), zip64.size.toLong())
            runtime.snapshot.directory("", true)
            context.contentResolver.openFileDescriptor(runtime.uri("zip64.cbz"), "r")!!.use { descriptor ->
                ArchiveReader(descriptor).use { archive ->
                    assertArrayEquals(first, archive.getInputStream("001.txt")!!.use { it.readBytes() })
                }
            }
            val withZip64 = ownCache.walkTopDown().filter { it.isFile && it.extension == "block" }.sumOf { it.length() }
            assertTrue("ZIP64 must open before full download", withZip64 - allBytes in 1 until zip64.size.toLong())
            val local = koharia.source.local.LocalLibraryPreferences(id, Injekt.get())
            local.saveLibraryDraft(
                koharia.source.local.LocalLibraryConfig(
                    roots = listOf(
                        koharia.source.local.LocalLibraryRootConfig(
                            id = "fixture",
                            treeUri = runtime.uri("").toString(),
                            contentType = koharia.source.local.LocalLibraryContentType.COMICS,
                            bookshelfId = "fixture-shelf",
                        ),
                    ),
                    bookshelves = listOf(
                        koharia.source.local.LocalBookshelf(
                            "fixture-shelf",
                            "Fixture",
                            koharia.source.local.LocalLibraryContentType.COMICS,
                            koharia.source.local.LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                        ),
                    ),
                    setupCompleted = true,
                ),
                emptyMap(),
            )
            val source = koharia.source.local.LocalFolderSource(
                context,
                id,
                "Transfer fixture",
                koharia.connection.LibraryConnectionProfile(id, "local-folder", "Transfer fixture"),
            )
            try {
                val scanStarted = android.os.SystemClock.elapsedRealtime()
                source.refreshLibrary().getOrThrow()
                assertTrue(local.getIndex().items.any { it.relativePath == "book.cbz" && !it.missing })
                assertTrue(!source.needsInitialScan())
                assertTrue(!runtime.snapshot.hasPendingScan())
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    android.os.Bundle().apply {
                        putString("storage_scan_protocol", mode.name)
                        putLong("scan_ms", android.os.SystemClock.elapsedRealtime() - scanStarted)
                        putInt("indexed_entries", local.getIndex().items.size)
                    },
                )
                val chapterUrl = koharia.source.local.LocalLibraryLocator.chapterUrl(id, "fixture", "book.cbz")
                val description = source.describeFileTransfer(chapterUrl)
                val prefix = 1024 * 1024
                val resumed = ByteArrayOutputStream()
                resumed.write(bytes, 0, prefix)
                source.transferFile(chapterUrl, description, resumed, prefix.toLong())
                assertArrayEquals(bytes, resumed.toByteArray())
                source.recordLocalPageProgress(chapterUrl, 8, 10, System.currentTimeMillis() - 10000, false)
                source.pushPageProgress(chapterUrl, 8, 10)
                source.recordLocalPageProgress(chapterUrl, 1, 10, System.currentTimeMillis() - 9000, false)
                source.pushPageProgress(chapterUrl, 1, 10)
                val pulled =
                    checkNotNull(source.pullPageProgress(chapterUrl, kotlinx.serialization.json.JsonObject(emptyMap())))
                assertEquals(1, pulled.pageIndex)
            } finally {
                source.close()
            }
            assertTrue(ownCache.canonicalPath.startsWith(cacheRoot.canonicalPath + java.io.File.separator))
            NetworkStorageRuntime.invalidate(id)
            ownCache.deleteRecursively()
        } finally {
            NetworkStorageRuntime.invalidate(id)
            val mangaRepository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
            mangaRepository.getMangaBySourceId(id).forEach { mangaRepository.deleteMangaById(it.id) }
            Injekt.get<LibraryStorageRepository>().removeConnection(id)
            sourcePreferences("source_$id").edit().clear().commit()
            // This test created the entire randomly named subtree.
            suspend fun removeTree(path: String) {
                backend.list(path).forEach { if (it.directory) removeTree(it.path) else backend.delete(it) }
                if (path.isNotEmpty()) backend.delete(backend.stat(path))
            }
            removeTree("")
            backend.close()
            parent.delete(parent.stat(folder))
            parent.close()
        }
    }

    private fun zip64Fixture(classic: ByteArray): ByteArray {
        val endOffset = classic.size - 22
        val end = java.nio.ByteBuffer.wrap(classic.copyOfRange(endOffset, classic.size))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        check(end.getInt(0) == 0x06054b50)
        val count = end.getShort(10).toLong() and 0xffff
        val centralSize = end.getInt(12).toLong() and 0xffffffffL
        val centralOffset = end.getInt(16).toLong() and 0xffffffffL
        val extended = java.nio.ByteBuffer.allocate(76).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06064b50)
            putLong(44)
            putShort(45)
            putShort(45)
            putInt(0)
            putInt(0)
            putLong(count)
            putLong(count)
            putLong(centralSize)
            putLong(centralOffset)
            putInt(0x07064b50)
            putInt(0)
            putLong(endOffset.toLong())
            putInt(1)
        }.array()
        end.putShort(8, -1)
        end.putShort(10, -1)
        end.putInt(12, -1)
        end.putInt(16, -1)
        return classic.copyOfRange(0, endOffset) + extended + end.array()
    }

    private fun epubFixture(): ByteArray {
        val output = ByteArrayOutputStream()
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent().encodeToByteArray(),
            "book.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">fixture</dc:identifier>
                <dc:title>Fixture</dc:title><dc:language>en</dc:language></metadata><manifest>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>
            """.trimIndent().encodeToByteArray(),
            "chapter.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head>
                <body><p>first readable chapter</p></body></html>
            """.trimIndent().encodeToByteArray(),
            "unused.bin" to ByteArray(8 * 1024 * 1024) { 7 },
        )
        ZipOutputStream(output).use { zip ->
            for ((name, data) in files) {
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun pdfFixture(): ByteArray {
        val output = ByteArrayOutputStream()
        fun text(value: String) = output.write(value.encodeToByteArray())
        text("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        fun obj(value: String) {
            offsets += output.size()
            text("${offsets.lastIndex} 0 obj\n$value\nendobj\n")
        }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R /Resources << >> >>")
        obj("<< /Length 0 >>\nstream\n\nendstream")
        offsets += output.size()
        text("5 0 obj\n<< /Length 8388608 >>\nstream\n")
        output.write(ByteArray(8 * 1024 * 1024))
        text("\nendstream\nendobj\n")
        val crossReference = output.size()
        text("xref\n0 6\n0000000000 65535 f \n")
        offsets.drop(1).forEach { text("${it.toString().padStart(10, '0')} 00000 n \n") }
        text("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$crossReference\n%%EOF\n")
        return output.toByteArray()
    }
}
