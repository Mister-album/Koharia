package koharia.source.local

import koharia.connection.ConnectionLibraryRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Explicit scans belong to the application, not the screen waiting for their result. */
class LocalLibraryRefreshTasks(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val tasks = mutableMapOf<Long, Deferred<Result<ConnectionLibraryRefreshResult>>>()
    private val active = MutableStateFlow(emptySet<Long>())
    val activeIds = active.asStateFlow()

    suspend fun cancel(id: Long) {
        synchronized(tasks) { tasks[id] }?.cancelAndJoin()
    }

    suspend fun refresh(
        id: Long,
        scan: suspend () -> ConnectionLibraryRefreshResult,
    ): Result<ConnectionLibraryRefreshResult> {
        val task = synchronized(tasks) {
            tasks[id]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    Result.success(scan())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }.also { task ->
                tasks[id] = task
                active.update { it + id }
                task.invokeOnCompletion {
                    synchronized(tasks) {
                        if (tasks[id] === task) {
                            tasks.remove(id)
                            active.update { it - id }
                        }
                    }
                }
                task.start()
            }
        }
        return task.await()
    }
}
