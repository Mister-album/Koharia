package koharia.storage

import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.SystemSafFixture
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeSafDescriptorLifecycleDeviceTest {
    @Test
    fun nativeSafSeeksReopensAndReleasesOwnedDescriptorsDuringBoundedObservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        SystemSafFixture.withTree { tree ->
            val name = checkNotNull(tree.name)
            check(name.startsWith("Koharia-SAF-"))
            val file = checkNotNull(tree.createFile("descriptor.bin"))
            val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
            val resolver = context.contentResolver
            checkNotNull(resolver.openOutputStream(file.uri, "wt")).use { it.write(bytes) }
            fun snapshot(): Map<String, Int> {
                val descriptors = checkNotNull(File("/proc/self/fd").listFiles())
                val targets = descriptors.mapNotNull { runCatching { Os.readlink(it.path) }.getOrNull() }
                return mapOf(
                    "total" to descriptors.size,
                    "owned" to targets.count { it.contains(name) },
                    "socket" to targets.count { it.startsWith("socket:") },
                    "pipe" to targets.count { it.startsWith("pipe:") },
                    "anon" to targets.count { it.startsWith("anon_inode:") },
                )
            }
            fun verifyRead() {
                checkNotNull(resolver.openFileDescriptor(file.uri, "r")).use { descriptor ->
                    val fd = descriptor.fileDescriptor
                    val tail = ByteArray(23)
                    assertEquals(23, Os.pread(fd, tail, 0, tail.size, bytes.size - 23L))
                    assertArrayEquals(bytes.copyOfRange(bytes.size - 23, bytes.size), tail)
                    assertEquals(0L, Os.lseek(fd, 0, OsConstants.SEEK_CUR))
                    assertEquals(2048L, Os.lseek(fd, 2048, OsConstants.SEEK_SET))
                    val middle = ByteArray(17)
                    assertEquals(17, Os.read(fd, middle, 0, middle.size))
                    assertArrayEquals(bytes.copyOfRange(2048, 2065), middle)
                    assertEquals(2065L, Os.lseek(fd, 0, OsConstants.SEEK_CUR))
                }
                assertEquals("Closed SAF descriptor must not retain owned file", 0, snapshot().getValue("owned"))
            }
            val canceled = CancellationSignal().apply { cancel() }
            assertTrue(
                runCatching { resolver.openFileDescriptor(file.uri, "r", canceled)?.close() }.exceptionOrNull() is
                    OperationCanceledException,
            )
            checkNotNull(resolver.openFileDescriptor(file.uri, "r")).use { first ->
                checkNotNull(resolver.openFileDescriptor(file.uri, "r")).use { second ->
                    first.close()
                    val head = ByteArray(19)
                    assertEquals(19, Os.pread(second.fileDescriptor, head, 0, head.size, 0))
                    assertArrayEquals(bytes.copyOfRange(0, 19), head)
                }
            }
            repeat(10) { verifyRead() }
            delay(2000)
            val started = SystemClock.elapsedRealtime()
            fun record(iteration: Int) {
                val state = JSONObject(snapshot()).apply {
                    put("iteration", iteration)
                    put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("native_saf_fd", state.toString()) })
            }
            record(0)
            repeat(120) { index ->
                verifyRead()
                delay(500)
                if ((index + 1) % 20 == 0) record(index + 1)
            }
            assertArrayEquals(bytes, checkNotNull(resolver.openInputStream(file.uri)).use { it.readBytes() })
            assertEquals(0, snapshot().getValue("owned"))
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("native_saf_fd_cleanup", "owned-tree-and-grant-released") },
        )
    }
}
