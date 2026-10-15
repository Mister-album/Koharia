package koharia.komga.progress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class KomgaProgressRecoveryJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if (koharia.connection.DevReadOnlyValidation.enabled) {
        Result.retry()
    } else {
        try {
            Result.success(workDataOf("recovered" to Injekt.get<KomgaProgressRecovery>().recover()))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.retry()
        }
    }

    companion object {
        private val startupGate = Mutex()
        private var startupScheduled = false

        suspend fun enqueueAtStartup(context: Context) = startupGate.withLock {
            if (koharia.connection.DevReadOnlyValidation.enabled || startupScheduled) return@withLock
            val request = OneTimeWorkRequestBuilder<KomgaProgressRecoveryJob>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "komga-progress-recovery",
                ExistingWorkPolicy.KEEP,
                request,
            ).result.await()
            startupScheduled = true
        }
    }
}
