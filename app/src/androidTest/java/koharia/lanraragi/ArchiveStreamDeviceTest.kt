package koharia.lanraragi

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.core.archive.ArchiveReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArchiveStreamDeviceTest {
    @Test
    fun partialReadsRespectOffsetLengthAndZeroLengthContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zip = File.createTempFile("lanraragi-stream-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("data.bin"))
                it.write(ByteArray(20) { index -> (index + 1).toByte() })
                it.closeEntry()
            }
            ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    checkNotNull(reader.getInputStream("data.bin")).use { stream ->
                        val buffer = ByteArray(10) { 99 }
                        assertEquals(2, stream.read(buffer, 3, 2))
                        assertArrayEquals(byteArrayOf(99, 99, 99, 1, 2, 99, 99, 99, 99, 99), buffer)
                        assertEquals(0, stream.read(buffer, 0, 0))
                        assertArrayEquals(ByteArray(18) { index -> (index + 3).toByte() }, stream.readBytes())
                        assertEquals(-1, stream.read())
                    }
                }
            }
        } finally {
            zip.delete()
        }
    }
}
