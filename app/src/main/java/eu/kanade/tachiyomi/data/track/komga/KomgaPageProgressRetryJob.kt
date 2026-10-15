package eu.kanade.tachiyomi.data.track.komga

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import koharia.connection.hasPausedConnectionWrites
import koharia.source.komga.KomgaProgressOwnerChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class KomgaPageProgressRetryJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (koharia.connection.DevReadOnlyValidation.enabled) return Result.retry()
        val reason = when {
            inputData.getString("owner").isNullOrBlank() -> "unknown_owner"
            inputData.getLong("revision", -1) < 0 -> "unknown_revision"
            else -> "legacy_payload"
        }
        return if (runCatching { KomgaHeldProgress.store(applicationContext, id, inputData, reason) }
                .getOrDefault(false)
        ) {
            Result.failure(workDataOf("progressStatus" to "held", "reason" to reason))
        } else {
            Result.retry()
        }
    }

    companion object {
        private fun resourceName(sourceId: Long, url: String, ownerKey: String): String =
            "komga-page-$sourceId-$ownerKey-" +
                MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun name(sourceId: Long, url: String, ownerKey: String, revision: Long) =
            "${resourceName(sourceId, url, ownerKey)}-r$revision"

        fun enqueue(
            sourceId: Long,
            url: String,
            page: Int,
            total: Int,
            baseline: KomgaApi.BookProgressSnapshot,
            ownerKey: String,
            revision: Long,
        ): UUID {
            require(ownerKey.isNotBlank() && revision >= 0)
            val request = OneTimeWorkRequestBuilder<KomgaPageProgressRetryJob>()
                .setInputData(
                    workDataOf(
                        "source" to sourceId,
                        "owner" to ownerKey,
                        "revision" to revision,
                        "url" to url,
                        "page" to page,
                        "total" to total,
                        "expectedPage" to (baseline.pageIndex ?: -1),
                        "expectedCompleted" to baseline.completed,
                        "expectedDate" to baseline.readDate,
                        "localReadAt" to -1L,
                    ),
                )
                .addTag(resourceName(sourceId, url, ownerKey))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            Injekt.get<Application>().workManager.enqueueUniqueWork(
                name(sourceId, url, ownerKey, revision),
                ExistingWorkPolicy.REPLACE,
                request,
            )
            return request.id
        }

        suspend fun enqueueLocal(
            sourceId: Long,
            url: String,
            page: Int,
            total: Int,
            readAt: Long,
            ownerKey: String,
            revision: Long,
        ): UUID {
            require(ownerKey.isNotBlank() && revision >= 0)
            val request = OneTimeWorkRequestBuilder<KomgaPageProgressRetryJob>()
                .setInputData(
                    workDataOf(
                        "source" to sourceId,
                        "owner" to ownerKey,
                        "revision" to revision,
                        "url" to url,
                        "page" to page,
                        "total" to total,
                        "expectedPage" to -1,
                        "expectedCompleted" to false,
                        "localReadAt" to readAt,
                    ),
                )
                .addTag(resourceName(sourceId, url, ownerKey))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            Injekt.get<Application>().workManager.enqueueUniqueWork(
                name(sourceId, url, ownerKey, revision),
                ExistingWorkPolicy.REPLACE,
                request,
            ).result.await()
            return request.id
        }

        suspend fun cancelRevision(sourceId: Long, url: String, ownerKey: String, revision: Long) {
            Injekt.get<Application>().workManager.cancelUniqueWork(
                name(sourceId, url, ownerKey, revision),
            ).result.await()
        }

        /** Explicit reset/restore cancels this resource's legacy and revision-scoped work only. */
        fun cancel(sourceId: Long, url: String, ownerKey: String) {
            val manager = Injekt.get<Application>().workManager
            val resource = resourceName(sourceId, url, ownerKey)
            manager.cancelUniqueWork(resource)
            manager.cancelAllWorkByTag(resource)
        }
    }
}
