package tachiyomi.macrobenchmark

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

internal object BenchmarkTarget {
    const val PACKAGE = "app.koharia.dev.devicefixture"

    fun verify(context: Context) {
        // Older Benchmark releases may reset compilation by uninstalling on pre-34 devices.
        check(Build.VERSION.SDK_INT >= 34) { "Android 14+ is required for data-preserving ART profile resets" }
        val app = context.packageManager.getApplicationInfo(PACKAGE, 0)
        check(app.packageName == PACKAGE && app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Install the non-debuggable benchmark fixture APK first"
        }
        check(app.isProfileableByShell) { "The fixture APK must be profileable" }
    }
}

class KohariaBenchmarkRunner : AndroidJUnitRunner() {
    override fun onStart() {
        val error = runCatching { BenchmarkTarget.verify(context) }.exceptionOrNull()
        if (error != null) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("shortMsg", error.message) })
            return
        }
        super.onStart()
    }
}
