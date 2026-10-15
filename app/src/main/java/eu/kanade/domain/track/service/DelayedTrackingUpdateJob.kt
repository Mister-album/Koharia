package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob internal constructor(
    context: Context,
    workerParams: WorkerParameters,
    private val delayedTrackingStore: DelayedTrackingStore,
) : CoroutineWorker(context, workerParams) {

    constructor(context: Context, workerParams: WorkerParameters) : this(context, workerParams, Injekt.get())

    override suspend fun doWork(): Result {
        if (koharia.connection.DevReadOnlyValidation.enabled) return Result.retry()
        return withIOContext {
            if (delayedTrackingStore.holdLegacyItems()) {
                Result.success(
                    workDataOf(
                        "progressStatus" to "held",
                        "heldCount" to delayedTrackingStore.getHeldItems().size,
                    ),
                )
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
