package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupPasswordStore
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

class BackupRestoreJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri()
        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { RestoreOptions.fromBooleanArray(it) }

        if (uri == null || options == null) {
            return Result.failure()
        }

        val isSync = inputData.getBoolean(SYNC_KEY, false)
        val passwordId = inputData.getString(PASSWORD_ID_KEY)
        val directoryBindingsId = inputData.getString(DIRECTORY_BINDINGS_ID_KEY)

        setForegroundSafely()

        var password: CharArray? = null
        return try {
            password = passwordId?.let { BackupPasswordStore.loadManual(context, it) }
            val directoryBindings = directoryBindingsId?.let { RestoreDirectoryBindings.load(context, it) }.orEmpty()
            BackupRestorer(context, notifier, isSync).restore(uri, options, password, directoryBindings)
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                notifier.showRestoreError(context.stringResource(MR.strings.restoring_backup_canceled))
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                notifier.showRestoreError(e.message)
                Result.failure()
            }
        } finally {
            password?.fill('\u0000')
            passwordId?.let { BackupPasswordStore.removeManual(context, it) }
            directoryBindingsId?.let { RestoreDirectoryBindings.remove(context, it) }
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        fun isRunning(context: Context): Boolean {
            return context.workManager.getWorkInfosByTag(TAG).get().any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }

        suspend fun start(
            context: Context,
            uri: Uri,
            options: RestoreOptions,
            sync: Boolean = false,
            password: String? = null,
            directoryBindings: Map<String, String> = emptyMap(),
        ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
            var passwordId: String? = null
            var directoryBindingsId: String? = null
            var removeTemporaryData = true
            try {
                passwordId = password?.takeIf(String::isNotEmpty)?.let {
                    BackupPasswordStore.saveManual(context, it)
                }
                directoryBindingsId = directoryBindings.takeIf(Map<String, String>::isNotEmpty)?.let {
                    RestoreDirectoryBindings.save(context, it)
                }
                val values = mutableListOf<Pair<String, Any>>(
                    LOCATION_URI_KEY to uri.toString(),
                    SYNC_KEY to sync,
                    OPTIONS_KEY to options.asBooleanArray(),
                )
                passwordId?.let { values += PASSWORD_ID_KEY to it }
                directoryBindingsId?.let { values += DIRECTORY_BINDINGS_ID_KEY to it }
                val request = OneTimeWorkRequestBuilder<BackupRestoreJob>()
                    .addTag(TAG)
                    .setInputData(workDataOf(*values.toTypedArray()))
                    .build()
                val workManager = context.workManager
                workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request).result.await()
                removeTemporaryData = false
                val accepted = workManager.getWorkInfoById(request.id).await() != null
                removeTemporaryData = !accepted
                accepted
            } finally {
                if (removeTemporaryData) {
                    passwordId?.let { BackupPasswordStore.removeManual(context, it) }
                    directoryBindingsId?.let { RestoreDirectoryBindings.remove(context, it) }
                }
            }
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}

private const val TAG = "BackupRestore"

private const val LOCATION_URI_KEY = "location_uri" // String
private const val SYNC_KEY = "sync" // Boolean
private const val OPTIONS_KEY = "options" // BooleanArray
private const val PASSWORD_ID_KEY = "password_id"
private const val DIRECTORY_BINDINGS_ID_KEY = "directory_bindings_id"
