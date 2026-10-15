package koharia.storage

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class StorageCacheRecoveryDeviceTest {
    @Test fun recoverInterruptedFilesInNewProcess(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("storageCachePhase")
        assumeTrue(phase in setOf("prepare", "recover"))
        val run = checkNotNull(arguments.getString("storageCacheRun"))
        require(run.matches(Regex("[0-9]+")))
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, "cache-recovery-$run").canonicalFile
        check(root.parentFile == parent)
        val bytes = "abcdefgh".encodeToByteArray()
        val entry = StorageEntry("book", false, bytes.size.toLong(), version = "fixture-version")
        val backend = object : LibraryStorageBackend {
            override val capabilities = StorageCapabilities(false, true, false, false)
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int) =
                bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            override suspend fun stat(path: String) = entry
            override suspend fun list(path: String) = listOf(entry)
            override suspend fun createDirectory(path: String): Unit = error("Unexpected mutation")
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?): Unit =
                error("Unexpected mutation")
            override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Unexpected mutation")
            override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected mutation")
            override fun close() = Unit
        }
        val cache = StorageBlockCache(root, StorageSession(1, "fixture", "fixture") { true }, backend) { 8 }
        val checkpoint = File(root, "owner-process")
        val interrupted = File(root, "interrupted/complete.part")
        if (phase == "prepare") {
            check(!root.exists())
            val complete = cache.prepareComplete(entry)
            check(File(complete.parentFile, "complete.ok").delete())
            check(interrupted.parentFile!!.mkdirs())
            interrupted.writeBytes(byteArrayOf(1, 2))
            checkpoint.writeText(Process.myPid().toString())
            return@runBlocking
        }
        try {
            assertNotEquals(checkpoint.readText().toInt(), Process.myPid())
            assertTrue(interrupted.isFile)
            assertArrayEquals(bytes, cache.read(entry, 0, bytes.size))
            assertFalse(interrupted.exists())
            assertTrue(root.walkTopDown().none { it.name == "complete.bin" })
            StorageCacheIndex.get(root).access {
                assertEquals(8L, measuredBytes())
                assertEquals(0L, reservedBytes())
                assertEquals(1, scans)
            }
            assertTrue(checkpoint.isFile)
        } finally {
            check(root.parentFile == parent)
            assertTrue(root.deleteRecursively())
        }
    }
}
