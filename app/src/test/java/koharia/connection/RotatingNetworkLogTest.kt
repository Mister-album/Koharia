package koharia.connection

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RotatingNetworkLogTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `export flushes queued records and retains them across writer restart`() {
        RotatingNetworkLog(directory).use { log ->
            log.append("session-one route-selected target=internal")
            log.append("session-one probe-failed")
            val report = log.read()
            assertTrue("writerFlushed=true dropped=0 writeFailures=0" in report)
            assertTrue(report.indexOf("route-selected") < report.indexOf("probe-failed"))
        }
        RotatingNetworkLog(directory).use { log ->
            log.append("session-two started")
            val report = log.read()
            assertTrue("session-one probe-failed" in report)
            assertTrue("session-two started" in report)
        }
    }

    @Test
    fun `rotation bounds disk use and retains the most recent records`() {
        RotatingNetworkLog(directory, maxBytes = 128).use { log ->
            repeat(80) { log.append("event=$it route=internal") }
            val report = log.read()
            assertTrue("event=79 " in report)
            assertFalse("event=0 " in report)
            assertTrue(directory.listFiles().orEmpty().size <= 2)
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 256)
        }
    }

    @Test
    fun `storage failures are reported without escaping to caller`() {
        val blocked = File(directory, "not-a-directory").apply { writeText("fixture") }
        RotatingNetworkLog(blocked).use { log ->
            log.append("probe-start")
            assertTrue("writeFailures=1" in log.read())
        }
    }
}
