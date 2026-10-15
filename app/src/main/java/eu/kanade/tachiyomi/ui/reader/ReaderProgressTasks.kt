package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async

/** Capture order at the viewer callback, before dispatching persistence to an IO thread. */
internal class ReaderProgressTasks(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val monitor = Any()
    private var tail: Deferred<*>? = null

    fun <T> submit(operation: suspend () -> T): Deferred<T> {
        val task = synchronized(monitor) {
            val previous = tail
            scope.async(dispatcher + NonCancellable, start = CoroutineStart.LAZY) {
                previous?.join()
                operation()
            }.also { tail = it }
        }
        task.invokeOnCompletion { synchronized(monitor) { if (tail === task) tail = null } }
        task.start()
        return task
    }

    suspend fun <T> run(operation: suspend () -> T): T = submit(operation).await()
}
