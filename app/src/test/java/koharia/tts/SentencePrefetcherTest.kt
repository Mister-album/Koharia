package koharia.tts

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tests for [SentencePrefetcher].
 *
 * No mockk (sandboxed CI JVM throws SecurityException on setAccessible) and no
 * runBlocking (never returns there). Determinism comes from awaiting the
 * scheduled [kotlinx.coroutines.Deferred]s, which drives each synthesis job to
 * completion on the real IO dispatcher before assertions run; the remaining
 * virtual time is advanced via the runTest scheduler.
 */
class SentencePrefetcherTest {

    private val voice = "冰糖"
    private val style: String? = null

    /** Build [count] distinct one-sentence [Sentence]s via the real segmenter. */
    private fun sentences(count: Int): List<Sentence> {
        val text = (1..count).joinToString("") { "这是第${it}句话。" }
        return SentenceSegmenter.cut(chapterHref = "ch-1", text = text)
    }

    private class FakeEngine(
        private val configured: Boolean = true,
        private val blockOnGate: CompletableDeferred<Unit>? = null,
        private val produce: (Sentence) -> ByteArray = { sentence -> byteArrayOf(sentence.index.toByte()) },
    ) : TtsEngine {
        override val engineId: String = "fake"
        override val displayName: String = "FakeEngine"
        val callIndices: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val started = Channel<Int>(Channel.UNLIMITED)
        val unwound = Channel<Int>(Channel.UNLIMITED)

        override fun isConfigured(): Boolean = configured

        override suspend fun listVoices(): List<Voice> = emptyList()

        override suspend fun synthesize(sentence: Sentence, request: SynthesisRequest): SynthesisResult {
            callIndices += sentence.index
            started.send(sentence.index)
            blockOnGate?.let { gate ->
                suspendCancellableCoroutine<Unit> { cont ->
                    // Cancellation of the surrounding coroutine (e.g. via
                    // [SentencePrefetcher.cancelAll]) must always signal `unwound`
                    // and resume with CancellationException, even if `gate` has
                    // already completed by the time we observe it. Plain
                    // `CompletableDeferred.await()` is racy here: its fast-path
                    // returns the completed result without checking the outer
                    // Job's cancellation state, so a synthesize job that "lost"
                    // the cancel race would still fall through to `produce` and
                    // write to the disk cache, defeating the test's intent.
                    cont.invokeOnCancellation {
                        // Channel.send is a suspend function, but
                        // invokeOnCancellation runs the handler inline; with
                        // Channel.UNLIMITED send never actually suspends, so
                        // using runBlocking here would be wrong. Instead we
                        // use a trySend — it returns failure rather than
                        // blocking, which matches the "best effort" semantics
                        // of a cancellation handler.
                        unwound.trySend(sentence.index)
                    }
                    gate.invokeOnCompletion { cause ->
                        if (cont.isCancelled) return@invokeOnCompletion
                        if (cause != null) cont.resumeWithException(cause)
                        else cont.resume(Unit)
                    }
                }
            }
            // Normal path: signal unwound after the wait completes, then produce.
            unwound.send(sentence.index)
            val audioData = produce(sentence)
            return SynthesisResult(
                audioData = audioData,
                format = AudioFormat.MP3,
                sampleRate = 24_000,
                channels = 1,
                durationMs = audioData.size.toLong(),
                elapsedMs = 1L,
            )
        }
    }

    /** Snapshot of the recorded engine calls (synchronized wrapper has identity equals). */
    private fun FakeEngine.calls(): List<Int> = callIndices.toList()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `await on cache miss synthesizes once and returns the audio bytes`(@TempDir tempDir: Path) = runTest {
        val list = sentences(1)
        val engine = FakeEngine()
        val cache = TtsCache(tempDir.toFile())
        val prefetcher = SentencePrefetcher(scope = this, engine = engine, cache = cache, voice = voice, style = style)

        val first = prefetcher.await(0, list[0])
        testScheduler.advanceUntilIdle()

        first shouldBe byteArrayOf(0)
        engine.calls() shouldBe listOf(0)
        cache.get(voice, style, list[0].text) shouldBe first

        prefetcher.cancelAll()
        val second = prefetcher.await(0, list[0])
        second shouldBe first
        // Second read is served from the disk cache; engine stays untouched.
        engine.calls() shouldBe listOf(0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `scheduling the same window twice never re-synthesizes an index`(@TempDir tempDir: Path) = runTest {
        val list = sentences(5)
        val engine = FakeEngine()
        val prefetcher = SentencePrefetcher(this, engine, TtsCache(tempDir.toFile()), voice, style)

        prefetcher.scheduleFrom(0, list)
        prefetcher.scheduleFrom(0, list)
        testScheduler.advanceUntilIdle()

        repeat(5) { i -> prefetcher.await(i, list[i]) }

        engine.calls().sorted() shouldBe (0..4).toList()
        engine.calls() shouldHaveSize 5
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `window size caps how many sentences are scheduled ahead`(@TempDir tempDir: Path) = runTest {
        val list = sentences(20)
        val engine = FakeEngine()
        val prefetcher = SentencePrefetcher(this, engine, TtsCache(tempDir.toFile()), voice, style, windowSize = 5)

        prefetcher.scheduleFrom(0, list)
        testScheduler.advanceUntilIdle()

        repeat(5) { i -> prefetcher.await(i, list[i]) }

        engine.calls() shouldHaveSize 5
        engine.calls().maxOrNull() shouldBe 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `engine failure returns null instead of crashing`(@TempDir tempDir: Path) = runTest {
        val list = sentences(1)
        val engine = FakeEngine(produce = { throw IllegalStateException("boom") })
        val prefetcher = SentencePrefetcher(this, engine, TtsCache(tempDir.toFile()), voice, style)

        val bytes = prefetcher.await(0, list[0])

        bytes shouldBe null
        engine.calls() shouldBe listOf(0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `unconfigured engine never synthesizes and await returns null`(@TempDir tempDir: Path) = runTest {
        val list = sentences(1)
        val engine = FakeEngine(configured = false)
        val prefetcher = SentencePrefetcher(this, engine, TtsCache(tempDir.toFile()), voice, style)

        val bytes = prefetcher.await(0, list[0])

        bytes shouldBe null
        engine.calls() shouldBe emptyList()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `await releases the entry so re-await hits disk cache not the engine`(@TempDir tempDir: Path) = runTest {
        val list = sentences(2)
        val engine = FakeEngine()
        val cache = TtsCache(tempDir.toFile())
        val prefetcher = SentencePrefetcher(this, engine, cache, voice, style)

        prefetcher.scheduleFrom(0, list)
        val first = prefetcher.await(0, list[0])
        // No cancelAll here: the completed entry must have been dropped by await itself,
        // so the second await re-schedules and is served from the disk cache.
        val again = prefetcher.await(0, list[0])

        first shouldBe byteArrayOf(0)
        again shouldBe first
        engine.calls().count { it == 0 } shouldBe 1
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelAll stops in-flight synthesis from completing`(@TempDir tempDir: Path) = runTest {
        val list = sentences(5)
        val release = CompletableDeferred<Unit>()
        val engine = FakeEngine(blockOnGate = release)
        val cache = TtsCache(tempDir.toFile())
        val prefetcher = SentencePrefetcher(this, engine, cache, voice, style)

        prefetcher.scheduleFrom(0, list)
        repeat(5) { engine.started.receive() }

        prefetcher.cancelAll()
        release.complete(Unit)
        repeat(5) { engine.unwound.receive() }

        // All five synthesis calls started; cancellation kept them from producing.
        engine.calls() shouldHaveSize 5
        cache.sizeBytes() shouldBe 0L
    }
}
