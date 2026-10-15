package koharia.storage

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.core.archive.ArchiveReader
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class StorageDescriptorLifecycleDeviceTest {
    @Test fun webdavCachedReadersReleaseDescriptors() = exercise(LibraryStorageMode.WEBDAV)

    @Test fun smbCachedReadersReleaseDescriptors() = exercise(LibraryStorageMode.SMB)

    private fun exercise(mode: LibraryStorageMode): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = Long.MAX_VALUE - System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        val configuration = NetworkStorageConfiguration(
            mode = mode,
            address = if (mode == LibraryStorageMode.WEBDAV) "http://127.0.0.1:1/" else "smb://127.0.0.1/share/",
            rootIdentity = "descriptor-lifecycle-$id",
            needsValidation = true,
        )
        NetworkStoragePreferences(id).save(configuration, "fixture", "fixture")
        val runtime = NetworkStorageRuntime.get(context, id)
        val root = File(context.filesDir, "library-storage-cache").canonicalFile
        val owned = File(root, storageDigest("$id/${runtime.account}/${configuration.rootIdentity}")).canonicalFile
        check(owned.parentFile == root && !owned.exists())
        val image = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.RED)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        val files = mapOf(
            "book.cbz" to zip(mapOf("001.png" to image, "unused.bin" to ByteArray(1024 * 1024) { 7 })),
            "book.pdf" to pdf(),
            "book.epub" to epub(),
        )
        val entries = files.map { (name, bytes) -> StorageEntry(name, false, bytes.size.toLong(), version = "fixture") }
        val backend = object : LibraryStorageBackend {
            override val capabilities = StorageCapabilities(false, true, false, false)
            override suspend fun stat(path: String) = entries.single { it.path == path }
            override suspend fun list(path: String) = entries
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int) =
                files.getValue(entry.path).copyOfRange(offset.toInt(), offset.toInt() + length)
            override suspend fun createDirectory(path: String): Unit = error("Unexpected fixture mutation")
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?): Unit =
                error("Unexpected fixture mutation")
            override suspend fun move(
                entry: StorageEntry,
                destination: String,
            ): Unit = error("Unexpected fixture mutation")
            override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected fixture mutation")
            override fun close() = Unit
        }
        try {
            runtime.records.put("directories", "", StorageDirectorySnapshot(StorageEntry("", true), entries, 1))
            // Populate only these new files without evicting any pre-existing fixture cache.
            val seed =
                StorageBlockCache(root, runtime.session, backend, runtime.identities::identity) { Long.MAX_VALUE }
            entries.forEach { seed.prepareComplete(it) }
            val resolver = context.contentResolver
            val http = DefaultHttpClient()
            val retriever = AssetRetriever(resolver, http)
            val opener = PublicationOpener(
                DefaultPublicationParser(context.applicationContext as android.app.Application, http, retriever, null),
            )
            suspend fun readAll() {
                resolver.openFileDescriptor(runtime.uri("book.cbz"), "r")!!.use { descriptor ->
                    assertTrue(Os.readlink("/proc/self/fd/${descriptor.fd}").startsWith("/mnt/appfuse/"))
                    assertEquals(0L, Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET))
                    val header = ByteArray(4)
                    assertEquals(4, Os.read(descriptor.fileDescriptor, header, 0, header.size))
                    assertArrayEquals(files.getValue("book.cbz").copyOfRange(0, 4), header)
                    assertEquals(4L, Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR))
                    assertEquals(0L, Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET))
                    ArchiveReader(descriptor).use { archive ->
                        assertArrayEquals(image, archive.getInputStream("001.png")!!.use { it.readBytes() })
                    }
                }
                resolver.openFileDescriptor(runtime.uri("book.pdf"), "r")!!.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        assertEquals(1, renderer.pageCount)
                        renderer.openPage(0).use { page ->
                            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                assertEquals(Color.BLUE, bitmap.getPixel(32, 32))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
                val asset = retriever.retrieve(checkNotNull(runtime.uri("book.epub").toAbsoluteUrl()), MediaType.EPUB)
                    .getOrElse { error(it.message) }
                val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
                    asset.close()
                    error(it.message)
                }
                try {
                    assertEquals(2, publication.readingOrder.size)
                    publication.readingOrder.forEachIndexed { index, link ->
                        val resource = checkNotNull(publication.get(link))
                        try {
                            val bytes = resource.read().getOrElse { error(it.message) }
                            assertTrue(bytes.decodeToString().contains("Lifecycle chapter $index"))
                        } finally {
                            resource.close()
                        }
                    }
                } finally {
                    publication.close()
                }
            }
            val proxyBaseline = proxyDescriptors()
            repeat(3) {
                readAll()
                awaitProxyCount(proxyBaseline)
            }
            val totalBaseline = checkNotNull(File("/proc/self/fd").list()).size
            val started = SystemClock.elapsedRealtime()
            val observeFd = InstrumentationRegistry.getArguments().getString("descriptorObserveFd") == "true"
            val rounds = if (observeFd) 120 else 12
            fun recordDescriptors(iteration: Int) {
                val descriptors = checkNotNull(File("/proc/self/fd").listFiles())
                val targets = descriptors.mapNotNull { runCatching { Os.readlink(it.path) }.getOrNull() }
                val sample = JSONObject().apply {
                    put("mode", mode.name)
                    put("iteration", iteration)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    put("total", descriptors.size)
                    put("proxy", proxyDescriptors())
                    put("socket", targets.count { it.startsWith("socket:") })
                    put("pipe", targets.count { it.startsWith("pipe:") })
                    put("anon", targets.count { it.startsWith("anon_inode:") })
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("cached_reader_fd", sample.toString()) })
            }
            if (observeFd) recordDescriptors(0)
            repeat(rounds) { index ->
                readAll()
                awaitProxyCount(proxyBaseline)
                if (observeFd) {
                    delay(500)
                    if ((index + 1) % 20 == 0) recordDescriptors(index + 1)
                }
            }
            val canceled = CancellationSignal().apply { cancel() }
            assertTrue(
                runCatching { resolver.openFileDescriptor(runtime.uri("book.cbz"), "r", canceled)?.close() }
                    .exceptionOrNull() is OperationCanceledException,
            )
            resolver.openFileDescriptor(runtime.uri("book.cbz"), "r")!!.use { descriptor ->
                val bytes = ByteArray(4)
                assertEquals(4, Os.pread(descriptor.fileDescriptor, bytes, 0, bytes.size, 0))
                NetworkStorageRuntime.invalidate(id)
                // The kernel may retain already-read pages; request a region not read by this descriptor.
                val unreadOffset = files.getValue("book.cbz").size - 128L
                assertTrue(
                    "An obsolete descriptor must not fetch another region",
                    runCatching { Os.pread(descriptor.fileDescriptor, bytes, 0, bytes.size, unreadOffset) }.isFailure,
                )
            }
            awaitProxyCount(proxyBaseline)
            val reopened = NetworkStorageRuntime.get(context, id)
            resolver.openFileDescriptor(reopened.uri("book.cbz"), "r")!!.use { descriptor ->
                ArchiveReader(descriptor).use { archive ->
                    assertArrayEquals(image, archive.getInputStream("001.png")!!.use { it.readBytes() })
                }
            }
            awaitProxyCount(proxyBaseline)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("cached_storage_mode", mode.name)
                    putInt("reader_open_close_cycles", rounds * 3)
                    putInt("proxy_fd_before", proxyBaseline)
                    putInt("proxy_fd_after", proxyDescriptors())
                    putInt("process_fd_after_warmup", totalBaseline)
                    putInt("process_fd_after_cycles", checkNotNull(File("/proc/self/fd").list()).size)
                    putLong("lifecycle_elapsed_ms", SystemClock.elapsedRealtime() - started)
                },
            )
        } finally {
            NetworkStorageRuntime.invalidate(id)
            Injekt.get<LibraryStorageRepository>().removeConnection(id)
            check(owned.parentFile == root)
            StorageCacheIndex.get(root).access {
                try {
                    check(!owned.exists() || owned.deleteRecursively())
                } finally {
                    invalidate()
                }
            }
            check(preferences.edit().clear().commit())
        }
    }

    private fun proxyDescriptors() = checkNotNull(File("/proc/self/fd").listFiles()).count {
        runCatching { Os.readlink(it.path).startsWith("/mnt/appfuse/") }.getOrDefault(false)
    }

    private suspend fun awaitProxyCount(expected: Int) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (proxyDescriptors() != expected && SystemClock.elapsedRealtime() < deadline) delay(25)
        assertEquals("Reader must release all proxy descriptors", expected, proxyDescriptors())
    }

    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        if (name == "mimetype" || name == "unused.bin") {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        }
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
            page.canvas.drawRect(0f, 0f, 64f, 64f, Paint().apply { color = Color.BLUE })
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
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">lifecycle</dc:identifier>
                <dc:title>Lifecycle</dc:title><dc:language>en</dc:language></metadata><manifest>
                <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="first"/><itemref idref="second"/></spine></package>
            """.trimIndent(),
            "first.xhtml" to chapter(0),
            "second.xhtml" to chapter(1),
        ).mapValues { it.value.encodeToByteArray() },
    )

    private fun chapter(index: Int) = """
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Lifecycle</title></head>
        <body><p>Lifecycle chapter $index</p></body></html>
    """.trimIndent()
}
