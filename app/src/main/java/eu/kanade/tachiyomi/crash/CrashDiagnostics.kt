package eu.kanade.tachiyomi.crash

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import koharia.telemetry.TelemetryConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

/** Persists the small amount of diagnostic data that must survive a process crash. */
object CrashDiagnostics {

    private const val LAST_CRASH_FILE = "koharia_last_crash.txt"
    private const val LAST_ANR_FILE = "koharia_last_anr.txt"
    private const val EVENTS_FILE = "koharia_diagnostics.log"
    private const val MAX_REPORT_CHARS = 256 * 1024
    private const val MAX_EVENTS_CHARS = 128 * 1024

    private val fileLock = Any()
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Koharia-diagnostics-writer").apply {
            isDaemon = true
        }
    }

    fun recordUncaughtException(
        context: Context,
        thread: Thread,
        exception: Throwable,
    ) {
        val report = buildString {
            appendLine(header("uncaught exception"))
            appendLine("Thread: ${thread.name}")
            appendLine(exception.stackTraceToString())
        }.take(MAX_REPORT_CHARS)

        writeReport(context.applicationContext, LAST_CRASH_FILE, report)
        appendEvent(context.applicationContext, "uncaught exception", report)
    }

    fun recordMainThreadStall(
        context: Context,
        durationMs: Long,
        stackTrace: Array<StackTraceElement>,
    ) {
        val exception = MainThreadStallException(durationMs).apply {
            this.stackTrace = stackTrace
        }
        val report = buildString {
            appendLine(header("main thread stall"))
            appendLine("Duration: ${durationMs}ms")
            appendLine(exception.stackTraceToString())
        }.take(MAX_REPORT_CHARS)

        writeReport(context.applicationContext, LAST_ANR_FILE, report)
        recordNonFatal(context, "main.thread.stall", exception)
    }

    fun recordNonFatal(context: Context, stage: String, throwable: Throwable) {
        val applicationContext = context.applicationContext
        try {
            logcat(LogPriority.WARN, throwable) { "Non-fatal error at $stage" }
        } catch (_: Throwable) {
            // Diagnostics must never become the source of a second crash.
        }

        try {
            TelemetryConfig.recordException(throwable, "stage=$stage")
        } catch (_: Throwable) {
            // Firebase is optional and must not affect the reader lifecycle.
        }

        appendEventAsync(applicationContext, stage, throwable.stackTraceToString())
    }

    fun recordSlowOperation(context: Context, stage: String, durationMs: Long) {
        if (durationMs < SLOW_OPERATION_THRESHOLD_MS) return
        val message = "stage=$stage durationMs=$durationMs"
        try {
            logcat(LogPriority.WARN) { "Slow operation: $message" }
        } catch (_: Throwable) {
            // Ignore failures while handling diagnostics.
        }
        appendEventAsync(context.applicationContext, "slow operation", message)
    }

    fun readPersistedReports(context: Context): String {
        val directory = context.applicationContext.noBackupFilesDir
        return listOf(LAST_CRASH_FILE, LAST_ANR_FILE, EVENTS_FILE)
            .mapNotNull { name ->
                val file = File(directory, name)
                val content = readFile(file, MAX_REPORT_CHARS) ?: return@mapNotNull null
                "===== $name =====\n$content"
            }
            .joinToString(separator = "\n\n")
    }

    private fun writeReport(context: Context, name: String, content: String) {
        synchronized(fileLock) {
            try {
                val directory = context.noBackupFilesDir
                if (!directory.exists() && !directory.mkdirs()) return

                val destination = File(directory, name)
                val temporary = File(directory, "$name.tmp")
                FileOutputStream(temporary).use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
                if (!temporary.renameTo(destination)) {
                    destination.delete()
                    if (!temporary.renameTo(destination)) {
                        temporary.delete()
                    }
                }
            } catch (error: Throwable) {
                try {
                    logcat(LogPriority.WARN, error) { "Unable to persist diagnostic report $name" }
                } catch (_: Throwable) {
                    // Ignore failures while handling a crash.
                }
            }
        }
    }

    private fun appendEvent(context: Context, stage: String, content: String) {
        synchronized(fileLock) {
            try {
                val directory = context.noBackupFilesDir
                if (!directory.exists() && !directory.mkdirs()) return

                val file = File(directory, EVENTS_FILE)
                val previous = readFile(file, MAX_EVENTS_CHARS).orEmpty()
                val event = buildString {
                    appendLine("${OffsetDateTime.now(ZoneId.systemDefault())} [$stage]")
                    appendLine(content)
                    appendLine()
                }
                val combined = (previous + event).takeLast(MAX_EVENTS_CHARS)
                FileOutputStream(file).use { output ->
                    output.write(combined.toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
            } catch (error: Throwable) {
                try {
                    logcat(LogPriority.WARN, error) { "Unable to append diagnostic event" }
                } catch (_: Throwable) {
                    // Ignore failures while handling diagnostics.
                }
            }
        }
    }

    private fun appendEventAsync(context: Context, stage: String, content: String) {
        try {
            eventExecutor.execute { appendEvent(context, stage, content) }
        } catch (_: Throwable) {
            // The event is best effort; never block or crash the lifecycle for diagnostics.
        }
    }

    private fun readFile(file: File, maxChars: Int): String? {
        if (!file.isFile) return null
        return try {
            file.inputStream().bufferedReader().use { reader ->
                reader.readText().takeLast(maxChars)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun header(type: String): String {
        return "Koharia $type\n" +
            "App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Time: ${OffsetDateTime.now(ZoneId.systemDefault())}"
    }

    private class MainThreadStallException(durationMs: Long) :
        RuntimeException("Main thread stalled for ${durationMs}ms")

    private const val SLOW_OPERATION_THRESHOLD_MS = 500L
}
