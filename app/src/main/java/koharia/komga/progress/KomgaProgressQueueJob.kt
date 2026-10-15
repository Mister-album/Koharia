package koharia.komga.progress

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import koharia.connection.hasPausedConnectionWrites
import koharia.domain.komga.KomgaReadStatusKey
import koharia.source.komga.KomgaProgressOwnerChanged
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal fun interface KomgaProgressWorkDispatcher {
    suspend fun dispatch(key: KomgaReadStatusKey): KomgaProgressDispatchResult
}

class KomgaProgressQueueJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (koharia.connection.DevReadOnlyValidation.enabled) return Result.retry()
        val source = inputData.getLong("source", -1)
        val owner = inputData.getString("owner")
        val book = inputData.getString("book")
        if (source <= 0 || owner.isNullOrBlank() || book.isNullOrBlank() || book.length > 256 ||
            book.any(Char::isISOControl)
        ) {
            return Result.failure(workDataOf("progressStatus" to "invalid_identity"))
        }
        val key = KomgaReadStatusKey(source, owner, book)
        return try {
            val result = Injekt.get<KomgaProgressWorkDispatcher>().dispatch(key)
            if (result == KomgaProgressDispatchResult.PENDING) Injekt.get<KomgaProgressWakeups>().wake(key)
            Result.success(workDataOf("progressStatus" to result.name))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is KomgaProgressOwnerChanged || error.hasPausedConnectionWrites() ||
                error is KomgaProgressOnHold
            ) {
                Result.success(workDataOf("progressStatus" to KomgaProgressDispatchResult.HELD.name))
            } else {
                Result.retry()
            }
        }
    }
}
