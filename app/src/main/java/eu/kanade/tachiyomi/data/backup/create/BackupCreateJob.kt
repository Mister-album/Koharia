package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupPasswordStore
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class BackupCreateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)

    override suspend fun doWork(): Result {
        if (uy.kohesive.injekt.Injekt.get<koharia.connection.SharedConfigMigration>().isPending()) return Result.retry()

        val isAutoBackup = inputData.getBoolean(IS_AUTO_BACKUP_KEY, true)

        if (isAutoBackup && BackupRestoreJob.isRunning(context)) return Result.retry()

        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri()
            ?: getAutomaticBackupLocation()
            ?: return Result.failure()

        setForegroundSafely()

        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { BackupOptions.fromBooleanArray(it) }
            ?: BackupOptions()

        val passwordId = inputData.getString(PASSWORD_ID_KEY)
        var password: CharArray? = null
        return try {
            password = if (isAutoBackup) {
                BackupPasswordStore.loadAutomatic(context)
            } else {
                passwordId?.let { BackupPasswordStore.loadManual(context, it) }
            }
            val location = BackupCreator(context, isAutoBackup).backup(uri, options, password)
            if (!isAutoBackup) {
                notifier.showBackupComplete(UniFile.fromUri(context, location.toUri())!!)
            }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            if (!isAutoBackup) notifier.showBackupError(e.message)
            Result.failure()
        } finally {
            password?.fill('\u0000')
            passwordId?.let { BackupPasswordStore.removeManual(context, it) }
            context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_BACKUP_PROGRESS,
            notifier.showBackupProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun getAutomaticBackupLocation(): Uri? {
        val storageManager = Injekt.get<StorageManager>()
        return storageManager.getAutomaticBackupsDirectory()?.uri
    }

    companion object {
        fun isManualJobRunning(context: Context): Boolean {
            return context.workManager.getWorkInfosByTag(TAG_MANUAL).get().any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val backupPreferences = Injekt.get<BackupPreferences>()
            val interval = prefInterval ?: backupPreferences.backupInterval.get()
            if (interval > 0) {
                val constraints = Constraints(
                    requiresBatteryNotLow = true,
                )

                val request = PeriodicWorkRequestBuilder<BackupCreateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .addTag(TAG_AUTO)
                    .setConstraints(constraints)
                    .setInputData(workDataOf(IS_AUTO_BACKUP_KEY to true))
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        suspend fun startNow(context: Context, uri: Uri, options: BackupOptions, password: String? = null): Boolean =
            withContext(Dispatchers.IO + NonCancellable) {
                var passwordId: String? = null
                var removeTemporaryPassword = true
                try {
                    passwordId = password?.takeIf(String::isNotEmpty)?.let {
                        BackupPasswordStore.saveManual(context, it)
                    }
                    val values = mutableListOf<Pair<String, Any>>(
                        IS_AUTO_BACKUP_KEY to false,
                        LOCATION_URI_KEY to uri.toString(),
                        OPTIONS_KEY to options.asBooleanArray(),
                    )
                    passwordId?.let { values += PASSWORD_ID_KEY to it }
                    val request = OneTimeWorkRequestBuilder<BackupCreateJob>()
                        .addTag(TAG_MANUAL)
                        .setInputData(workDataOf(*values.toTypedArray()))
                        .build()
                    val workManager = context.workManager
                    workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request).result.await()
                    removeTemporaryPassword = false
                    val accepted = workManager.getWorkInfoById(request.id).await() != null
                    removeTemporaryPassword = !accepted
                    accepted
                } finally {
                    if (removeTemporaryPassword) passwordId?.let { BackupPasswordStore.removeManual(context, it) }
                }
            }
    }
}

private const val TAG_AUTO = "BackupCreator"
private const val TAG_MANUAL = "$TAG_AUTO:manual"

private const val IS_AUTO_BACKUP_KEY = "is_auto_backup" // Boolean
private const val LOCATION_URI_KEY = "location_uri" // String
private const val OPTIONS_KEY = "options" // BooleanArray
private const val PASSWORD_ID_KEY = "password_id"
