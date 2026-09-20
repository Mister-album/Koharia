package koharia.tts.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
/**
 * Phase 1b 播放器：MediaExtractor 解析 MP3 → MediaCodec 解码 PCM → 单条 AudioTrack(MODE_STREAM)。
 *
 * 与 Phase 1c 的 MediaPlayer 方案的区别：每句不再新建播放器 + prepare + start
 * （句间 ~600ms 空隙的来源），而是把各句 PCM 连续写进同一条 AudioTrack，
 * 句间只剩「临时文件 + extractor + codec 重建」的几十毫秒；配合 SentencePrefetcher
 * 保持喂料，听感接近无缝。
 *
 * 为什么必须走 MediaExtractor：c2.android.mp3.decoder 对手搓的
 * `MediaFormat.createAudioFormat(mime, rate, ch)` 直接回 BAD_CONFIG（模拟器日志实锤），
 * 只有 extractor 解析出来的权威格式才能 configure 成功——MediaPlayer 内部也是这条路。
 *
 * 线程模型：一条守护线程消费有界 [queue]（满时 [enqueue] 挂起形成背压）；
 * [stop] 清空队列并 pause+flush（正在阻塞的 write 会被释放）；
 * [release] 投毒退出线程并释放资源。
 */
class TtsPlayer(
    private val tempDir: File,
    /** 句间静音时长：解码无间隙后靠它维持自然朗读节奏（Phase 3 接设置页可调）。 */
    private val interSentenceGapMs: Long = DEFAULT_GAP_MS,
    /**
     * MiMo 引擎合成 MP3 帧头报告的 encoder-delay 采样数（每声道）。
     * decoder 实际产出 PCM 时不会自动剥除这些前置静音——这里在 per-clip PCM
     * 装配时手工裁掉。默认 576 是 Phase 1b 真机 audit 观测到的值。
     */
    private val encoderDelaySamples: Int = DEFAULT_ENCODER_DELAY_SAMPLES,
    /**
     * MiMo 引擎合成 MP3 帧头报告的 encoder-padding 采样数（每声道）。
     * 默认 768 是 Phase 1b 真机 audit 观测到的保守值；个别 clip 报 960 也被覆盖。
     */
    private val encoderPaddingSamples: Int = DEFAULT_ENCODER_PADDING_SAMPLES,
    /**
     * 某句音频**真正开始写入 AudioTrack**（≈开始发声）时回调其句下标。
     *
     * 高亮/进度必须跟随这里，而不是跟随入队：`SentencePrefetcher` + 本播放器的
     * 有界队列（[QUEUE_CAPACITY]）会让入队领先实际发声最多 8 句，若用入队驱动力
     * 高亮，用户会看到高亮跑到音频前面好几句。
     *
     * 回调在播放器工作线程上执行，实现方需自行保证线程安全。
     */
    private val onClipStarted: (Int) -> Unit = {},
    /**
     * 某句音频**确实写出了非空 PCM**（=真的会有声音）时回调其句下标。
     *
     * 与 [onClipStarted] 的区别，以及为什么必须分开：
     *  - [onClipStarted] 在 worker **刚取到 clip** 时就触发（此时还没解码）——这是高亮/进度
     *    不滞后的前提，不能改到解码之后（否则高亮会整整慢一句）。
     *  - 但"这一句到底有没有声音"只能在**写完 AudioTrack 之后**判断：厂商返回 HTTP 200、
     *    正文却是错误 JSON/HTML，或 MP3 损坏时，clip 被取到、解码为空，一个字都没写。
     *
     * 若用 [onClipStarted] 统计"真正发声的句数"，上述零声音的章节会被判成播完，
     * 阅读器于是在完全没有声音的情况下连续跳章 —— 正是 review P1 要消除的故障类别。
     *
     * 回调在播放器工作线程上执行，实现方需自行保证线程安全。
     */
    private val onClipAudioProduced: (Int) -> Unit = {},
) {

    private class Clip(val mp3: ByteArray, val index: Int)

    /**
     * [awaitDrained] 用的 FIFO 栅栏：投递到有界队列尾部，worker 按顺序消费到它时
     * [CountDownLatch.countDown]。栅栏被消费 ⟺ 它之前入队的所有 clip 都已写入 AudioTrack。
     */
    private class DrainBarrier(val latch: CountDownLatch)

    private val queue = LinkedBlockingQueue<Any?>(QUEUE_CAPACITY)
    private val stopped = AtomicBoolean(false)

    /**
     * Phase 3 step 1：暂停开关。`true` 时 worker 在 [runLoop] 的 pause 闸门阻塞，
     * 已入队但未消费的 clip 留在 [queue] 里；`pause()`/`play()` 同时切 AudioTrack 输出。
     *
     * 与 [stopped] 的语义区别：stopped 是"丢弃剩余、清空队列、flush AudioTrack"（不可恢复）；
     * paused 是"暂时静音但保留队列和 AudioTrack 缓冲"（可恢复）。
     */
    private val paused = AtomicBoolean(false)

    /**
     * Phase 3 step 2：朗读语速倍率（1.0 = 正常，0.5 = 半速，2.0 = 两倍速）。
     *
     * 通过 [AudioTrack.setPlaybackParams]（pitch 固定 1.0）实现**音高不变的变速**
     * （官方文档称 timestretching；AOSP 内部用 Sonic/PICOLA）。由 [setSpeed] 更新。
     */
    @Volatile
    private var speed: Float = 1.0f

    /**
     * Phase 5 (PR review)：输出音量（0f..1f），默认 1.0。
     *
     * 供 Service 在音频焦点 duck（AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK）时压低。
     * 跨句复用同一条 AudioTrack；新建 track 时在 [ensureTrack] 里重新套用。
     */
    @Volatile
    private var volume: Float = 1.0f

    private val framesWritten = AtomicLong(0L)
    private val trackRef = AtomicReference<AudioTrack?>(null)

    @Volatile
    private var released = false

    @Volatile
    private var currentSampleRate = 0

    @Volatile
    private var currentChannels = 0

    private val worker = Thread(::runLoop, "koharia-tts-player").apply { isDaemon = true }
    private var workerStarted = false

    @Synchronized
    private fun ensureWorker() {
        if (!workerStarted) {
            workerStarted = true
            worker.start()
        }
    }

    /**
     * 提交一句 MP3。队列有界（[QUEUE_CAPACITY]）：满时在此挂起形成背压，
     * 防止整章命中缓存时把几百句音频一次性堆进内存。
     *
     * @param index 该句在章节内的下标；实际开始播放时经 [onClipStarted] 回调。
     */
    suspend fun enqueue(mp3: ByteArray, index: Int) {
        if (released) return
        ensureWorker()
        stopped.set(false)
        // 可被协程取消：队列满时用 cancellable delay 轮询，避免 stop/skipTo 的
        // cancelAndJoin 在"暂停 + 满队列"下永远等不到本循环退出。
        while (currentCoroutineContext().isActive && !released && !stopped.get()) {
            if (runCatching { queue.offer(Clip(mp3, index)) }.getOrDefault(false)) return
            delay(ENQUEUE_POLL_MS)
        }
    }

    /**
     * 立即停止：丢弃未播的队列与已写入未播放的缓冲。
     */
    fun stop() {
        stopped.set(true)
        paused.set(false)
        queue.clear()
        framesWritten.set(0L)
        trackRef.get()?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    /**
     * Phase 3 step 1 followup:跳句重置。
     *
     * 与 [stop] 的区别:**不暂停 AudioTrack**。stop() 在 skipTo 路径里会让 track 进入 PAUSED 状态,
     * 而 [TtsPlayer.play] 只在 `paused == true` 时才调 track.play(),所以 skipTo 之后
     * NEW job 写入的 PCM 进的是 paused track,听不见。
     *
     * 这里:
     * 1. stopped=true → worker 跳过当前 clip(若正处理 OLD PCM,解码后丢弃)
     * 2. paused=false → 下次 [enqueue] 把 stopped 改 false 后,worker 立即开始消费新 clip
     * 3. queue.clear() → 清掉 OLD 残留
     * 4. framesWritten=0 → 下次写 PCM 时偏移从 0 开始
     * 5. **track.pause() + flush() + track.play()** —— 清 OLD 缓冲,但**保持 track 处于播放状态**,
     *    让 NEW PCM 写入后立即发声
     */
    fun skipTo() {
        stopped.set(true)
        paused.set(false)
        queue.clear()
        framesWritten.set(0L)
        trackRef.get()?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.play() }
            // Phase 3 step 2：pause→play 后重新套用语速（保持 time-stretch 生效）。
            applyPlaybackParams(track)
        }
    }

    /**
     * Phase 3 step 1：暂停。保留 [queue] 中已入队但未消费的 clip 和 AudioTrack 缓冲；
     * [runLoop] 在下一次取元素前会卡在 pause 闸门，AudioTrack.pause() 让声卡输出立即停止。
     *
     * 多次调用幂等。与 [stop] 的区别：可恢复（[play]），队列不丢。
     */
    fun pause() {
        if (stopped.get() || released) return
        if (paused.compareAndSet(false, true)) {
            trackRef.get()?.let { runCatching { it.pause() } }
            logcat(LogPriority.INFO) { "[TtsPlayer] paused" }
        }
    }

    /**
     * Phase 3 step 1：从暂停恢复。[runLoop] 解除阻塞，AudioTrack.play() 让声卡输出恢复。
     * 已被暂停的 AudioTrack 缓冲中的 PCM 会先被消费，再取队列里的下一句。
     *
     * 多次调用幂等；非 paused 状态下调用无副作用。
     */
    fun play() {
        if (stopped.get() || released) return
        if (paused.compareAndSet(true, false)) {
            trackRef.get()?.let { track ->
                runCatching { track.play() }
                // Phase 3 step 2：恢复播放后重新套用语速（暂停期间 setSpeed 会被跳过）。
                applyPlaybackParams(track)
            }
            logcat(LogPriority.INFO) { "[TtsPlayer] resumed" }
        }
    }

    /** 当前是否处于暂停状态。供 Service 同步 MediaSession.PlaybackState 使用。 */
    fun isPaused(): Boolean = paused.get()

    /**
     * Phase 3 step 2：设置朗读语速倍率（[MIN_SPEED]..[MAX_SPEED]）。
     *
     * 用 [AudioTrack.setPlaybackParams] 且 **pitch 固定 1.0**。官方 `PlaybackParams` 文档：
     * "Pitch equals 1.0f. Speed change will be done with pitch preserved, often called
     * timestretching" —— 即音高不变的变速（不是 `setPlaybackRate` 那种重采样变调）。
     *
     * [setPlaybackParams] 只对 **PLAYING** 状态的 track 生效；不可用时仅记录数值，
     * 等 [play] / [skipTo] / [ensureTrack] 恢复播放时再套用。幂等。
     */
    fun setSpeed(newSpeed: Float) {
        val clamped = newSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (clamped == speed) return
        speed = clamped
        trackRef.get()?.let { applyPlaybackParams(it) }
    }

    /** 当前语速（1.0 = 正常）。 */
    fun currentSpeed(): Float = speed

    /**
     * Phase 5 (PR review)：设置输出音量（0f..1f）。服务层在音频焦点 duck / 恢复时调用。
     *
     * 幂等；作用于当前 [AudioTrack]，并记住以便 [ensureTrack] 重建 track 后继续生效。
     */
    fun setVolume(newVolume: Float) {
        val clamped = newVolume.coerceIn(0f, 1f)
        if (clamped == volume) return
        volume = clamped
        trackRef.get()?.let { track ->
            runCatching { track.setVolume(clamped) }
        }
        logcat(LogPriority.INFO) { "[TtsPlayer] volume -> $clamped" }
    }

    /** 把当前 [speed] 套用到 [track]（需 track 处于 PLAYING）。失败不致命，降级为不变速。 */
    private fun applyPlaybackParams(track: AudioTrack) {
        try {
            track.setPlaybackParams(PlaybackParams().setSpeed(speed).setPitch(1.0f))
            logcat(LogPriority.INFO) { "[TtsPlayer] playback speed set to ${speed}x" }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "[TtsPlayer] setPlaybackParams(speed=$speed) failed; keeping previous rate"
            }
        }
    }

    /**
     * 挂起直到队列清空且播放头追上已写入帧数；被 [stop] 或协程取消打断时提前返回。
     *
     * Phase 3 step 1 修复(1)：旧实现用 `queue.isEmpty() && (track == null || written == 0L || ...)`
     * 判断"播完"，但 worker 可能刚 `take()` 走最后一句、AudioTrack 还在创建中（`track == null`），
     * 此时会**过早返回** → `runPlaybackJob` 立刻 `stopSelf()` → `onDestroy` release 掉还没发声的
     * AudioTrack（真机症状：章节末尾点播放只有 ~45ms 就 `playback complete`，完全没声音，
     * 日志里 `track ready` 反而出现在 `playback complete` **之后**）。
     *
     * 现在改为 FIFO 栅栏 [DrainBarrier]：在队列尾部投递栅栏，worker 处理完它之前的所有 clip 后
     * `countDown`。栅栏被消费 ⟺ 所有已入队 clip 都已写入 AudioTrack；之后再等 `playbackHeadPosition`
     * 追上 `framesWritten`，保证真正发声完毕才返回。
     */
    suspend fun awaitDrained() {
        if (released || stopped.get()) return
        ensureWorker()
        val barrier = DrainBarrier(CountDownLatch(1))
        // 队列有界（满时形成背压）：轮询投递直到成功 / 被取消 / 被停止 / 被释放。
        while (currentCoroutineContext().isActive && !released && !stopped.get() && barrier.latch.count > 0L) {
            val accepted = runCatching {
                queue.offer(barrier, ENQUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (accepted) break
        }
        // 分阶段日志：区分"worker 没消费 barrier"（卡在 writePcm/pumpClip）
        // 与"播放头追不上 framesWritten"（卡在 drain 循环）两种故障。
        logcat(LogPriority.DEBUG) { "[TtsPlayer] awaitDrained: barrier queued, waiting worker" }
        while (currentCoroutineContext().isActive) {
            if (stopped.get() || released) return
            if (barrier.latch.count == 0L) break
            delay(DRAIN_POLL_MS)
        }
        if (stopped.get() || released) return
        logcat(LogPriority.DEBUG) { "[TtsPlayer] awaitDrained: worker drained all clips, waiting playback head" }
        // 第三阶段：等播放头追上写入帧数（真正发声完毕）。
        //
        // ⚠️ **必须有界**。`playbackHeadPosition` 与 [framesWritten] 的基准会在
        // [skipTo]/[stop] 的 `flush()` 后错位：flush 丢弃未播数据却**不重置播放头**，
        // 于是 written 可能永远比 head 大出一大截。无界等待会让 [awaitDrained] 永久挂住
        // → `playback complete` 打不出来 → `notifyChapterCompleted()` 不触发
        // → **自动续章失效 + 服务静默停住**。
        //
        // 真机证据：head 冻结在 266256 而 written=312336，差值 46080 帧（1.92s，远大于
        // 400ms 缓冲），永远追不上。
        //
        // 因此改用"播放头是否还在前进"作为主判据，并加双重上限：
        //   - [DRAIN_STALL_ROUNDS]：播放头连续 ~1s 不动 ⟹ 音频确已放完（或 track 已死）
        //   - [DRAIN_MAX_ROUNDS]：整体上限，防止缓慢前进导致的超长等待
        // 暂停期间播放头本来就不动，不计入停滞判定，继续等（resume 后会自动恢复前进）。
        var lastHead = -1L
        var stalledRounds = 0
        var drainRounds = 0
        while (currentCoroutineContext().isActive) {
            if (stopped.get() || released) return
            if (paused.get()) {
                lastHead = -1L
                stalledRounds = 0
                delay(DRAIN_POLL_MS)
                continue
            }
            val track = trackRef.get() ?: return
            val written = framesWritten.get()
            val head = track.playbackHeadPosition.toLong()
            if (written == 0L || head >= written) {
                logcat(LogPriority.DEBUG) {
                    "[TtsPlayer] awaitDrained: done head=$head written=$written"
                }
                return
            }
            if (head == lastHead) {
                if (++stalledRounds >= DRAIN_STALL_ROUNDS) {
                    logcat(LogPriority.WARN) {
                        "[TtsPlayer] awaitDrained: playback head stalled at $head for " +
                            "${stalledRounds * DRAIN_POLL_MS}ms (written=$written) — giving up, " +
                            "treating chapter as finished"
                    }
                    return
                }
            } else {
                stalledRounds = 0
                lastHead = head
            }
            if (++drainRounds >= DRAIN_MAX_ROUNDS) {
                logcat(LogPriority.WARN) {
                    "[TtsPlayer] awaitDrained: drain timeout after " +
                        "${drainRounds * DRAIN_POLL_MS}ms (head=$head written=$written)"
                }
                return
            }
            if (drainRounds % 25 == 0) {
                logcat(LogPriority.DEBUG) {
                    "[TtsPlayer] awaitDrained: still draining head=$head written=$written"
                }
            }
            delay(DRAIN_POLL_MS)
        }
    }

    fun release() {
        if (released) return
        released = true
        queue.clear()
        queue.offer(POISON)
        if (workerStarted) {
            runCatching { worker.join(WORKER_JOIN_MS) }
            if (worker.isAlive) worker.interrupt()
        }
        releaseTrack()
    }

    private fun releaseTrack() {
        trackRef.getAndSet(null)?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    // ===== 播放线程 =====

    private fun runLoop() {
        // 高亮推进时机：worker 取到 clip N 时**立即**通知 item.index。
        //
        // 时序依据：进入这里 ⟹ [pumpClip] 刚把 clip N-1 的 PCM + 句间静音全部写完。
        // [writePcm] 是阻塞式（`track.write` 在缓冲满时挂起），而 AudioTrack 只缓冲约
        // [TIME_STRETCH_BUFFER_MS]=400ms，所以此刻播放头 ≈ 句子 N 起点前 ~0.4s。
        // 通知 N 只会让高亮早约 0.4s，感知不到。
        //
        // ⚠️ Phase 3 step 3 曾把这里改成"通知 index-1"（lag by 1），想防"暂停期间高亮
        // 跑飞"。实测这是错的：单句 ~3s 的音频 [pumpClip] 也要 ~3s 才返回（writePcm 与
        // 播放近似同步），于是高亮整整**滞后一句** —— 真机表现就是
        // "语音已读下一句，高亮还停在上一句"。
        // 暂停期间也不可能连取多个 clip：track.pause() 后缓冲不再排空，写入立刻阻塞在
        // [writePcm]，最多再多引出一句的量（≈400ms）。
        while (true) {
            if (released) return
            // Phase 3 step 1：暂停闸门。paused 时 worker 在这里 sleep，避免消费队列里
            // 的 clip（让它们留在 queue 等 resume）。100ms 轮询足够响应 play()，又不会
            // 空转吃 CPU。
            while (paused.get() && !released) {
                try {
                    Thread.sleep(PAUSE_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (released) return
            val item = try {
                queue.take()
            } catch (error: InterruptedException) {
                break
            }
            if (item === POISON) break
            when (item) {
                is Clip -> {
                    if (stopped.get()) continue
                    // 详见函数上方"高亮推进时机"注释。
                    logcat(LogPriority.DEBUG) {
                        "[TtsPlayer] clip start idx=${item.index} headMs=${playbackHeadMs()}"
                    }
                    runCatching { onClipStarted(item.index) }
                    val produced = try {
                        pumpClip(item.mp3, item.index)
                    } catch (error: Exception) {
                        logcat(LogPriority.ERROR, error) { "[TtsPlayer] clip decode failed, skipping" }
                        false
                    }
                    // 只有真的写出非空 PCM 才上报"这一句发声了"。
                    // stopped/released 时不上报：播放已被新一轮接管，计数会污染新轮的零声音判定。
                    if (produced && !stopped.get() && !released) {
                        runCatching { onClipAudioProduced(item.index) }
                    }
                }
                // 队列 FIFO：处理到这里 ⟹ 之前的 clip 都已 pump 完
                is DrainBarrier -> item.latch.countDown()
                else -> Unit
            }
        }
    }

    /**
     * 一句 MP3：临时文件 → extractor 出权威格式 → 解码 → PCM 连续写入 AudioTrack。
     * decoder 每句新建（~10-30ms），AudioTrack 跨句复用保证流式无缝。
     */
    private fun pumpClip(mp3: ByteArray, index: Int): Boolean {
        tempDir.mkdirs()
        val file = File(tempDir, "pump-${System.nanoTime()}.mp3")
        file.writeBytes(mp3)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var sourceTrackIndex = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    sourceTrackIndex = i
                    sourceFormat = f
                    break
                }
            }
            val clipFormat = sourceFormat
            if (sourceTrackIndex < 0 || clipFormat == null) {
                logcat(LogPriority.WARN) { "[TtsPlayer] no audio track in clip, skipping" }
                return false
            }
            extractor.selectTrack(sourceTrackIndex)
            val mime = clipFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val track = ensureTrack(clipFormat)
            if (track == null) {
                logcat(LogPriority.ERROR) { "[TtsPlayer] audio track unavailable, skipping clip" }
                return false
            }
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(clipFormat, null, null, 0)
                start()
            }
            val rawPcm = decodeIntoBytes(decoder, extractor)
            if (stopped.get() || released) return false
            val trimmed = trimGaplessPcm(
                pcm = rawPcm,
                channels = currentChannels.coerceAtLeast(1),
                delaySamples = encoderDelaySamples,
                paddingSamples = encoderPaddingSamples,
            )
            // 分阶段日志：真机上"卡在哪个阶段"一眼可见（历史故障是 writePcm 永久阻塞）。
            logcat(LogPriority.DEBUG) {
                "[TtsPlayer] clip $index decode done raw=${rawPcm.size}B trimmed=${trimmed.size}B " +
                    "headMs=${playbackHeadMs()}"
            }
            val writtenBytes = if (trimmed.isNotEmpty()) writePcm(track, trimmed) else 0
            logcat(LogPriority.DEBUG) {
                "[TtsPlayer] clip $index pcm written=$writtenBytes/B framesWritten=${framesWritten.get()} " +
                    "headMs=${playbackHeadMs()}"
            }
            if (writtenBytes <= 0) {
                // 解码成功但一个字节都没写进 AudioTrack（厂商返回 HTTP 200 正文却不是音频、
                // MP3 损坏、track 不可用…）—— 这一句**没有声音**，不能算"已发声"。
                logcat(LogPriority.WARN) {
                    "[TtsPlayer] clip $index produced no PCM; not counted as played"
                }
                return false
            }
            if (!stopped.get() && !released) writeGap(track)
            logcat(LogPriority.DEBUG) { "[TtsPlayer] clip $index pumped fully" }
            return true
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            runCatching { file.delete() }
        }
    }

    private fun decodeInto(decoder: MediaCodec, extractor: MediaExtractor, track: AudioTrack) {
        decodeIntoCollect(decoder, extractor) { bytes -> writePcm(track, bytes) }
    }

    /**
     * 一次解码完一段 MP3，返回拼好的 PCM 字节流。
     * 不直接写入 AudioTrack——交给调用方按需裁剪/写入。
     */
    private fun decodeIntoBytes(decoder: MediaCodec, extractor: MediaExtractor): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        decodeIntoCollect(decoder, extractor) { bytes -> out.write(bytes) }
        return out.toByteArray()
    }

    private fun decodeIntoCollect(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        onPcm: (ByteArray) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone && !stopped.get()) {
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)
                    val size = input?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val status = decoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    logcat(LogPriority.DEBUG) { "[TtsPlayer] decoder output format: ${decoder.outputFormat}" }
                else -> if (status >= 0) {
                    val isEndOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val pcm = decoder.getOutputBuffer(status)
                        if (pcm != null) {
                            val bytes = ByteArray(info.size)
                            pcm.position(info.offset)
                            pcm.get(bytes, 0, info.size)
                            onPcm(bytes)
                        }
                    }
                    decoder.releaseOutputBuffer(status, false)
                    if (isEndOfStream) outputDone = true
                }
            }
        }
    }

    /**
     * 写入 PCM 到 AudioTrack。
     *
     * ⚠️ 用 [AudioTrack.WRITE_NON_BLOCKING] 轮询，**不要**用阻塞式 `write`。
     *
     * 阻塞式 `write` 在缓冲满时挂进内核等待，只有**另一个线程**调 `pause()`/`flush()`
     * 才能唤醒它。一旦服务端 track 出问题（设备切换、flush 后未重新挂载、被系统回收），
     * 它会**永久挂死**，而 `writePcm` 没有超时也感知不到 [stopped]/[released]：
     *
     *   挂死 → `pumpClip` 永不返回 → `awaitDrained()` 的 [DrainBarrier] 永不被消费
     *        → `playback complete` 不打印 → `notifyChapterCompleted()` 不触发
     *        → **自动续章失效 + 服务静默停住**
     *
     * 真机证据：`koharia-tts-player` 线程 utime/stime 5 秒零增长（完全阻塞，非空转），
     * `dumpsys audio` 显示该 track `state:started` 却不再排空。
     *
     * 非阻塞轮询让这里能感知 [stopped]/[released] 并主动退出，不会把播放协程拖死。
     */
    private fun writePcm(track: AudioTrack, bytes: ByteArray): Int {
        var written = 0
        var idleRounds = 0
        while (written < bytes.size && !stopped.get() && !released) {
            val result = runCatching {
                track.write(bytes, written, bytes.size - written, AudioTrack.WRITE_NON_BLOCKING)
            }.getOrDefault(-1)
            if (result < 0) {
                logcat(LogPriority.WARN) {
                    "[TtsPlayer] writePcm aborted result=$result written=$written/${bytes.size}"
                }
                break
            }
            if (result == 0) {
                // 缓冲已满，等音频排空。只在进入等待时打一条，避免刷屏。
                if (idleRounds == 0) {
                    logcat(LogPriority.DEBUG) {
                        "[TtsPlayer] writePcm buffer full, draining: written=$written/${bytes.size} " +
                            "headMs=${playbackHeadMs()} framesWritten=${framesWritten.get()}"
                    }
                }
                idleRounds++
                runCatching { Thread.sleep(WRITE_POLL_MS) }
                continue
            }
            idleRounds = 0
            written += result
        }
        framesWritten.addAndGet(written.toLong() / BYTES_PER_FRAME)
        return written
    }

    /**
     * 诊断用：AudioTrack 播放头已播出的毫秒数（-1 = 不可用）。
     *
     * 配合 `clip start idx=…` 日志，可实测"高亮推进"与"实际发声"的偏差：
     * 若 `headMs` 长期显著落后于已播句子的累计时长，说明高亮被推迟了。
     */
    private fun playbackHeadMs(): Long {
        val track = trackRef.get() ?: return -1
        val rate = currentSampleRate
        if (rate <= 0) return -1
        return runCatching { track.playbackHeadPosition.toLong() * 1000L / rate }.getOrDefault(-1)
    }

    /** 写入 interSentenceGapMs 毫秒静音，维持句间自然停顿（不影响解码流式性）。 */
    private fun writeGap(track: AudioTrack) {
        // Phase 3 step 2：track 开启 time-stretch 后，输出时长 = 输入时长 / speed。
        // 若原样写 interSentenceGapMs，0.5x 时停顿会拉长一倍、2x 时缩短一半；
        // 这里按 speed 反向放大输入静音，使**听感上的句间停顿恒为 interSentenceGapMs**。
        val gapMs = interSentenceGapMs * speed
        val silenceBytes =
            (currentSampleRate * currentChannels * SAMPLE_BYTES_PER_CHANNEL * gapMs / 1000f).toInt()
        if (silenceBytes <= 0) return
        writePcm(track, ByteArray(silenceBytes))
    }

    /** 跨句复用同一条 AudioTrack；采样率/声道变化时重建（MiMo 输出恒定 24k mono，实际不触发）。 */
    private fun ensureTrack(format: MediaFormat): AudioTrack? {
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
            ?: DEFAULT_SAMPLE_RATE
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull() ?: 1
        val existing = trackRef.get()
        if (existing != null && currentSampleRate == sampleRate && currentChannels == channels) {
            return existing
        }
        val channelMask = if (channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            logcat(LogPriority.ERROR) { "[TtsPlayer] bad minBufferSize=$minBuffer rate=$sampleRate" }
            return null
        }
        releaseTrack()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    minBuffer * BUFFER_SIZE_MULTIPLIER,
                    timeStretchMinBufferBytes(sampleRate, channels),
                ),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.play()
                // 先把新 track 发布到 trackRef，**再**套用音量。反过来的话，并发的
                // [setVolume]（duck）会落在旧的/空的 trackRef 上而什么都不做，
                // 新 track 就停在 1.0，而字段已经是压低后的值 —— duck 丢失且无人再触发。
                trackRef.set(it)
                // 循环到稳定：setVolume() 可能在"读字段 → 写 track"之间再次改变字段。
                // 音量只在 duck/restore 之间跳变，最多几轮即收敛。
                for (round in 0 until VOLUME_APPLY_ROUNDS) {
                    val target = volume
                    runCatching { it.setVolume(target) }
                    if (volume == target) break
                }
                currentSampleRate = sampleRate
                currentChannels = channels
                framesWritten.set(0L)
                // Phase 3 step 2：track 进入 PLAYING 后才能设 playback params（time-stretch）。
                applyPlaybackParams(it)
                logcat(LogPriority.INFO) {
                    "[TtsPlayer] track ready rate=$sampleRate channels=$channels speed=${speed}x"
                }
            }
    }

    /**
     * Phase 3 step 2：time-stretch 要求 AudioTrack 缓冲足够大（AOSP 需容纳数个 pitch 周期），
     * 否则 [AudioTrack.setPlaybackParams] 会返回错误。取 [minBuffer] 倍数与
     * ~[TIME_STRETCH_BUFFER_MS] 毫秒音频两者中较大者。
     */
    private fun timeStretchMinBufferBytes(sampleRate: Int, channels: Int): Int =
        (sampleRate.toLong() * channels * SAMPLE_BYTES_PER_CHANNEL * TIME_STRETCH_BUFFER_MS / 1000L).toInt()

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2L
        const val BUFFER_SIZE_MULTIPLIER = 2

        /** Phase 3 step 2：语速范围（与 [koharia.tts.TtsPreferences] 的取值域对齐）。 */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** time-stretch 所需的最小 AudioTrack 缓冲时长（毫秒）。 */
        const val TIME_STRETCH_BUFFER_MS = 400L
        const val DRAIN_POLL_MS = 40L
        const val WORKER_JOIN_MS = 1_000L
        const val QUEUE_CAPACITY = 8
        const val PAUSE_POLL_MS = 100L
        const val ENQUEUE_POLL_MS = 200L

        /** [writePcm] 非阻塞轮询间隔：缓冲满时的等待粒度。 */
        const val WRITE_POLL_MS = 10L

        /**
         * [awaitDrained] 第三阶段：播放头连续静止多少个 [DRAIN_POLL_MS] 就判定"已放完"。
         * 25 × 40ms = 1s —— 正常流式播放时播放头每 ~40ms 前进一次，静止 1s 即音频确已结束。
         */
        const val DRAIN_STALL_ROUNDS = 25

        /** [awaitDrained] 第三阶段整体上限：500 × 40ms = 20s，兜底防止缓慢前进导致的超长等待。 */
        const val DRAIN_MAX_ROUNDS = 500

        /**
         * [ensureTrack] 里套用当前音量的最大轮数。
         * 音量只在 duck / restore 之间跳变，几轮即收敛；这是防跑飞的兜底上限。
         */
        const val VOLUME_APPLY_ROUNDS = 4
        const val SAMPLE_BYTES_PER_CHANNEL = 2L
        const val DEFAULT_GAP_MS = 350L
        const val DEFAULT_ENCODER_DELAY_SAMPLES = 576
        const val DEFAULT_ENCODER_PADDING_SAMPLES = 768
        val POISON = Any()
    }
}

/**
 * 从 [pcm] 中裁掉前缀 [delaySamples] 个采样和后缀 [paddingSamples] 个采样。
 *
 * MiMo 引擎的 TTS MP3 帧头报告 encoder-delay / encoder-padding 是 c2.android.mp3.decoder
 * 的产物；decoder 不会自动剥掉，结果是每句开头/结尾各 ~24ms / ~38ms 静音。
 * Per-clip PCM 装配时在这里手工裁掉，配合 350ms 句间静音，听感更紧。
 *
 * 行为约定：
 * - 输入 PCM 长度必须为 `frameBytes = 2 (16-bit) * channels` 的整数倍（MediaCodec 输出恒满足）
 * - 若 PCM 总采样数 `<= delay + padding`：返回原 PCM，不强制裁剪（避免吞掉整句）
 * - delay 或 padding 为 0/负：返回原 PCM
 * - 单声道 / 立体声统一处理（采样数按帧计，跨声道对称裁剪）
 *
 * 为方便单测，提到顶层 internal 可见。
 */
internal fun trimGaplessPcm(
    pcm: ByteArray,
    channels: Int,
    delaySamples: Int,
    paddingSamples: Int,
): ByteArray {
    if (pcm.isEmpty()) return pcm
    val safeChannels = channels.coerceAtLeast(1)
    val frameBytes = SAMPLE_BYTES_PER_CHANNEL_INT * safeChannels
    if (pcm.size % frameBytes != 0) {
        // 异常输入：非整帧边界，原样返回避免错位
        return pcm
    }
    if (delaySamples <= 0 && paddingSamples <= 0) return pcm
    val totalFrames = pcm.size / frameBytes
    val keepFromFrame = delaySamples.coerceAtLeast(0)
    val keepToFrame = (totalFrames - paddingSamples.coerceAtLeast(0)).coerceAtLeast(keepFromFrame)
    if (keepToFrame <= keepFromFrame) {
        // 整段被裁光，返回原 PCM 兜底（听感是"这一句拖点气"总比"无声"好）
        return pcm
    }
    val fromByte = keepFromFrame * frameBytes
    val toByte = keepToFrame * frameBytes
    return pcm.copyOfRange(fromByte, toByte)
}

private const val SAMPLE_BYTES_PER_CHANNEL_INT = 2
