package tachiyomi.presentation.core.motion

class EInkRefreshScheduler(
    interval: Int = 1,
) {
    private var interval = interval.coerceAtLeast(1)
    private var requestCount = 0
    private var active = false
    private var lastKey: String? = null

    fun request(key: String): RequestResult {
        if (key == lastKey) return RequestResult.IGNORED
        lastKey = key

        val shouldRefresh = requestCount % interval == 0
        requestCount += 1
        if (!shouldRefresh) return RequestResult.IGNORED

        if (active) {
            return RequestResult.QUEUED
        }

        active = true
        return RequestResult.START
    }

    fun finish() {
        active = false
    }

    fun setInterval(value: Int) {
        interval = value.coerceAtLeast(1)
        reset()
    }

    fun reset() {
        requestCount = 0
        active = false
        lastKey = null
    }

    enum class RequestResult {
        START,
        QUEUED,
        IGNORED,
    }
}
