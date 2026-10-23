package koharia.connection

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.time.Instant
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Dev-only local diagnostics. Callers supply fixed labels, numbers and exception types, never payloads. */
object NetworkDiagnostics {
    @Volatile private var writer: RotatingNetworkLog? = null
    private val calls = WeakHashMap<Call, Long>()
    private val sequence = AtomicLong()
    private val session = java.util.UUID.randomUUID().toString().take(8)

    @Synchronized
    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG || writer != null) return
        writer = runCatching {
            val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                context.getSystemService(ActivityManager::class.java).runningAppProcesses
                    ?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
            // The crash activity has its own process; only the main process rotates these files.
            if (processName != context.packageName) return
            RotatingNetworkLog(directory(context))
        }.getOrNull() ?: return
        record(
            "session-start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
                "commit=${BuildConfig.COMMIT_SHA} readOnly=${BuildConfig.DEV_READ_ONLY_VALIDATION} " +
                "diagnostics=2 build=${BuildConfig.BUILD_TIME}",
        )
    }

    fun record(message: String) {
        if (!BuildConfig.DEBUG) return
        val destination = writer ?: return
        runCatching {
            destination.append("${Instant.now()} session=$session $message")
            logcat { "NetworkDiagnostics: $message" }
        }
    }

    fun read(context: Context): String {
        if (!BuildConfig.DEBUG) return ""
        return writer?.read() ?: RotatingNetworkLog(directory(context)).use { it.read() }
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "network-diagnostics")

    fun callId(call: Call): Long = synchronized(calls) { calls.getOrPut(call) { sequence.incrementAndGet() } }

    /** Do not include messages: IO errors commonly contain hostnames, paths or response bodies. */
    fun errorType(error: Throwable): String = error.javaClass.simpleName

    /** Classify configuration without retaining even a hash of the address. */
    fun addressState(address: String): String {
        if (address.isBlank()) return "empty"
        val trimmed = address.trim()
        val url = trimmed.toHttpUrlOrNull() ?: return if (
            !trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)
        ) {
            "missing-or-unsupported-scheme"
        } else {
            "invalid-url"
        }
        return when {
            url.username.isNotBlank() || url.password.isNotBlank() -> "embedded-credentials"
            url.query != null -> "query-present"
            url.fragment != null -> "fragment-present"
            else -> "valid"
        }
    }

    internal fun endpoint(request: Request): String {
        val path = request.url.pathSegments
        val api = path.indexOfLast { it == "api" }
        val segments = if (api >= 0) path.drop(api + 1) else emptyList()
        return when (segments.firstOrNull()) {
            "info", "search", "categories" -> segments.first()
            "archives" -> when {
                segments.size == 1 -> "archives"
                segments.getOrNull(1) == "untagged" -> "untagged"
                segments.lastOrNull() in setOf("metadata", "files", "thumbnail", "isnew") ->
                    "archive-${segments.last()}"
                segments.getOrNull(2) == "progress" -> "archive-progress"
                else -> "archive-other"
            }
            "tankoubons" -> if (segments.size == 1) "tanks" else "tank-detail"
            else -> "other"
        }
    }
}
