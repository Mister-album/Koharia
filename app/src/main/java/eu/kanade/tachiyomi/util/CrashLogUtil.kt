package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.OffsetDateTime
import java.time.ZoneId

class CrashLogUtil(
    private val context: Context,
    private val preferences: BasePreferences = Injekt.get(),
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val file = context.createFileInCacheDir("koharia_crash_logs.txt")

            file.appendText(getDebugInfo() + "\n\n")
            exception?.let { file.appendText("$it\n\n") }

            Runtime.getRuntime().exec("logcat *:E -d -v year -v zone -f ${file.absolutePath}").waitFor()

            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (e: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
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

}
