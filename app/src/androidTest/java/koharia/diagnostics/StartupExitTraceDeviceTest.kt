package koharia.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Bundle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads only this isolated package's selected historical ANR; never dumps preferences or heap contents. */
@SdkSuppress(minSdkVersion = 30)
class StartupExitTraceDeviceTest {
    @Test
    fun reportMainThreadAndItsLockOwners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val requestedPid = InstrumentationRegistry.getArguments().getString("tracePid")
        assumeTrue("Historical ANR diagnostics require an explicit fixture tracePid", requestedPid != null)
        val pid = checkNotNull(requestedPid).toInt()
        check(pid > 0)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, pid, 8)
            .filter { it.reason == ApplicationExitInfo.REASON_ANR }
        assertTrue("No ANR exit record for the specified fixture process", exits.isNotEmpty())
        val trace = exits.firstNotNullOfOrNull { exit ->
            exit.traceInputStream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(8 * 1024 * 1024)
                var size = 0
                while (size < buffer.size) {
                    val count = reader.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                check(size < buffer.size) { "Trace exceeds bounded diagnostic size" }
                String(buffer, 0, size)
            }
        }
        assertTrue("Android retained no readable ANR trace", trace != null)
        val processes = checkNotNull(trace).split(Regex("(?m)^Cmd line: ")).drop(1)
            .filter { it.lineSequence().first().trim() == context.packageName }
        val threads = processes.flatMap { process ->
            val starts = Regex("(?m)^\\s*\"[^\\r\\n]+\"[^\\r\\n]*$").findAll(process).toList()
            starts.mapIndexed { index, match ->
                process.substring(match.range.first, starts.getOrNull(index + 1)?.range?.first ?: process.length)
                    .trim()
            }
        }
        val selected = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.addAll(threads.filter { it.startsWith("\"main\"") })
        while (pending.isNotEmpty() && selected.size < 12) {
            val thread = pending.removeFirst()
            if (!selected.add(thread)) continue
            Regex("held by thread (\\d+)").findAll(thread).forEach { match ->
                val owner = match.groupValues[1]
                threads.firstOrNull { Regex("(?m)^\"[^\n]+ tid=$owner(?: |$)").containsMatchIn(it) }
                    ?.let(pending::addLast)
            }
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                val headers = trace.lineSequence().filter { it.startsWith("-----") }.take(12).joinToString("\n")
                val inventory = if (threads.isNotEmpty()) {
                    threads.take(12).joinToString("\n") { it.lineSequence().first() }
                } else {
                    processes.flatMap { it.lineSequence().toList() }
                        .filter { Regex("(?i)(sysTid=|^\\s*\\d+[: ])").containsMatchIn(it) }
                        .take(40).joinToString("\n")
                }
                putString(
                    "stream",
                    "\nFixture ANR exit pid=$pid, process snapshots=${processes.size}\n$headers\n$inventory\n" +
                        selected.joinToString("\n\n") + "\n",
                )
            },
        )
        assertTrue("Android trace has no Java main thread; see bounded thread inventory", selected.isNotEmpty())
    }
}
