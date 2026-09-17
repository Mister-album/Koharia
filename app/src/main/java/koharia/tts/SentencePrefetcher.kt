package koharia.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Coroutine-based sentence prefetcher.
 *
 * Keeps up to [windowSize] sentence-synthesis jobs in flight ahead of
 * playback: whenever playback advances to [index] the window
 * `index until index + windowSize` is scheduled. Cache-first and
 * deduplicated per sentence index, so replay never re-synthesizes.
 * Consumed entries are dropped from the map (bytes stay in the disk
 * cache), which keeps resident audio bounded to the sliding window.
 */
class SentencePrefetcher(
    private val scope: CoroutineScope,
    private val engine: TtsEngine,
    private val cache: TtsCache,
    private val voice: String,
    private val style: String?,
    private val windowSize: Int = 5,
) {
    private val lock = Any()
    private val pending = HashMap<Int, Deferred<ByteArray?>>()
    private val synthesisDispatcher = Dispatchers.IO.limitedParallelism(windowSize)

    /**
     * Schedule synthesis for the window `index until index + windowSize`,
     * bounded by `sentences`. Indices already scheduled are skipped.
     */
    fun scheduleFrom(index: Int, sentences: List<Sentence>) {
        val end = (index + windowSize).coerceAtMost(sentences.size)
        for (i in index until end) {
            synchronized(lock) {
                if (i !in pending) {
                    pending[i] = scope.async(synthesisDispatcher) { synthesizeOrCache(sentences[i]) }
                }
            }
        }
    }

    /**
     * Await audio bytes for `sentence`. When the playback window already
     * moved past it, schedules it first (defensive), then awaits that one
     * [Deferred]. A null result (failure) is returned, not thrown.
     */
    suspend fun await(index: Int, sentence: Sentence): ByteArray? {
        val deferred = synchronized(lock) {
            pending[index] ?: scope.async(synthesisDispatcher) { synthesizeOrCache(sentence) }
                .also { pending[index] = it }
        }
        return try {
            deferred.await()
        } finally {
            // 消费完即释放音频字节的强引用；重访问会重新调度并直接命中磁盘缓存，
            // 避免整章结果驻留内存（无界章节的 OOM 防线）。
            synchronized(lock) {
                if (pending[index] === deferred) {
                    pending.remove(index)
                }
            }
        }
    }

    /** Cancel all outstanding synthesis jobs and clear the window state. */
    fun cancelAll() {
        synchronized(lock) {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    /**
     * Cache-first synthesis, mirroring `TtsService.synthesizeOrCache`:
     * cache hit → bytes; engine not configured → null; otherwise synthesize,
     * persist to cache, return audio data; any exception → null.
     */
    private suspend fun synthesizeOrCache(sentence: Sentence): ByteArray? {
        cache.get(voice, style, sentence.text)?.let { return it }

        if (!engine.isConfigured()) {
            logcat(LogPriority.ERROR) {
                "[SentencePrefetcher] TtsEngine not configured — set mimo.api.key in local.properties"
            }
            return null
        }
        return try {
            val result = engine.synthesize(sentence, SynthesisRequest(voice = voice, style = style))
            cache.put(voice, style, sentence.text, result.audioData)
            result.audioData
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[SentencePrefetcher] synthesis error for sentence ${sentence.index}" }
            null
        }
    }
}
