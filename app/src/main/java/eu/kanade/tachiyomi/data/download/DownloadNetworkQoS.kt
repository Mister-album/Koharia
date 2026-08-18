package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Coordinates download concurrency with the foreground reader. */
object DownloadNetworkQoS {
    private val activeReaders = AtomicInteger(0)
    private val _readerActive = MutableStateFlow(false)
    val readerActive = _readerActive.asStateFlow()

    fun acquireReader() {
        activeReaders.incrementAndGet()
        _readerActive.value = true
    }

    fun releaseReader() {
        val remaining = activeReaders.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        _readerActive.value = remaining > 0
    }
}
