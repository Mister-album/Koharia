package tachiyomi.presentation.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                UpdatesGridGlanceWidget().updateAll(context)
                UpdatesGridCoverScreenGlanceWidget().updateAll(context)
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error) { "Failed to refresh widgets after an app update" }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
