package koharia.testing

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import tachiyomi.core.common.util.system.logcat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Launches fixture activities for device tests.
 *
 * Devices such as MIUI refuse activity starts from a backgrounded app, and an instrumentation
 * process has no activity of its own while the runner finishes every activity between tests. A
 * plain [ActivityScenario.launch] therefore waits forever for an activity that is never created,
 * so the fixture app is put in the foreground first and every launch is bounded: a failure reports
 * itself instead of hanging the run.
 */
internal object FixtureActivityLauncher {

    /** Enough for a reader to build its page list and first page on a slow device. */
    private const val LAUNCH_TIMEOUT_MS = 60_000L
    private const val FOREGROUND_TIMEOUT_MS = 45_000L
    private const val FOREGROUND_SETTLE_MS = 1_000L

    fun <A : Activity> launch(activityClass: Class<A>): ActivityScenario<A> {
        ensureForeground()
        return launchBounded { ActivityScenario.launch(activityClass) }
    }

    fun <A : Activity> launch(intent: Intent): ActivityScenario<A> {
        ensureForeground()
        return launchBounded { ActivityScenario.launch(intent) }
    }

    /**
     * Brings the fixture app to the foreground with a shell start, which the instrumentation is
     * allowed to issue even where an app cannot start activities itself.
     */
    fun ensureForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val mainActivity = "$packageName/eu.kanade.tachiyomi.ui.main.MainActivity"
        val deadline = SystemClock.uptimeMillis() + FOREGROUND_TIMEOUT_MS
        var topMostSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            // `am start -W` reports "top-most instance" once the activity already holds the
            // foreground, which is the cheapest signal available: `grep` is not usable through the
            // instrumentation shell and a full `dumpsys window` is far too large to poll.
            val output = shellCommand(instrumentation, "am start -W -n $mainActivity")
            if (output.contains("top-most instance")) {
                if (topMostSince == 0L) topMostSince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - topMostSince >= FOREGROUND_SETTLE_MS) return
            } else {
                topMostSince = 0L
            }
            SystemClock.sleep(250)
        }
        logcat { "FixtureActivityLauncher: $packageName did not confirm the foreground" }
    }

    private fun <A : Activity> launchBounded(launch: () -> ActivityScenario<A>): ActivityScenario<A> {
        val scenario = AtomicReference<ActivityScenario<A>?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread {
            runCatching(launch)
                .onSuccess(scenario::set)
                .onFailure(failure::set)
        }
        thread.start()
        thread.join(LAUNCH_TIMEOUT_MS)
        failure.get()?.let { throw it }
        return scenario.get() ?: error("Activity was not resumed within ${LAUNCH_TIMEOUT_MS}ms")
    }

    private fun shellCommand(instrumentation: Instrumentation, command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }
        }
}
