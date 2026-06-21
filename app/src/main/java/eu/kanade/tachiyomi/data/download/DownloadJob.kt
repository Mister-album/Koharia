package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import koharia.source.komga.KomgaSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This worker is used to manage the downloader. The system can decide to stop the worker, in
 * which case the downloader is also stopped. It's also stopped while there's no network available.
 */
class DownloadJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val downloadManager: DownloadManager = Injekt.get()
    private val downloadPreferences: DownloadPreferences = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val requireWifi = downloadPreferences.downloadOnlyOverWifi.get()
        val initialNetworkState = applicationContext.activeNetworkState()
        var networkCheck = checkNetworkState(initialNetworkState, requireWifi)
        val queueSizeBeforeStart = downloadManager.queueState.value.size
        val downloaderStarted = if (networkCheck) downloadManager.downloaderStart() else false
        var active = networkCheck && downloaderStarted

        logcat(LogPriority.INFO) {
            "DownloadJob.doWork(): networkCheck=$networkCheck, " +
                "requireWifi=$requireWifi, " +
                "isConnected=${initialNetworkState.isConnected}, " +
                "isValidated=${initialNetworkState.isValidated}, " +
                "isWifi=${initialNetworkState.isWifi}, " +
                "queueSizeBeforeStart=$queueSizeBeforeStart, " +
                "downloaderStarted=$downloaderStarted, " +
                "managerIsRunning=${downloadManager.isRunning}"
        }

        if (!active) {
            logcat(LogPriority.WARN) {
                "DownloadJob.doWork(): failing early due to start precondition, " +
                    "networkCheck=$networkCheck, downloaderStarted=$downloaderStarted"
            }
            return Result.failure()
        }

        setForegroundSafely()

        coroutineScope {
            combineTransform(
                applicationContext.networkStateFlow(),
                downloadPreferences.downloadOnlyOverWifi.changes(),
                transform = { a, b -> emit(checkNetworkState(a, b)) },
            )
                .onEach {
                    networkCheck = it
                    logcat(LogPriority.DEBUG) {
                        "DownloadJob.doWork(): networkCheck updated to $networkCheck"
                    }
                }
                .launchIn(this)
        }

        // Keep the worker running when needed
        while (active) {
            active = !isStopped && downloadManager.isRunning && networkCheck
        }

        return Result.success()
    }

    private suspend fun checkNetworkState(state: NetworkState, requireWifi: Boolean): Boolean {
        if (!state.isConnected) {
            downloadManager.downloaderStop(applicationContext.getString(R.string.download_notifier_no_network))
            return false
        }

        val noWifi = requireWifi && !state.isWifi
        if (noWifi) {
            downloadManager.downloaderStop(
                applicationContext.getString(R.string.download_notifier_text_only_wifi),
            )
            return false
        }

        if (state.isValidated) {
            return true
        }

        if (hasQueuedKomgaDownload()) {
            val komgaReachable = isQueuedKomgaSourceReachable()
            logcat(LogPriority.INFO) {
                "DownloadJob.checkNetworkState(): allowing unvalidated connected network for Komga queue, probeReachable=$komgaReachable"
            }
            return true
        }

        downloadManager.downloaderStop(applicationContext.getString(R.string.download_notifier_no_network))
        return false
    }

    private fun hasQueuedKomgaDownload(): Boolean {
        return downloadManager.queueState.value.any { it.source is KomgaSource }
    }

    private suspend fun isQueuedKomgaSourceReachable(): Boolean {
        val komgaSource = downloadManager.queueState.value.firstOrNull()?.source as? KomgaSource ?: return false
        if (komgaSource.baseUrl.isBlank()) return false

        return runCatching {
            withIOContext {
                komgaSource.client.newCall(GET("${komgaSource.baseUrl}/api/v1/libraries?size=1", komgaSource.headers))
                    .execute()
                    .use { response ->
                        logcat(LogPriority.INFO) {
                            "DownloadJob.isQueuedKomgaSourceReachable(): probe code=${response.code}"
                        }
                        response.isSuccessful
                    }
            }
        }.getOrElse { error ->
            logcat(LogPriority.WARN, error) {
                "DownloadJob.isQueuedKomgaSourceReachable(): probe failed"
            }
            false
        }
    }

    companion object {
        private const val TAG = "Downloader"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
