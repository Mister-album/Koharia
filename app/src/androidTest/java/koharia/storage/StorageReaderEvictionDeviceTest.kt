package koharia.storage

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.core.archive.ArchiveReader
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class StorageReaderEvictionDeviceTest {
    @Test fun webdavCompleteCacheWaitsForReadersBeforeEviction() = exercise(LibraryStorageMode.WEBDAV)

    @Test fun smbCompleteCacheWaitsForReadersBeforeEviction() = exercise(LibraryStorageMode.SMB)

    private fun exercise(mode: LibraryStorageMode): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = Long.MAX_VALUE - System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, "storage-reader-eviction-$id").canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("eviction-$id-$name".also { preferenceNames += it }, mode)
        }
        try {
            val config = NetworkStorageConfiguration(
                mode = mode,
                address = if (mode == LibraryStorageMode.WEBDAV) "http://127.0.0.1:1/" else "smb://127.0.0.1/share/",
                rootIdentity = "eviction-$id",
                needsValidation = true,
            )
            NetworkStoragePreferences(id).save(config, "fixture", "fixture")
            val runtime = NetworkStorageRuntime.get(isolated, id)
            val files = linkedMapOf(
                "book.cbz" to zip(mapOf("001.txt" to "Archive survives pressure".encodeToByteArray())),
                "book.pdf" to pdf(),
                "book.epub" to epub(),
            )
            val entries = files.map { (name, bytes) -> StorageEntry(name, false, bytes.size.toLong(), version = "one") }
            val budget = files.values.sumOf { it.size }.toLong()
            files["pressure.bin"] = ByteArray(budget.toInt()) { 7 }
            val pressure = StorageEntry("pressure.bin", false, budget, version = "one")
            val backend = object : LibraryStorageBackend {
                override val capabilities = StorageCapabilities(false, true, false, false)
                override suspend fun stat(path: String) = (entries + pressure).single { it.path == path }
                override suspend fun list(path: String) = entries
                override suspend fun read(entry: StorageEntry, offset: Long, length: Int) =
                    files.getValue(entry.path).copyOfRange(offset.toInt(), offset.toInt() + length)
                override suspend fun createDirectory(path: String): Unit = error("Unexpected mutation")
                override suspend fun write(
                    path: String,
                    data: InputStream,
                    length: Long,
                    expectedVersion: String?,
                ): Unit =
                    error("Unexpected mutation")
                override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Unexpected mutation")
                override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected mutation")
                override fun close() = Unit
            }
            runtime.records.put("directories", "", StorageDirectorySnapshot(StorageEntry("", true), entries, 1))
            val cacheRoot = File(isolated.filesDir, "library-storage-cache")
            val cache = StorageBlockCache(cacheRoot, runtime.session, backend, runtime.identities::identity) { budget }
            val complete = entries.map { cache.prepareComplete(it) }
            files["idle.bin"] = byteArrayOf(1, 2, 3, 4)
            val idle = StorageBlockCache(cacheRoot, runtime.session, backend, runtime.identities::identity) {
                Long.MAX_VALUE
            }.prepareComplete(StorageEntry("idle.bin", false, 4, version = "one"))
            val manual = File(root, "downloads/manual.cbz").apply {
                check(parentFile!!.mkdirs())
                writeBytes(files.getValue("book.cbz"))
            }
            suspend fun assertProtected() {
                assertEquals(if (idle.exists()) 1 else 0, NetworkStorageRuntime.clearUnusedContentCache(isolated))
                assertFalse(idle.exists())
                val error = runCatching { cache.prepareComplete(pressure) }.exceptionOrNull()
                assertTrue(error is StorageFailure)
                assertEquals(StorageFailure.Reason.SPACE, (error as StorageFailure).reason)
                assertTrue(complete.all { it.isFile })
                assertArrayEquals(files.getValue("book.cbz"), manual.readBytes())
            }
            val resolver = context.contentResolver
            val http = DefaultHttpClient()
            val retriever = AssetRetriever(resolver, http)
            val opener = PublicationOpener(
                DefaultPublicationParser(context.applicationContext as android.app.Application, http, retriever, null),
            )
            resolver.openFileDescriptor(runtime.uri("book.cbz"), "r")!!.use { first ->
                ArchiveReader(first).use { archive ->
                    resolver.openFileDescriptor(runtime.uri("book.cbz"), "r")!!.use { second ->
                        assertTrue(Os.readlink("/proc/self/fd/${second.fd}").startsWith("/mnt/appfuse/"))
                        resolver.openFileDescriptor(runtime.uri("book.pdf"), "r")!!.use { pdf ->
                            PdfRenderer(pdf).use { renderer ->
                                val asset = retriever.retrieve(
                                    runtime.uri("book.epub").toAbsoluteUrl()!!,
                                    MediaType.EPUB,
                                )
                                    .getOrElse { error(it.message) }
                                val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
                                    asset.close()
                                    error(it.message)
                                }
                                try {
                                    assertProtected()
                                    assertEquals(
                                        "Archive survives pressure",
                                        archive.getInputStream("001.txt")!!.use { it.readBytes().decodeToString() },
                                    )
                                    archive.close()
                                    first.close()
                                    assertProtected()
                                    val tail = ByteArray(22)
                                    val archiveBytes = files.getValue("book.cbz")
                                    assertEquals(
                                        22,
                                        Os.pread(
                                            second.fileDescriptor,
                                            tail,
                                            0,
                                            22,
                                            archiveBytes.size - 22L,
                                        ),
                                    )
                                    assertArrayEquals(archiveBytes.takeLast(22).toByteArray(), tail)
                                    renderer.openPage(0).use { page ->
                                        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                                        try {
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            assertEquals(Color.BLUE, bitmap.getPixel(32, 32))
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                    val resource = checkNotNull(publication.get(publication.readingOrder.single()))
                                    try {
                                        assertTrue(
                                            resource.read().getOrElse { error(it.message) }.decodeToString()
                                                .contains("Readium survives pressure"),
                                        )
                                    } finally {
                                        resource.close()
                                    }
                                } finally {
                                    publication.close()
                                }
                            }
                        }
                    }
                }
            }
            // Proxy onRelease callbacks run on the provider's handler after the FD is closed.
            val replacement = withTimeout(5000) {
                while (true) {
                    val result = runCatching { cache.prepareComplete(pressure) }
                    if (result.isSuccess) return@withTimeout result.getOrThrow()
                    val error = result.exceptionOrNull()
                    if (error !is StorageFailure || error.reason != StorageFailure.Reason.SPACE) throw error!!
                    delay(25)
                }
                error("Unreachable")
            }
            assertArrayEquals(files.getValue("pressure.bin"), replacement.readBytes())
            assertTrue(complete.none { it.exists() })
            assertArrayEquals(files.getValue("book.cbz"), manual.readBytes())
            assertFalse(cacheRoot.walkTopDown().any { it.name.endsWith(".part") })
            StorageCacheIndex.get(cacheRoot).access { assertEquals(0L, reservedBytes()) }
            assertTrue(NetworkStorageRuntime.cachedContentSize(isolated) >= pressure.size)
            assertEquals(1, NetworkStorageRuntime.clearUnusedContentCache(isolated))
            assertEquals(0L, NetworkStorageRuntime.cachedContentSize(isolated))
            entries.forEach { assertEquals(it, runtime.snapshot.cachedEntry(it.path)) }
            runtime.session.checkActive()
            assertArrayEquals(files.getValue("book.cbz"), manual.readBytes())
        } finally {
            NetworkStorageRuntime.invalidate(id)
            Injekt.get<LibraryStorageRepository>().removeConnection(id)
            check(preferences.edit().clear().commit())
            preferenceNames.forEach { check(context.deleteSharedPreferences(it)) }
            check(root.canonicalFile.parentFile == parent)
            check(root.deleteRecursively())
        }
    }

    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
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
        output.toByteArray()
    }

    private fun pdf() = ByteArrayOutputStream().use { output ->
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(64, 64, 1).create())
            page.canvas.drawColor(Color.BLUE)
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
        output.toByteArray()
    }

    private fun epub() = zip(
        mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent(),
            "book.opf" to """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">eviction</dc:identifier>
            <dc:title>Eviction fixture</dc:title><dc:language>en</dc:language></metadata>
            <manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="page"/></spine></package>
            """.trimIndent(),
            "page.xhtml" to """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head>
            <body><p>Readium survives pressure</p></body></html>
            """.trimIndent(),
        ).mapValues { it.value.encodeToByteArray() },
    )
}
