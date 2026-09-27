package koharia.lanraragi

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import koharia.core.archive.ArchiveReadException
import koharia.core.archive.ArchiveReader
import koharia.core.archive.archiveReader
import koharia.core.archive.openArchiveReader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.imageEntries
import tachiyomi.core.common.util.system.readCoverImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArchiveReaderDeviceTest {
    @Test
    fun offsetsBeyondTwoGiBRemainReadableWithoutMappingTheFile() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeOffsets") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val file = File.createTempFile("archive-large-offset-", ".zip", context.cacheDir)
        try {
            val paddingSize = 0x80001000L
            val zeros = ByteArray(1024 * 1024)
            val paddingCrc = CRC32().apply {
                repeat((paddingSize / zeros.size).toInt()) { update(zeros) }
                update(zeros, 0, (paddingSize % zeros.size).toInt())
            }.value
            val payload = byteArrayOf(5, 9, 12)
            val payloadCrc = CRC32().apply { update(payload) }.value
            RandomAccessFile(file, "rw").use { output ->
                fun short(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
                fun int(value: Long) = output.writeInt(Integer.reverseBytes(value.toInt()))
                fun local(name: String, size: Long, crc: Long) {
                    int(0x04034b50)
                    short(20)
                    short(0)
                    short(0)
                    short(0)
                    short(0)
                    int(crc)
                    int(size)
                    int(size)
                    short(name.length)
                    short(0)
                    output.writeBytes(name)
                }
                fun central(name: String, size: Long, crc: Long, offset: Long) {
                    int(0x02014b50)
                    short(20)
                    short(20)
                    short(0)
                    short(0)
                    short(0)
                    short(0)
                    int(crc)
                    int(size)
                    int(size)
                    short(name.length)
                    short(0)
                    short(0)
                    short(0)
                    short(0)
                    int(0)
                    int(offset)
                    output.writeBytes(name)
                }
                local("padding.bin", paddingSize, paddingCrc)
                output.seek(output.filePointer + paddingSize)
                val imageOffset = output.filePointer
                local("last.jpg", payload.size.toLong(), payloadCrc)
                output.write(payload)
                val directoryOffset = output.filePointer
                central("padding.bin", paddingSize, paddingCrc, 0)
                central("last.jpg", payload.size.toLong(), payloadCrc, imageOffset)
                val directorySize = output.filePointer - directoryOffset
                int(0x06054b50)
                short(0)
                short(0)
                short(2)
                short(2)
                int(directorySize)
                int(directoryOffset)
                short(0)
            }
            assertTrue(file.length() > Int.MAX_VALUE)
            ZipFile(file).use { zip ->
                assertArrayEquals(payload, zip.getInputStream(zip.getEntry("last.jpg")).use { it.readBytes() })
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    assertEquals(listOf("last.jpg"), reader.imageEntries().map { it.name })
                    assertArrayEquals(payload, checkNotNull(reader.getInputStream("last.jpg")).use { it.readBytes() })
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun descriptorsSurviveCallerCloseAndConcurrentStreamsHaveIndependentPositions() = withZip(
        (0..12).associate { "$it.bin" to ByteArray(80_000) { byte -> (byte + it).toByte() } },
    ) { file ->
        val reader = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { ArchiveReader(it) }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val tasks = (0..2).map { worker ->
                executor.submit {
                    repeat(8) { iteration ->
                        val index = worker + iteration
                        val bytes = checkNotNull(reader.getInputStream("$index.bin")).use { it.readBytes() }
                        assertArrayEquals(ByteArray(80_000) { (it + index).toByte() }, bytes)
                    }
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            val active = checkNotNull(reader.getInputStream("0.bin"))
            reader.close()
            reader.close()
            assertTrue(runCatching { active.read() }.exceptionOrNull() is IOException)
            assertTrue(runCatching { reader.getInputStream("0.bin") }.exceptionOrNull() is IOException)
            active.close()
        } finally {
            reader.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun imageSelectionProbesCurrentEntryAndPreservesUnknownAndUppercaseImages() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val png = try {
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        withZip(
            linkedMapOf(
                "contentList.json" to "{}".toByteArray(),
                "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
                "image-without-extension" to png,
                "image.unusual" to png,
                "upper.PNG" to png,
                "not-image.bin" to byteArrayOf(1, 2, 3),
                "__MACOSX/._upper.PNG" to png,
            ),
        ) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    assertEquals(
                        listOf("image-without-extension", "image.unusual", "upper.PNG"),
                        reader.imageEntries().map { it.name },
                    )
                    assertArrayEquals(png, checkNotNull(reader.getInputStream("image.unusual")).use { it.readBytes() })
                }
            }
        }
    }

    @Test
    fun corruptCoverFallsBackWithoutRemovingThePage() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val png = try {
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        withZip(mapOf("01.jpg" to byteArrayOf(0, 1), "02.png" to png)) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    assertEquals(2, reader.imageEntries().size)
                    assertArrayEquals(png, reader.readCoverImage())
                    assertEquals(2, reader.imageEntries().size)
                }
            }
        }
    }

    @Test
    fun cancelledEnumerationDoesNotLeakDescriptorsAndReaderRemainsUsable() = withZip(
        mapOf("image.jpg" to byteArrayOf(1)),
    ) { file ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            ArchiveReader(descriptor).use { reader ->
                val before = File("/proc/self/fd").list()!!.size
                repeat(30) {
                    assertTrue(
                        runCatching {
                            reader.imageEntries { throw CancellationException("test") }
                        }.exceptionOrNull() is CancellationException,
                    )
                }
                assertTrue(File("/proc/self/fd").list()!!.size <= before + 2)
                assertEquals(1, reader.imageEntries().size)
            }
        }
    }

    @Test
    fun concurrentFirstSpoolsShareTheNewDirectory() = withZip(mapOf("data.bin" to byteArrayOf(4, 5, 6))) { file ->
        val creators = CyclicBarrier(2)
        val temporary = object : File(file.parentFile, "concurrent-spool") {
            override fun mkdirs(): Boolean {
                // Both callers have observed an absent directory before either creates it.
                creators.await(5, TimeUnit.SECONDS)
                return synchronized(this) { super.mkdirs() }
            }
        }
        val pipes = List(2) { ParcelFileDescriptor.createPipe() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val bytes = file.readBytes()
            pipes.forEach { pipe ->
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            }
            val results = pipes.map { pipe ->
                executor.submit<ByteArray> {
                    pipe[0].use { descriptor ->
                        openArchiveReader(descriptor, temporary).use { reader ->
                            checkNotNull(reader.getInputStream("data.bin")).use { it.readBytes() }
                        }
                    }
                }
            }
            results.forEach { assertArrayEquals(byteArrayOf(4, 5, 6), it.get(10, TimeUnit.SECONDS)) }
            assertTrue(temporary.isDirectory)
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally {
            pipes.forEach { pipe -> pipe.forEach { it.close() } }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            temporary.delete()
        }
    }

    @Test
    fun nonDirectorySpoolDestinationStillFails() = withZip(mapOf("data.bin" to byteArrayOf(1))) { file ->
        val temporary = File(file.parentFile, "blocked-spool").apply { writeText("Not a directory") }
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val error = runCatching { openArchiveReader(pipe[0], temporary).close() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("Not a directory", temporary.readText())
        } finally {
            pipe.forEach { it.close() }
            temporary.delete()
        }
    }

    @Test
    fun nonSeekableInputUsesOwnedTemporaryDescriptorAndRemovesTemporaryFiles() = withZip(
        mapOf("data.bin" to ByteArray(5000) { it.toByte() }),
    ) { file ->
        val pipe = ParcelFileDescriptor.createPipe()
        val executor = Executors.newSingleThreadExecutor()
        val temporary = File(file.parentFile, "spool").apply { mkdirs() }
        try {
            val writer = executor.submit {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(file.readBytes()) }
            }
            val reader = pipe[0].use { openArchiveReader(it, temporary) }
            reader.use {
                assertTrue(temporary.listFiles().orEmpty().isEmpty())
                assertArrayEquals(
                    ByteArray(5000) { it.toByte() },
                    checkNotNull(reader.getInputStream("data.bin")).use { it.readBytes() },
                )
            }
            writer.get(10, TimeUnit.SECONDS)
        } finally {
            pipe.forEach { it.close() }
            executor.shutdownNow()
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
            temporary.delete()
        }
    }

    @Test
    fun waitingForPipeDataCanBeCancelled() = withZip(mapOf("data.bin" to byteArrayOf(1))) { file ->
        val pipe = ParcelFileDescriptor.createPipe()
        val cancelled = AtomicBoolean(false)
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val temporary = File(file.parentFile, "waiting-spool").apply { mkdirs() }
        try {
            val result = executor.submit<Throwable?> {
                var checks = 0
                runCatching {
                    openArchiveReader(pipe[0], temporary) {
                        if (++checks >= 3) waiting.countDown()
                        if (cancelled.get()) throw CancellationException("test")
                    }
                }.exceptionOrNull()
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(result.get(5, TimeUnit.SECONDS) is CancellationException)
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally {
            pipe.forEach { it.close() }
            executor.shutdownNow()
            temporary.delete()
        }
    }

    @Test
    fun cancelledSpoolingLeavesNoTemporaryFile() = withZip(mapOf("data.bin" to byteArrayOf(1))) { file ->
        val pipe = ParcelFileDescriptor.createPipe()
        val temporary = File(file.parentFile, "cancel-spool").apply { mkdirs() }
        var checks = 0
        try {
            val error = runCatching {
                openArchiveReader(pipe[0], temporary) {
                    if (++checks > 1) throw CancellationException("test")
                }
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally {
            pipe.forEach { it.close() }
            temporary.delete()
        }
    }

    @Test
    fun contentUriUsesTheSameReaderAndInvalidArchivesFailExplicitly() = withZip(
        mapOf("data.bin" to byteArrayOf(7)),
    ) { file ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        checkNotNull(UniFile.fromUri(context, uri)).archiveReader(context).use {
            assertArrayEquals(
                byteArrayOf(7),
                checkNotNull(it.getInputStream("data.bin")).use { input ->
                    input.readBytes()
                },
            )
        }
        file.writeText("not an archive")
        assertTrue(
            runCatching {
                checkNotNull(UniFile.fromUri(context, uri)).archiveReader(context).use {
                    it.useEntries { entries -> entries.count() }
                }
            }.exceptionOrNull() is ArchiveReadException,
        )
    }

    @Test
    fun seekableInputDoesNotCreateATemporaryCopy() = withZip(mapOf("data.bin" to byteArrayOf(1))) { file ->
        val temporary = File(file.parentFile, "unused-spool")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            openArchiveReader(descriptor, temporary).use {
                assertEquals(1, it.useEntries { entries -> entries.count() })
            }
        }
        assertFalse(temporary.exists())
    }

    private fun withZip(entries: Map<String, ByteArray>, block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val directory = File(context.cacheDir, "archive-test-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "test.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            block(file)
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
