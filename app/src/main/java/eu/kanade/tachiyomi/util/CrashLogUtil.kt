package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.crash.CrashDiagnostics
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class CrashLogUtil(
    private val context: Context,
    private val preferences: BasePreferences = Injekt.get(),
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val uri = withContext(Dispatchers.IO) {
                val file = context.createFileInCacheDir("koharia_crash_logs.txt")
                val persistedReports = CrashDiagnostics.readPersistedReports(context)
                val logcat = dumpLogcat()

                file.writeText(
                    buildString {
                        appendLine(getDebugInfo())
                        exception?.let {
                            appendLine()
                            appendLine("Current exception:")
                            appendLine(it.stackTraceToString())
                        }
                        if (persistedReports.isNotBlank()) {
                            appendLine()
                            appendLine(persistedReports)
                        }
                        appendLine()
                        appendLine("===== logcat =====")
                        appendLine(logcat)
                    },
                )
                file.getUriCompat(context)
            }

            withUIContext {
                context.startActivity(uri.toShareIntent(context, "text/plain"))
            }
        } catch (error: Throwable) {
            CrashDiagnostics.recordNonFatal(context, "crash.logs.export", error)
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    private fun dumpLogcat(): String {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "year",
            "-v",
            "zone",
            "-b",
            "main",
            "-b",
            "system",
            "-b",
            "crash",
            "*:E",
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    val remaining = MAX_LOGCAT_CHARS - length
                    if (remaining <= 0) continue

                    if (line.length < remaining) {
                        appendLine(line)
                    } else {
                        append(line, 0, remaining)
                    }
                }
            }
        }

        if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Timed out while reading logcat")
        }
        if (process.exitValue() != 0) {
            throw IOException("logcat failed with exit code ${process.exitValue()}: $output")
        }
        return output
    }

    fun getDebugInfo(): String {
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Installation ID: ${preferences.installationId.get()}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${OffsetDateTime.now(ZoneId.systemDefault())}
        """.trimIndent()
    }

    private companion object {
        const val MAX_LOGCAT_CHARS = 256 * 1024
        const val LOGCAT_TIMEOUT_SECONDS = 5L
    }
}
