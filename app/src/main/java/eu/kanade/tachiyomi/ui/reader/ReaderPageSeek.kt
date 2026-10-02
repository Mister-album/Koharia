package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ReaderPageSeek(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var target: Int? = null
    private var dispatched: Int? = null

    fun update(index: Int, preview: () -> Unit = {}, seek: () -> Unit) {
        // Movement within the same page must not postpone loading indefinitely.
        if (target == index) return
        target = index
        job?.cancel()
        dispatched = null
        preview()
        job = scope.launch {
            delay(100L)
            seek()
            dispatched = index
        }
    }

    fun finish(index: Int, preview: () -> Unit = {}, seek: () -> Unit) {
        job?.cancel()
        if (target != index) preview()
        if (dispatched != index) seek()
        cancel()
    }

    fun cancel() {
        job?.cancel()
        target = null
        dispatched = null
        job = null
    }
}
