package koharia.epub.replacement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owned by the reader's main thread, including manual turns and drags. */
internal class ReplacementNavigation {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun launch(scope: CoroutineScope, prepare: suspend () -> Boolean, navigate: () -> Unit) {
        cancel()
        val requestedGeneration = generation
        job = scope.launch {
            if (prepare() && generation == requestedGeneration) navigate()
        }
    }
}
