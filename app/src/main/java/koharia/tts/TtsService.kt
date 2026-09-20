package koharia.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import koharia.tts.player.TtsPlayer
import koharia.tts.progress.TtsProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import androidx.media.app.NotificationCompat as MediaAppNotificationCompat

/**
 * Foreground service that plays TTS for the current EPUB chapter.
 *
 * Phase 1b/1c:
 * - receives chapter params via [Intent] extras
 * - extracts chapter text via [ChapterTextExtractor] (falls back to test text)
 * - segments into sentences via [SentenceSegmenter]
 * - locates the playback start with [StartIndexResolver] (viewport text offset → anchor → progress)
 * - synthesizes ahead through [SentencePrefetcher] (cache-first, 5 in flight)
 * - plays back-to-back via [TtsPlayer]'s single MediaCodec→AudioTrack stream
 * - shows a notification with a "停止" action
 *
 * Phase 2: sentence highlighting broadcast via [koharia.tts.progress.TtsProgressNotifier].
 *
 * Phase 2.5b: 句子级持久化（关闭 app 重开 → 从上次朗读到的句子继续）：
 * - 启动时从 [TtsProgressRepository] 读取持久化下标，作为 [StartIndexResolver] 的低优先级锚
 * - 朗读中每句触发 debounced 写入（750ms），停止 / 销毁时 flush 最终下标
 * - 写失败不抛 — Repository 内部 try/catch + logcat
 */
class TtsService : Service(), CoroutineScope {

    // 必须是稳定 val —— 用 `get() = ...` 每次访问都创建新的 SupervisorJob，
    // launch() 内部多次访问 coroutineContext 时会拿到不同 parent，children 找不到
    // 共同祖先，被 cancel 时部分 child 可能不响应（旧 bug 现象之一）。
    private val serviceJob = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + serviceJob

    /**
     * 播放专用 scope：**与 Service 生命周期解耦**。
     *
     * 旧实现播放线程用 `Thread { runBlocking { ... } }`,锁屏 / 系统回收时裸线程会
     * 被冻结或带走;Service scope 在 onDestroy 时整体取消,后台播放也无法维持。
     * 这里单独建一个 [SupervisorJob] + [Dispatchers.IO] 的 scope,跑播放循环,
     * Service onDestroy 时主动 [cancel] 兜底。
     */
    private val playbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TtsPlayback"),
    )

    /**
     * Phase 3 step 1 修复(3)：**播放控制操作串行化**。
     *
     * 真机症状：系统媒体控件(MediaSession)的 prev/next 连点 → 多个
     * [MediaSessionCallback] 并发调用 [skipTo]；而 [startPlayback] 里
     * `playbackJob?.cancel()` + 重新赋值**不是原子的**，会孤儿化一个正在运行的 job。
     * Logcat 铁证：多个 `runPlaybackJob ENTER` 同时 enqueue 不同下标
     * (`enqueued 15/16/17/6/18/3` 交错，且一个 36 秒前的旧 job 到此刻才被 cancel)，
     * skipTo 的目标(13)反而没生效，播放的还是旧队列。
     *
     * 用 [Mutex] 把 start/pause/resume/skip/stop 串成一条队列：`cancel+join+重启`
     * 原子完成，杜绝孤儿 job。
     */
    private val controlMutex = Mutex()

    /**
     * 把一次播放控制操作投递到串行队列。所有会改 `playbackJob`/`prefetcher`/`player`
     * 状态的操作都必须经此入口，否则会与其它控制操作竞态。
     */
    private fun dispatchControl(label: String, block: suspend () -> Unit) {
        playbackScope.launch {
            controlMutex.withLock {
                logcat(LogPriority.INFO) { "[TtsService] control: $label" }
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logcat(LogPriority.ERROR, t) { "[TtsService] control $label failed" }
                }
            }
        }
    }

    private val cache: TtsCache by lazy { Injekt.get() }

    /**
     * 进程内章节正文暂存。调用方 `put(token, text)` 后**只把 token 放进 Intent**，
     * 避免整章正文经 Binder 序列化（大单文件 EPUB 会 TransactionTooLargeException）。
     */
    private val chapterTextStore: TtsChapterTextStore by lazy { Injekt.get() }

    private val extractor: koharia.tts.reader.ChapterTextExtractor by lazy { Injekt.get() }

    /**
     * 当前播放会话代次（review ocr finding D）。
     *
     * 由 [TtsService.startPlayback] 取自 [TtsPlayer.startNewSession] 的返回值；代次随
     * 每个播放器回调一并回传，Service 侧据此丢弃"迟到的旧会话回调" —— 播放器内部的
     * `generation.isValid()` 无法覆盖"worker 通过检查后被抢占、主线程已完成会话切换与计数
     * 归零"的窗口。`-1` 表示没有活动会话（停止后所有回调都作废）。
     */
    @Volatile
    private var activeSessionGeneration: Int = -1

    private val player: TtsPlayer by lazy {
        TtsPlayer(
            tempDir = File(cacheDir, "tts_pump"),
            // 高亮 / 进度：worker 取到 clip 就回调（保证不滞后）。
            onClipStarted = ::onSentencePlaybackStarted,
            // "真的发声了"：写出非空 PCM 才回调 —— 零声音判定只认这个（review P1）。
            onClipAudioProduced = ::onSentenceAudioProduced,
            // "合成成功但播放失败"：损坏 MP3 / 无 PCM 时回调 —— 章节完成判据必须涵盖
            // 这一类，否则只要另有一句成功就会误判整章播完并跳章（review P1 round 3）。
            onClipFailed = ::onSentencePlaybackFailed,
        )
    }

    /** 回调的会话代次是否仍是当前会话（迟到回调一律丢弃，review ocr finding D）。 */
    private fun isCurrentSession(generation: Int): Boolean = generation == activeSessionGeneration

    private var playbackJob: Job? = null
    private var prefetcher: SentencePrefetcher? = null

    /**
     * 句子级进度广播（Phase 2）。通过 Injekt 拿同一实例，让阅读器 ViewModel
     * 也能读到当前正在朗读的句下标。
     */
    private val progressNotifier: koharia.tts.progress.TtsProgressNotifier = Injekt.get()

    /**
     * 句子进度持久化（Phase 2.5b）。失败容忍：读写异常由 Repository 内部吞掉，
     * 主流程不会被 DB 错误打断。
     */
    private val progressRepository: TtsProgressRepository = Injekt.get()

    /** Phase 3 step 2：朗读语速偏好（全局、未作用域）。 */
    private val ttsPreferences: TtsPreferences by lazy { Injekt.get() }

    /**
     * Phase 4:安全 prefs 单例,存 API key 与 base URL 覆盖。
     *
     * 用闭包传给 [TtsVendor.createEngine] 的 apiKeyLookup / baseUrlLookup,引擎每次
     * [TtsEngine.synthesize] 前重新读取,实现"用户在设置页改 key 后正在飞的 playback
     * 下一句就用新 key"的效果。
     */
    private val securePreferences: TtsSecurePreferences by lazy { Injekt.get() }

    /**
     * Phase 4:当前 TTS 引擎。**[observeVendorPreference] 在 vendorId 变化时重建**;
     * key / baseUrl 变化不重建(provider lambda 每次重新读)。
     *
     * [engine] 是只读 getter,内部代码照常引用 `engine.xxx`,不必感知重建。
     *
     * [lateinit] 因为 [createEngine] 需要 [ttsPreferences]/[securePreferences](都在
     * Service 构造后才经 Injekt 解析)。`@Volatile` 保证跨线程可见性。
     */
    @Volatile
    private lateinit var currentEngine: TtsEngine
    private val engine: TtsEngine get() = currentEngine

    private fun createEngine(): TtsEngine {
        val vendorId = ttsPreferences.vendorId.get()
        return TtsVendor.fromId(vendorId).createEngine(
            apiKeyLookup = { securePreferences.getApiKey(vendorId) },
            baseUrlLookup = { securePreferences.getBaseUrl(vendorId) },
        )
    }

    private fun rebuildEngine(newEngine: TtsEngine) {
        val oldId = currentEngine.engineId
        currentEngine = newEngine
        logcat(LogPriority.INFO) { "[TtsService] engine swapped: $oldId -> ${newEngine.engineId}" }
    }

    // ===== Phase 2.5b 持久化状态 =====
    private var currentChapterId: Long = -1L
    private var currentMangaId: Long = -1L

    /** 当前**正在发声**的句下标。由 [onSentencePlaybackStarted]（播放器工作线程）写入，故 @Volatile。 */
    @Volatile
    private var currentSentenceIndex: Int = -1

    /**
     * 已**确认发声**（[onSentenceAudioProduced] 触发）的最大句下标。
     *
     * 与 [currentSentenceIndex] 分离的动机（review P2）：
     *  - `onSentencePlaybackStarted` 在 clip 被取到时就触发（早于解码），用来驱动 UI 高亮是
     *    必要的（不然高亮比音频晚整整一句）；但它**不能**作为"已持久化"的下标——解码失败的
     *    句子（厂商返回错误正文 / MP3 损坏）会把持久化进度推进到无声音的位置。
     *  - 因此进度持久化只信任真正"写出非空 PCM"后的 [onSentenceAudioProduced] 回调，写入
     *    [confirmedSentenceIndex]；`flushProgress` 持久化这个值。
     */
    @Volatile
    private var confirmedSentenceIndex: Int = -1
    private var progressSaveJob: Job? = null

    /**
     * 本次播放**真正写出音频**过的句数。由 [onSentenceAudioProduced]（播放器工作线程）自增，
     * 故 @Volatile；每轮 [startPlayback] 重置。
     *
     * ⚠️ 不能改用 [onSentencePlaybackStarted] 计数：那个回调在 clip **被取到**时就触发，
     * 此时还没解码 —— 厂商返回 HTTP 200 但正文不是音频、或 MP3 损坏时，该句零声音却仍会被计上。
     *
     * 用途：区分"整章播完"与"每句合成都失败"（无效 API key / 断网 / 限流 / 超长句）。
     * 后者若仍发 [TtsProgressNotifier.notifyChapterCompleted]，阅读器就会在**没有任何声音**
     * 的情况下连续跳章（review P1）。
     *
     * review (ocr) 用 [AtomicInteger] 而非 `@Volatile Int`：本字段被**两个线程**写 ——
     * 播放循环（合成返回 null）与播放器 worker（[onSentenceAudioProduced] / 新一轮重置），
     * `@Volatile` 只保证可见性，不保证 read-modify-write 原子性；丢一次自增就会让
     * [classifyTtsPlayback] 误判 COMPLETE 并跳章。
     */
    private val playedSentenceCount = AtomicInteger(0)

    /**
     * 本轮播放中**失败**的句数。两类都计入：
     *  - 合成失败（`prefetcher.await` 返回 null，播放循环内自增）；
     *  - 合成成功但播放失败（损坏 MP3 / 无 PCM，[onSentencePlaybackFailed] 在 worker 线程自增）。
     *
     * review P1：第一句成功、后续全部失败时，旧逻辑只检查 "零声音"，会把"部分失败"当成
     * "播完"去跳章。改用 [classifyTtsPlayback] 严格判定。
     *
     * 同样是**多线程写**（播放循环 + 播放器 worker），故用 [AtomicInteger]（见
     * [playedSentenceCount] 的说明）。
     */
    private val failedSentenceCount = AtomicInteger(0)

    /** 音频焦点（review P2）：与其他媒体互斥、正确响应电话等焦点变化。 */
    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    /**
     * 当前持有的音频焦点请求。写在焦点回调线程 / IO 控制队列，读在主线程
     * （[onDestroy] / [abandonAudioFocus]），故 @Volatile —— 否则 onDestroy 可能读到
     * 陈旧的 null 而漏掉 `abandonAudioFocusRequest`，造成焦点泄漏。
     */
    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * TRANSIENT 失焦后是否需要在 GAIN 时自动恢复播放。
     * 同样跨线程（焦点回调 ↔ 控制队列），故 @Volatile。
     */
    @Volatile
    private var resumeOnAudioFocusGain: Boolean = false

    // ===== Phase 3 step 1：MediaSession + 状态机 =====

    /**
     * 本次章节播放参数快照，用于 [skipTo] 复用同一章节上下文重新启动 [playbackJob]。
     * `null` 表示还没收到有效的播放启动 Intent（不应调用 skipTo）。
     */
    private var lastPlaybackArgs: ChapterPlaybackArgs? = null

    /** skipTo 时使用的 overrideStartIndex（-1 = 不 override，让 StartIndexResolver 解析）。 */
    private var overrideStartIndex: Int = -1

    /**
     * 自然播完后的兜底自毁任务（自动续播）。
     *
     * 章节播完时不立即 `clear()+stopSelf()`，而是给阅读器 [AUTO_ADVANCE_GRACE_MS] 的窗口
     * 用一条新的 start 命令接管（[startPlayback] 会取消本任务）。若无人接管则自毁。
     * 这样避免"停服 → 重开服"造成的通知闪烁与 [onDestroy] 清理竞态。
     */
    private var completionStopJob: Job? = null

    /**
     * 实例视图 = companion [_playbackState]（进程级）。这是 getter/setter 代理 —
     * 让原有 `playbackUiState` 读写代码 (`resume`/`pause`/`buildNotification` 等)
     * **保持不变**，实际数据搬到 companion 供阅读器观察。
     */
    private var playbackUiState: TtsPlaybackState
        get() = _playbackState.value
        set(value) {
            _playbackState.value = value
        }

    private var mediaSession: MediaSessionCompat? = null

    /**
     * 单条参数快照。比 Intent extras 更易读,字段语义清晰;新增参数只需扩列,无需 clone Intent。
     */
    private data class ChapterPlaybackArgs(
        val chapterId: Long,
        val mangaId: Long,
        val href: String,
        val progression: Double,
        val textAnchor: String?,
        val anchorIsBefore: Boolean,
        val startOffset: Int,
        val persistedSentenceText: String?,
        val extractedText: String?,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        initMediaSession()
        // Phase 4:必须在 observeXxxPreference 之前构造引擎,observeVendorPreference
        // 才会读到一个稳定的 currentEngine。
        currentEngine = createEngine()
        observeSpeedPreference()
        observeVoicePreference()
        observeVendorPreference()
    }

    /**
     * Phase 3 step 2：监听朗读语速偏好并实时作用到 [player]。
     *
     * `changes()` 会先发一次当前值（`onStart`），所以无需在 [startPlayback] 里再读一次；
     * 用户拖动设置页滑块时会即时生效（由 AudioTrack 的 time-stretch 无缝接管）。
     * 收集器跑在 serviceScope：Service 销毁时一起取消。
     */
    private fun observeSpeedPreference() {
        launch {
            ttsPreferences.speedTenths.changes().collect { tenths ->
                val speed = tenths.coerceIn(
                    TtsPreferences.MIN_SPEED_TENTHS,
                    TtsPreferences.MAX_SPEED_TENTHS,
                ) / 10f
                logcat(LogPriority.INFO) { "[TtsService] speed preference -> ${speed}x" }
                player.setSpeed(speed)
            }
        }
    }

    /**
     * Phase 3 step 3：监听朗读音色偏好，写入 companion `_voice`。
     *
     * **仅影响下一段播放**：本函数只更新 companion 状态，不重建飞行中的 SentencePrefetcher。
     * 原因：[SentencePrefetcher] 的缓存键含 voice，改变会令已合成音频失效。
     * 下次 `startPlayback` 读 `_voice.value` 构造新 prefetcher，新音色生效。
     *
     * v0.4.2-63：音色改为 **per-vendor** 存储（[TtsPreferences.voiceIdFor]），因此这里用
     * `flatMapLatest` 跟随 `vendorId` —— 切 vendor 时自动改订阅新 vendor 的槽位。
     * `AndroidPreference.changes()` 的 `onStart { emit("ignition") }` 保证**重订阅后立刻
     * 吐出该 vendor 的当前值**，不会残留上一个 vendor 的非法 id。
     *
     * 非法值仍回落到**该 vendor 的** [TtsVendor.defaultVoiceId]（不再写死冰糖）：
     * 防手改 prefs XML / 跨版本迁移异常。
     */
    private fun observeVoicePreference() {
        launch {
            ttsPreferences.vendorId.changes()
                .flatMapLatest { vendorId -> ttsPreferences.voiceIdFor(vendorId).changes() }
                .collect { id ->
                    val currentVendorNow = TtsVendor.fromId(ttsPreferences.vendorId.get())
                    val validIdsNow = currentVendorNow.presetVoices().map { it.id }.toSet()
                    val valid = if (id in validIdsNow) {
                        id
                    } else {
                        logcat(LogPriority.WARN) {
                            "[TtsService] unknown voice id '$id' for vendor '${currentVendorNow.id}', " +
                                "fallback to ${currentVendorNow.defaultVoiceId()}"
                        }
                        currentVendorNow.defaultVoiceId()
                    }
                    if (valid != _voice.value) {
                        logcat(LogPriority.INFO) {
                            "[TtsService] voice preference -> $valid (vendor=${currentVendorNow.id})"
                        }
                        _voice.value = valid
                    }
                }
        }
    }

    /**
     * Phase 4:监听 vendorId 变化,重建引擎。
     *
     * 设计要点:
     *  - vendorId 改变 → 立即重建(引擎类型变化,MimoEngine vs EdgeEngine);
     *  - vendor 的 API key 改变 → **不重建**(MimoEngine.apiKeyProvider 是 lambda,
     *    下次 synthesize 重新读 securePrefs);
     *  - 新引擎未配置(用户切到 MiMo 但还没填 key)→ **保留旧引擎**,只 WARN 日志;
     *  - v0.4.2-63:**不再重置 voice** —— 音色已按 vendor 分槽存储
     *    ([TtsPreferences.voiceIdFor]),每个槽位要么是用户自己选过的合法值,要么未设置
     *    (落到该 vendor 的 [TtsVendor.defaultVoiceId]),天然合法。
     *    旧实现的"非法则 reset 成 default"在新结构下等于把默认值写进空槽,是空操作,
     *    还会误导读者以为必须 reset。`_voice` 的更新由 [observeVoicePreference] 负责。
     */
    private fun observeVendorPreference() {
        launch {
            ttsPreferences.vendorId.changes().collect { newVendorId ->
                val newVendor = TtsVendor.fromId(newVendorId)
                val newEngine = newVendor.createEngine(
                    apiKeyLookup = { securePreferences.getApiKey(newVendorId) },
                    baseUrlLookup = { securePreferences.getBaseUrl(newVendorId) },
                )
                if (newEngine.isConfigured()) {
                    rebuildEngine(newEngine)
                } else {
                    logcat(LogPriority.WARN) {
                        "[TtsService] vendor '${newVendor.displayName}' not configured " +
                            "(missing API key?), keeping ${currentEngine.engineId}"
                    }
                }
            }
        }
    }

    /**
     * Phase 3 step 1：注册 MediaSessionCompat,绑定 [MediaSessionCallback]。
     *
     * MediaSession 是通知栏锁屏控件 + 蓝牙耳机按键的统一入口：
     * - 锁屏控件自动读取 session token 显示媒体卡片
     * - 蓝牙耳机 KEYCODE_MEDIA_PLAY/PAUSE/NEXT/PREV 通过系统分发到 Callback
     * - 通知栏 MediaStyle.setMediaSession() 也读这个 token
     *
     * 启动时先置 STATE_STOPPED 等第一次 startPlayback 触发后再切到 PLAYING。
     */
    private fun initMediaSession() {
        val session = MediaSessionCompat(this, "KohariaTts").apply {
            setCallback(MediaSessionCallback())
            isActive = true
            setPlaybackState(buildPlaybackState(TtsPlaybackState.STOPPED))
        }
        mediaSession = session
        logcat(LogPriority.INFO) { "[TtsService] MediaSession initialized" }
    }

    private fun buildPlaybackState(state: TtsPlaybackState): PlaybackStateCompat {
        val position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        val playbackActions = when (state) {
            TtsPlaybackState.PLAYING ->
                PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            TtsPlaybackState.PAUSED ->
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            TtsPlaybackState.STOPPED ->
                PlaybackStateCompat.ACTION_STOP
        }
        val playbackState = when (state) {
            TtsPlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TtsPlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TtsPlaybackState.STOPPED -> PlaybackStateCompat.STATE_STOPPED
        }
        return PlaybackStateCompat.Builder()
            .setActions(playbackActions)
            .setState(playbackState, position, 1.0f)
            .build()
    }

    /**
     * 同步播放状态:更新内部 [playbackUiState],推送 PlaybackStateCompat 给锁屏/蓝牙,
     * 重建通知栏。状态变更的所有路径(暂停/恢复/跳句/停止/启动)都走这里。
     */
    private fun updateTtsPlaybackState(newState: TtsPlaybackState) {
        playbackUiState = newState
        mediaSession?.setPlaybackState(buildPlaybackState(newState))
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
        logcat(LogPriority.INFO) { "[TtsService] playbackUiState -> $newState" }
    }

    // ===== Phase 5 (PR review): 音频焦点 =====

    /**
     * 失焦回调。所有会改播放状态的分支都经 [dispatchControl] 投递到串行控制队列，
     * 保证 `playbackUiState` / 通知 / MediaSession 三者同步（与 [MediaSessionCallback] 同一模式）。
     *
     * - [AudioManager.AUDIOFOCUS_LOSS]：永久失焦 → 暂停且**不**自动恢复；
     * - [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT]：暂时失焦 → 暂停，GAIN 后自动恢复；
     * - [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK]：允许压低 → 降音量，不暂停；
     * - [AudioManager.AUDIOFOCUS_GAIN]：恢复音量，必要时恢复播放。
     */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                logcat(LogPriority.INFO) { "[TtsService] audio focus LOSS" }
                resumeOnAudioFocusGain = false
                dispatchControl("focus-loss") { pauseIfPlaying() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                logcat(LogPriority.INFO) { "[TtsService] audio focus LOSS_TRANSIENT" }
                resumeOnAudioFocusGain = true
                dispatchControl("focus-loss-transient") { pauseIfPlaying() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                logcat(LogPriority.INFO) { "[TtsService] audio focus CAN_DUCK" }
                player.setVolume(DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                logcat(LogPriority.INFO) { "[TtsService] audio focus GAIN" }
                player.setVolume(1.0f)
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false
                    dispatchControl("focus-gain") { resume() }
                }
            }
        }
    }

    /** 仅在 PLAYING 时暂停；跑在串行控制队列里（避免与其它控制操作竞态）。 */
    private fun pauseIfPlaying() {
        if (playbackUiState == TtsPlaybackState.PLAYING) {
            pause()
        }
    }

    /** 申请音频焦点（USAGE_MEDIA / CONTENT_TYPE_SPEECH，AUDIOFOCUS_GAIN）。 */
    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // 自己处理 duck，不交给系统直接暂停（否则恢复时机不可控）。
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        logcat(LogPriority.INFO) { "[TtsService] requestAudioFocus result=$result" }
    }

    /** 释放音频焦点（停止 / 销毁时调用）。 */
    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        audioFocusRequest = null
        resumeOnAudioFocusGain = false
    }

    /**
     * 启动前台服务。
     *
     * review P1：targetSdk 36 下必须用 [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK]。
     * 之前声明的 dataSync 会占用真正的数据同步额度，并受 Android 15+ 后台 6 小时配额限制。
     * 三参 [startForeground] 重载自 API 29 起可用，故低版本回退到两参重载。
     */
    private fun startForegroundForPlayback(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logcat(LogPriority.INFO) { "[TtsService] onStartCommand action=${intent?.action}" }
        when (intent?.action) {
            ACTION_STOP -> {
                dispatchControl("stop") {
                    stopPlayback()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                // MediaSession / 通知 / 蓝牙 play 键
                dispatchControl("play") { resume() }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                dispatchControl("pause") { pause() }
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                dispatchControl("next") { skipTo(currentSentenceIndex + 1) }
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                dispatchControl("prev") { skipTo((currentSentenceIndex - 1).coerceAtLeast(0)) }
                return START_NOT_STICKY
            }
            else -> {
                startForegroundForPlayback(buildNotification())
                val chapterId = intent?.getLongExtra(EXTRA_CHAPTER_ID, -1L) ?: -1L
                val mangaId = intent?.getLongExtra(EXTRA_MANGA_ID, -1L) ?: -1L
                val href = intent?.getStringExtra(EXTRA_HREF).orEmpty()
                val progression = intent?.getDoubleExtra(EXTRA_PROGRESSION, 0.0) ?: 0.0
                val textAnchor = intent?.getStringExtra(EXTRA_TEXT_ANCHOR)
                val anchorIsBefore = intent?.getBooleanExtra(EXTRA_ANCHOR_BEFORE, false) ?: false
                val startOffset = intent?.getIntExtra(EXTRA_START_OFFSET, -1) ?: -1
                val persistedSentenceText = intent?.getStringExtra(EXTRA_PERSISTED_SENTENCE_TEXT)
                // 正文经进程内 [TtsChapterTextStore] 传递：Intent 里只放短 token，
                // 这里用 token 一次性取回正文（取出即移除）。
                val extractedText = intent?.getStringExtra(EXTRA_TEXT_TOKEN)?.let(chapterTextStore::take)
                dispatchControl("start") {
                    startPlayback(
                        chapterId = chapterId,
                        mangaId = mangaId,
                        href = href,
                        progression = progression,
                        textAnchor = textAnchor,
                        anchorIsBefore = anchorIsBefore,
                        startOffset = startOffset,
                        persistedSentenceText = persistedSentenceText,
                        extractedText = extractedText,
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 启动一轮播放。**必须经 [dispatchControl] 串行调用**——内部 `cancelAndJoin` + 重新
     * 赋值 `playbackJob` 依赖互斥，否则并发时会孤儿化旧 job。
     */
    private suspend fun startPlayback(
        chapterId: Long,
        mangaId: Long,
        href: String,
        progression: Double,
        textAnchor: String?,
        anchorIsBefore: Boolean,
        startOffset: Int,
        persistedSentenceText: String?,
        extractedText: String? = null,
    ) {
        logcat(LogPriority.INFO) {
            // 只记录标识 / 长度 / 偏移，绝不打印章节正文或正文片段：
            // release 最低日志级别为 INFO，正文会因此进入系统日志。
            "[TtsService] startPlayback chapterId=$chapterId href='$href' " +
                "prog=$progression startOffset=$startOffset anchorPresent=${textAnchor != null} " +
                "anchorBefore=$anchorIsBefore persisted=${persistedSentenceText != null} " +
                "preExtracted=${extractedText != null}(len=${extractedText?.length ?: -1})"
        }
        // 有新的 start 接管（含自动续播）：先取消"自然播完兜底自毁"。
        completionStopJob?.cancel()
        completionStopJob = null
        // cancelAndJoin 而非 cancel：确保旧 job 完全退出后再起新的（串行控制队列下安全）。
        playbackJob?.cancelAndJoin()
        // Phase 3 step 1：记住本次播放参数,供 skipTo 复用
        lastPlaybackArgs = ChapterPlaybackArgs(
            chapterId = chapterId,
            mangaId = mangaId,
            href = href,
            progression = progression,
            textAnchor = textAnchor,
            anchorIsBefore = anchorIsBefore,
            startOffset = startOffset,
            persistedSentenceText = persistedSentenceText,
            extractedText = extractedText,
        )
        // review ocr finding C：换章/重新 start 时**显式**作废播放器上一个会话。
        // startPlayback 只 cancelAndJoin 协程、从不调 player.stop()/skipTo()，所以此刻
        // TtsPlayer 的 `stopped` 仍为 false；若不显式作废，上一章仍在途的 clip 会被当作本
        // 会话有效，把 PCM 写进未 flush 的 AudioTrack，并把旧句回调计入本会话的计数、
        // 甚至以旧下标对新 chapterId 落盘。放在 reset 计数之前，确保旧回调先失效。
        // 记住返回的代次：回调会带同一代次回来，Service 侧做第二道迟到校验（finding D）。
        //
        // review ocr finding 1：**先**把 Service 侧守卫置为"无会话"，再让播放器开新会话。
        // `startNewSession()` 内部先 `invalidate()` 再做若干 AudioTrack binder 调用才返回；
        // 若不先置 -1，一个已通过播放器侧检查的旧 worker 仍会读到**旧**的
        // `activeSessionGeneration` 从而通过 `isCurrentSession`。此时置 -1 不会误伤新会话回调 ——
        // 新会话的 clip 只可能在下方 launch 的 playbackJob 里入队。
        activeSessionGeneration = -1
        val newSessionGeneration = player.startNewSession()
        activeSessionGeneration = newSessionGeneration
        // 新一轮播放：重置持久化状态
        currentChapterId = chapterId
        currentMangaId = mangaId
        currentSentenceIndex = -1
        confirmedSentenceIndex = -1
        playedSentenceCount.set(0)
        failedSentenceCount.set(0)
        cancelProgressSave()
        // 与其他媒体互斥：起播前申请音频焦点（在 stopPlayback / onDestroy 里释放）。
        requestAudioFocus()
        logcat(LogPriority.INFO) { "[TtsService] about to launch playbackJob" }
        // 用 [playbackScope] 而不是 serviceScope：让播放循环独立于 Service 生命周期，
        // 避免后台锁屏时 Service 被系统回收导致 playbackJob 一同被取消。
        // [CoroutineStart.UNDISPATCHED] 强制 block 在调用线程立刻进入 —— 这是
        // Phase 2.5 早期"launch 不调度"症状的针对性防御（当时绕开用 raw Thread + runBlocking），
        // 现在用 UNDISPATCHED 显式保证调度,不再需要裸线程。
        playbackJob = playbackScope.launch(start = CoroutineStart.UNDISPATCHED) {
            logcat(LogPriority.INFO) { "[TtsService] runPlaybackJob ENTER (coroutine start)" }
            try {
                runPlaybackJob(
                    chapterId = chapterId,
                    mangaId = mangaId,
                    href = href,
                    progression = progression,
                    textAnchor = textAnchor,
                    anchorIsBefore = anchorIsBefore,
                    startOffset = startOffset,
                    persistedSentenceText = persistedSentenceText,
                    extractedText = extractedText,
                )
            } catch (e: CancellationException) {
                logcat(LogPriority.INFO) { "[TtsService] runPlaybackJob cancelled (normal stop)" }
            } catch (t: Throwable) {
                logcat(LogPriority.ERROR, t) { "[TtsService] runPlaybackJob CRASHED" }
            } finally {
                logcat(LogPriority.INFO) { "[TtsService] runPlaybackJob EXIT" }
            }
        }
        logcat(LogPriority.INFO) { "[TtsService] playbackJob LAUNCHED (coroutine, was raw Thread)" }
        // 新一轮启动（无论 PLAYING/PAUSED 路径）—— 切到 PLAYING 状态;若 [pause]/[resume]
        // 后续再切。这里假设 startPlayback 一律从 PLAYING 起，pause 由用户后续触发。
        updateTtsPlaybackState(TtsPlaybackState.PLAYING)
    }

    /**
     * 实际的播放流程(从 launch block 提取出来)。先被 raw Thread + runBlocking 包,
     * launch 不挂死时也可以被 launch 复用(只需把外面包装换成 launch 即可)。
     */
    private suspend fun runPlaybackJob(
        chapterId: Long,
        mangaId: Long,
        href: String,
        progression: Double,
        textAnchor: String?,
        anchorIsBefore: Boolean,
        startOffset: Int,
        persistedSentenceText: String?,
        extractedText: String?,
    ) {
        val launchStartMs = System.currentTimeMillis()
        logcat(LogPriority.INFO) {
            "[TtsService] runPlaybackJob START extractedText.len=${extractedText?.length ?: -1} " +
                "isBlank=${extractedText?.isBlank()}"
        }
        try {
            // 优先使用调用方预提取的文本(来自 WebView document.body.innerText),
            // 速度与 JS tree walker 看到的内容一致;缺失时降级到 ChapterTextExtractor。
            val text = if (extractedText != null && extractedText.isNotBlank()) {
                logcat(LogPriority.INFO) {
                    "[TtsService] using pre-extracted text from caller (${extractedText.length} chars) — skip ChapterTextExtractor"
                }
                extractedText
            } else {
                logcat(LogPriority.INFO) { "[TtsService] extractor.extract() START chapterId=$chapterId href='$href'" }
                val t = extractor.extract(chapterId, href)
                logcat(LogPriority.INFO) {
                    "[TtsService] extractor.extract() RETURN text.len=${t?.length ?: "<null>"}"
                }
                t
            }
            if (text == null) {
                logcat(LogPriority.WARN) {
                    "[TtsService] chapter text extraction failed (chapterId=$chapterId, href=\"$href\") — falling back to test text"
                }
                startForegroundForPlayback(buildNotification(extractFailed = true))
            }
            val resolvedText = text ?: FALLBACK_TEXT
            logcat(LogPriority.INFO) { "[TtsService] extracted ${resolvedText.length} chars; sentence-segmenting..." }
            val sentences = SentenceSegmenter.cut(chapterHref = href.ifBlank { "ch-fallback" }, text = resolvedText)
            logcat(LogPriority.INFO) { "[TtsService] ${sentences.size} sentences to play" }

            if (sentences.isEmpty()) {
                logcat(LogPriority.WARN) { "[TtsService] no sentences to play" }
                progressNotifier.bind(href, emptyList())
                stopSelf()
                return
            }

            // 把句子列表广播给阅读器(Phase 2 句子级同步)
            progressNotifier.bind(
                chapterHref = href,
                sentences = sentences.map {
                    koharia.tts.progress.TtsProgressNotifier.SentenceRef(
                        index = it.index,
                        startOffset = it.startOffset,
                        endOffset = it.endOffset,
                    )
                },
            )

            // 起播点决策(DOM 起始偏移 → 视口锚 → 持久化句 → 进度回退)。
            // Phase 3 step 1:skipTo 路径用 [overrideStartIndex] 覆盖,跳过 resolver。
            val startIndex = if (overrideStartIndex in 0 until sentences.size) {
                logcat(LogPriority.INFO) {
                    "[TtsService] startIndex overridden by skipTo -> $overrideStartIndex"
                }
                overrideStartIndex
            } else {
                StartIndexResolver.resolve(
                    sentences = sentences,
                    startOffset = startOffset,
                    textAnchor = textAnchor,
                    anchorIsBefore = anchorIsBefore,
                    progression = progression,
                    textLength = resolvedText.length,
                    persistedSentenceText = persistedSentenceText,
                )
            }
            logcat(LogPriority.INFO) {
                "[TtsService] starting at sentence $startIndex/${sentences.size} " +
                    "(startOffset=$startOffset prog=$progression " +
                    "persisted=${persistedSentenceText != null})"
            }
            progressNotifier.setCurrent(startIndex)
            if (startIndex >= 0) {
                // review P2 round 3:起播时不落盘 —— 此刻还没有任何音频被确认发声，
                // 若首个合成请求在防抖窗口(750ms)内失败，会把未播放的起始句写进持久化。
                // [currentSentenceIndex] 仍需记录，供 skipTo / MediaSession next/prev 作为基址。
                currentSentenceIndex = startIndex
            }

            val prefetcher = SentencePrefetcher(
                scope = playbackScope,
                engine = engine,
                cache = cache,
                voice = _voice.value,
                style = null,
            )
            this@TtsService.prefetcher = prefetcher
            prefetcher.scheduleFrom(startIndex, sentences)

            for ((index, sentence) in sentences.withIndex()) {
                if (index < startIndex) continue
                if (!currentCoroutineContext().isActive) break
                // 诊断：把"要朗读的文本"和"文本模型在该偏移区间的内容"并排打印。
                // 注意这是**入队**日志，不等于已经在发声；真正发声见 `now-playing`。
                logcat(LogPriority.DEBUG) {
                    // 只记录下标 / 偏移 / 长度，正文内容不落日志。
                    "[TtsService] enqueued $index/${sentences.size} " +
                        "[${sentence.startOffset},${sentence.endOffset}) len=${sentence.text.length}"
                }
                val mp3 = prefetcher.await(index, sentence)
                prefetcher.scheduleFrom(index + 1, sentences)
                if (mp3 == null) {
                    // review P1:必须计入失败计数。仅当**所有**尝试过的句都成功发声
                    // (failed == 0) 才视为章节完成。任意一句失败都不能跳章。
                    // 用 incrementAndGet（review ocr）：本计数器与播放器 worker 线程的
                    // [onSentencePlaybackFailed] 并发自增，`@Volatile` 不能防丢更新。
                    val failed = failedSentenceCount.incrementAndGet()
                    logcat(LogPriority.WARN) {
                        "[TtsService] sentence $index synthesis failed " +
                            "(failed=$failed played=${playedSentenceCount.get()}), " +
                            "skipping playback but persisting progress at last confirmed index"
                    }
                    continue
                }
                // 高亮/持久化**不在这里**推进——预取会让入队领先实际发声最多 8 句。
                // 由 TtsPlayer 在音频真正开始写入时回调 onSentencePlaybackStarted。
                player.enqueue(mp3, index)
                if (!currentCoroutineContext().isActive) break
            }
            player.awaitDrained()
            if (!currentCoroutineContext().isActive) return
            flushProgress()
            // flushProgress() 期间也可能被取消（skipTo / stop 在途）：再确认一次，否则下面的
            // "零声音"分支会在新一轮已经接管时发出 playbackFailed + stopSelf()。
            if (!currentCoroutineContext().isActive) return

            // review P1 round 2/3：章节完成判据必须严格 —— **任何**句失败都不能跳章。
            // 失败来源有两类，都必须计入 [failedSentenceCount]：
            //  - 合成返回 null（上面的 for 循环）；
            //  - 合成成功但播放失败（损坏 MP3 / 无 PCM，由 [onSentencePlaybackFailed] 回传）。
            // 判定抽到 [classifyTtsPlayback] 以便单测锁定（含"首句成功后续全失败"回归）。
            val played = playedSentenceCount.get()
            val failed = failedSentenceCount.get()
            when (classifyTtsPlayback(played = played, failed = failed)) {
                TtsPlaybackOutcome.COMPLETE -> Unit // 继续走下面的完成路径
                TtsPlaybackOutcome.NO_AUDIO,
                TtsPlaybackOutcome.PARTIAL_FAILURE,
                -> {
                    logcat(LogPriority.WARN) {
                        "[TtsService] playback incomplete: " +
                            "played=$played failed=$failed " +
                            "of ${sentences.size} sentences; not advancing chapter"
                    }
                    progressNotifier.notifyPlaybackFailed()
                    updateTtsPlaybackState(TtsPlaybackState.STOPPED)
                    stopSelf()
                    return
                }
            }

            logcat(LogPriority.INFO) {
                "[TtsService] playback complete (played=$played/${sentences.size})"
            }
            // Phase 3：通知阅读器"章节自然播完"，让它自动续播下一章。
            // 不自毁 —— 交给 [scheduleCompletionStop] 的兜底窗口，等阅读器用新 start 接管。
            progressNotifier.notifyChapterCompleted()
            scheduleCompletionStop()
        } catch (e: CancellationException) {
            // 取消是正常控制流（stop / skipTo）,不是错误 —— 不要打成 ERROR。
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) {
                "[TtsService] runPlaybackJob CRASHED after ${System.currentTimeMillis() - launchStartMs}ms"
            }
            throw t
        } finally {
            logcat(LogPriority.INFO) {
                "[TtsService] runPlaybackJob EXIT total=${System.currentTimeMillis() - launchStartMs}ms"
            }
        }
    }

    private suspend fun stopPlayback() {
        // 先 flush 持久化进度；现在跑在 [dispatchControl] 的串行协程里，直接 suspend 落盘。
        flushProgress()
        completionStopJob?.cancel()
        completionStopJob = null
        // cancelAndJoin 而非 cancel：等旧 job 真正退出，避免它在新 job 起来后又 enqueue。
        playbackJob?.cancelAndJoin()
        playbackJob = null
        prefetcher?.cancelAll()
        prefetcher = null
        // review ocr finding 2：**先**作废 Service 侧会话再 `player.stop()`。`stop()` 内部会
        // `invalidate()`；若在其后才置 -1，一个已通过播放器侧检查的旧 worker 会读到旧的
        // `activeSessionGeneration` 并通过 `isCurrentSession`，把 `confirmedSentenceIndex` 写回
        // 旧下标并 `scheduleProgressSave()` —— 本路径之后没有任何 `cancelProgressSave()`，
        // 那条防抖写入会真的落盘到已停止的播放上。
        activeSessionGeneration = -1
        player.stop()
        progressNotifier.clear()
        currentSentenceIndex = -1
        confirmedSentenceIndex = -1
        overrideStartIndex = -1
        abandonAudioFocus()
        updateTtsPlaybackState(TtsPlaybackState.STOPPED)
    }

    /**
     * 自然播完后的兜底自毁：给阅读器 [AUTO_ADVANCE_GRACE_MS] 的接管窗口。
     *
     * - 阅读器收到 [TtsProgressNotifier.chapterCompleted] 后会导航下一章并重新
     *   [startPlayback]，届时本任务被取消（通知不闪、Service 不重建）。
     * - 无人接管（无阅读器 / 已是最后一章）时，超时后 `clear()+stopSelf()`，行为与旧版一致。
     */
    private fun scheduleCompletionStop() {
        completionStopJob?.cancel()
        completionStopJob = playbackScope.launch {
            delay(AUTO_ADVANCE_GRACE_MS)
            if (!currentCoroutineContext().isActive) return@launch
            logcat(LogPriority.INFO) {
                "[TtsService] no auto-advance handover in ${AUTO_ADVANCE_GRACE_MS}ms; stopping"
            }
            progressNotifier.clear()
            updateTtsPlaybackState(TtsPlaybackState.STOPPED)
            stopSelf()
        }
    }

    /**
     * Phase 3 step 1：从暂停恢复（通知 / 锁屏 / 蓝牙 play 键 / MediaSession.onPlay）。
     * 幂等；只在 PAUSED 状态生效。
     */
    private fun resume() {
        if (playbackUiState != TtsPlaybackState.PAUSED) {
            logcat(LogPriority.INFO) { "[TtsService] resume() ignored (state=$playbackUiState)" }
            return
        }
        player.play()
        updateTtsPlaybackState(TtsPlaybackState.PLAYING)
    }

    /**
     * Phase 3 step 1：暂停当前播放（通知 / 锁屏 / 蓝牙 pause 键 / MediaSession.onPause）。
     * 幂等；只在 PLAYING 状态生效；保留队列以便 resume 续播。
     */
    private fun pause() {
        if (playbackUiState != TtsPlaybackState.PLAYING) {
            logcat(LogPriority.INFO) { "[TtsService] pause() ignored (state=$playbackUiState)" }
            return
        }
        player.pause()
        updateTtsPlaybackState(TtsPlaybackState.PAUSED)
    }

    /**
     * Phase 3 step 1：跳到指定句子下标。
     *
     * 实现策略：
     * 1. `cancelAndJoin()` OLD playbackJob,等它真正退出 —— 否则 OLD worker
     *    可能在 [prefetcher.await] 之后继续 enqueue,造成 OLD clip 进入 NEW job 的队列,
     *    触发 out-of-order now-playing(真机观察 now-playing 21 在 now-playing 16 之前)
     * 2. [player.skipTo](不是 [player.stop])—— flush 缓冲但**保持 AudioTrack 处于播放状态**,
     *    让 NEW PCM 写入后立即发声。stop() 会 pause track,而 [TtsPlayer.play] 只在 paused 状态
     *    下才调 track.play,导致 NEW PCM 写入 paused track = 静默("没法播放了")
     * 3. 用 [overrideStartIndex] 重启 playbackJob([runPlaybackJob] 入口检测到 override 跳过 [StartIndexResolver])
     *
     * 触发源：通知 prev/next 按钮、锁屏控件、蓝牙耳机 prev/next 键、MediaSession.onSkipTo*。
     */
    private suspend fun skipTo(targetIndex: Int) {
        val args = lastPlaybackArgs
        if (args == null) {
            logcat(LogPriority.WARN) { "[TtsService] skipTo($targetIndex) ignored - no chapter args" }
            return
        }
        val safeIndex = targetIndex.coerceAtLeast(0)
        logcat(LogPriority.INFO) { "[TtsService] skipTo current=$currentSentenceIndex -> target=$safeIndex" }
        // 关键:取消后等真正退出,避免 OLD job 与 NEW job 的 TtsPlayer worker 串扰。
        // 本函数经 [dispatchControl] 串行执行,不会与其它 skipTo/startPlayback 竞态。
        flushProgress()
        playbackJob?.cancelAndJoin()
        playbackJob = null
        prefetcher?.cancelAll()
        prefetcher = null
        // skipTo() 不是 stop():保留 track.play() 状态,让 NEW PCM 立即发声
        player.skipTo()
        overrideStartIndex = safeIndex
        // 用同一章节参数重启,只换 overrideStartIndex
        startPlayback(
            chapterId = args.chapterId,
            mangaId = args.mangaId,
            href = args.href,
            progression = args.progression,
            textAnchor = args.textAnchor,
            anchorIsBefore = args.anchorIsBefore,
            startOffset = args.startOffset,
            persistedSentenceText = args.persistedSentenceText,
            extractedText = args.extractedText,
        )
        // startPlayback 末尾会切到 PLAYING;overrideStartIndex 用完重置回 -1
        overrideStartIndex = -1
    }

    /**
     * MediaSessionCompat.Callback：把系统派发的 5 个媒体事件路由到 [resume]/[pause]/[stopPlayback]/[skipTo]。
     *
     * 回调跑在 binder 线程：所有分支都经 [dispatchControl] 投递到**串行控制队列**，
     * 避免多个回调（尤其系统媒体控件连点 prev/next）并发调用 skipTo/startPlayback
     * 造成 job 孤儿化（Phase 3 step 1 修复(3)）。
     */
    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            logcat(LogPriority.INFO) { "[TtsService] MediaSession.onPlay" }
            dispatchControl("ms-play") { resume() }
        }

        override fun onPause() {
            logcat(LogPriority.INFO) { "[TtsService] MediaSession.onPause" }
            dispatchControl("ms-pause") { pause() }
        }

        override fun onStop() {
            logcat(LogPriority.INFO) { "[TtsService] MediaSession.onStop" }
            dispatchControl("ms-stop") {
                stopPlayback()
                stopSelf()
            }
        }

        override fun onSkipToNext() {
            logcat(LogPriority.INFO) { "[TtsService] MediaSession.onSkipToNext (current=$currentSentenceIndex)" }
            dispatchControl("ms-next") { skipTo(currentSentenceIndex + 1) }
        }

        override fun onSkipToPrevious() {
            logcat(LogPriority.INFO) { "[TtsService] MediaSession.onSkipToPrevious (current=$currentSentenceIndex)" }
            dispatchControl("ms-prev") { skipTo((currentSentenceIndex - 1).coerceAtLeast(0)) }
        }
    }

    /**
     * TtsPlayer 在每句音频**真正开始写入 AudioTrack**（≈开始发声）时回调。
     *
     * 高亮与持久化都必须由实际播放驱动：`SentencePrefetcher` 会让入队领先音频最多
     * [TtsPlayer] 队列容量（8 句），若在入队循环里 `setCurrent`，高亮会跳到音频前面
     * （真机症状：听第 1 句、高亮停在第 10 句）。
     *
     * 运行在播放器工作线程：[progressNotifier] 是 StateFlow（线程安全）；
     * `launch`/`cancel` 线程安全；[currentSentenceIndex] 已 `@Volatile`。
     *
     * [generation] 非当前会话时直接丢弃（review ocr finding D）—— 否则旧会话的 clip 会把
     * 高亮/`currentSentenceIndex` 拉回旧位置。
     */
    private fun onSentencePlaybackStarted(index: Int, generation: Int) {
        if (!isCurrentSession(generation)) return
        logcat(LogPriority.INFO) { "[TtsService] now-playing $index (gen=$generation)" }
        progressNotifier.setCurrent(index)
        currentSentenceIndex = index
        // review P2:此处不调度持久化 —— clip 刚被取到时还没解码，MP3 损坏/厂商返回错误正文
        // 都会让该句零声音；持久化由 [onSentenceAudioProduced] 真正确认发声后驱动。
    }

    /**
     * 播放器回调：某句**确实写出了非空 PCM**（=真的会发声）。
     *
     * 只有这里自增 [playedSentenceCount]。[onSentencePlaybackStarted] 在 clip 被取到时就触发
     * （还没解码），用它计数会把"厂商返回错误正文 / MP3 损坏"的零声音章节判成播完，
     * 进而触发连续跳章（review P1 的故障类别）。
     *
     * [generation] 校验见 [isCurrentSession]：迟到的旧会话回调绝不能抬高新会话的计数、
     * 更不能把旧下标写进新章节的持久化进度（review ocr finding D）。
     *
     * 运行在播放器工作线程；计数器为 [AtomicInteger]（与播放循环并发写）。
     */
    private fun onSentenceAudioProduced(index: Int, generation: Int) {
        if (!isCurrentSession(generation)) return
        val played = playedSentenceCount.incrementAndGet()
        // review P2:这是唯一可信的"已发声下标"。decode 失败/无声的句子不会进入这里。
        confirmedSentenceIndex = index
        scheduleProgressSave()
        logcat(LogPriority.DEBUG) {
            "[TtsService] audio produced $index (played=$played gen=$generation)"
        }
    }

    /**
     * 播放器回调：某句**合成成功但播放失败**（写入 0 字节 PCM / 解码失败 / 无音轨）。
     *
     * [failedSentenceCount] 原本只统计 `prefetcher.await == null`；这类"有 mp3 但播不出声"
     * 的失败覆盖不到，只要另有一句成功，`playedSentenceCount > 0 && failedSentenceCount == 0`
     * 就成立，章节会被误判播完并跳章（review P1 round 3）。这里补齐该类失败。
     * [generation] 校验同 [onSentenceAudioProduced]（review ocr finding D）。
     *
     * 运行在播放器工作线程；计数器为 [AtomicInteger]（与播放循环并发写）。
     */
    private fun onSentencePlaybackFailed(index: Int, generation: Int) {
        if (!isCurrentSession(generation)) return
        val failed = failedSentenceCount.incrementAndGet()
        logcat(LogPriority.WARN) {
            "[TtsService] sentence $index produced no playable audio " +
                "(failed=$failed played=${playedSentenceCount.get()} gen=$generation)"
        }
    }

    /**
     * 调度一次延迟写入；同一窗口内的多次调用会被合并为最后一次（debounce）。
     * Service 作用域内 launch — Service 被销毁时 job 一起被取消，flush 由 stopPlayback 保证。
     */
    private fun scheduleProgressSave() {
        // review P2 round 3:防抖路径也必须用 [confirmedSentenceIndex]（已确认发声的下标），
        // 否则起播时未确认音频就调度保存 —— 首个合成请求等待超过 750ms 后失败时，
        // 仍未播放的起始句会被落盘，而后续 flush 因 confirmed=-1 直接 return，无法撤回。
        if (currentChapterId <= 0 || confirmedSentenceIndex < 0) return
        progressSaveJob?.cancel()
        val chapterId = currentChapterId
        val mangaId = currentMangaId
        val idx = confirmedSentenceIndex
        progressSaveJob = launch {
            try {
                delay(PROGRESS_SAVE_DEBOUNCE_MS)
                progressRepository.saveSentenceIndex(chapterId, mangaId, idx)
            } catch (_: CancellationException) {
                // 正常取消路径
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "[TtsService] failed to debounced-save TTS progress chapterId=$chapterId index=$idx"
                }
            }
        }
    }

    private fun cancelProgressSave() {
        progressSaveJob?.cancel()
        progressSaveJob = null
    }

    /**
     * 最终落盘。
     *
     * - 旧实现用 `launchIO`(GlobalScope.launch) fire-and-forget,进程被系统回收时
     *   可能丢失；现在改成 `suspend`,由 [stopPlayback] / [onDestroy] 用 `runBlocking`
     *   阻塞等待写入完成。
     * - 在 [runPlaybackJob] 自然结束路径上仍然是普通 suspend 调用。
     * - Repository 内部 try/catch + logcat —— flush 失败也不抛。
     */
    private suspend fun flushProgress() {
        cancelProgressSave()
        val chapterId = currentChapterId
        val mangaId = currentMangaId
        // review P2:用 [confirmedSentenceIndex] 而非 [currentSentenceIndex]：持久化必须仅
        // 反映"真正发过声的下标"，不能被解码失败/被 skipTo 抢占的句污染。
        val idx = confirmedSentenceIndex
        if (chapterId <= 0 || idx < 0) return
        try {
            progressRepository.saveSentenceIndex(chapterId, mangaId, idx)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "[TtsService] failed to flush TTS progress chapterId=$chapterId index=$idx"
            }
        }
    }

    override fun onDestroy() {
        // 先 flush 持久化进度（onDestroy 是主线程，用 runBlocking 阻塞等待 DB 写入）
        cancelProgressSave()
        runBlocking { flushProgress() }
        playbackJob?.cancel()
        prefetcher?.cancelAll()
        prefetcher = null
        // review ocr finding 7：同 stopPlayback —— 先作废 Service 侧会话，再动播放器。
        // 否则 `player.stop()/release()` 内部 invalidate 之后、本赋值之前通过
        // `isCurrentSession` 的迟到回调会在 `progressNotifier.clear()` 之后重新点亮高亮或
        // 调度一次保存。
        activeSessionGeneration = -1
        player.stop()
        player.release()
        progressNotifier.clear()
        abandonAudioFocus()
        // 关闭播放专用 scope,防协程泄漏（如果还有未结束的播放协程）
        playbackScope.cancel()
        // Phase 3 step 1：释放 MediaSession（detach callback + system token）
        mediaSession?.let { session ->
            runCatching { session.isActive = false }
            runCatching { session.release() }
        }
        mediaSession = null
        cancel()
        super.onDestroy()
        logcat(LogPriority.INFO) { "[TtsService] destroyed" }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TTS 朗读",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "正在朗读 EPUB 章节"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Phase 3 step 1：MediaStyle 通知 + 3 个媒体按钮（上一句/播放-暂停/下一句）。
     *
     * 状态来源：[playbackUiState] 由 [resume]/[pause]/[stopPlayback]/[skipTo]/[startPlayback] 维护。
     * 按钮图标随状态切换（播放中显示暂停，暂停中显示播放）。
     * MediaSession token 传入 MediaStyle 让锁屏自动渲染媒体卡片 + 通知折叠视图显示 3 个 action。
     *
     * 参考 VoxEngine ReaderPlaybackService.buildNotification 实现 —— MediaStyle.setShowActionsInCompactView(0,1,2)
     * 让上一句/播放-暂停/下一句在**折叠通知**(默认视图)也可见,不用展开。
     */
    private fun buildNotification(extractFailed: Boolean = false): Notification {
        val isPlaying = playbackUiState == TtsPlaybackState.PLAYING
        val isPaused = playbackUiState == TtsPlaybackState.PAUSED
        val isOngoing = isPlaying || isPaused

        val prevIntent = Intent(this, TtsService::class.java).apply { action = ACTION_PREV }
        val nextIntent = Intent(this, TtsService::class.java).apply { action = ACTION_NEXT }
        val playPauseIntent = Intent(this, TtsService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val stopIntent = Intent(this, TtsService::class.java).apply { action = ACTION_STOP }
        val prevPi = PendingIntent.getService(
            this,
            PI_REQ_PREV,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextPi = PendingIntent.getService(
            this,
            PI_REQ_NEXT,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPausePi = PendingIntent.getService(
            this,
            PI_REQ_PLAY_PAUSE,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            PI_REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (isPlaying) "暂停" else "继续"

        val contentText = when {
            extractFailed -> "章节文本提取失败 - 试听备用文本"
            isPlaying -> "正在朗读..."
            isPaused -> "已暂停"
            else -> "已停止"
        }
        val subText = lastPlaybackArgs?.href?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "EPUB"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Koharia TTS")
            .setContentText(contentText)
            .setSubText(subText)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // VoxEngine 写法:CATEGORY_TRANSPORT + PRIORITY_DEFAULT 让系统把通知视为"媒体播放",
            // 锁屏/蓝牙/系统媒体控件自动接管
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "上一句", prevPi)
            .addAction(playPauseIcon, playPauseLabel, playPausePi)
            .addAction(android.R.drawable.ic_media_next, "下一句", nextPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)

        // MediaStyle 让折叠通知显示 3 个 action,展开时显示 4 个 + MediaSession 媒体卡片
        mediaSession?.sessionToken?.let { token ->
            builder.setStyle(
                MediaAppNotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2), // 上一句 / 播放-暂停 / 下一句
            )
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "koharia.tts.playback"
        private const val NOTIFICATION_ID = 8421

        /** 失焦 duck（AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK）时的输出音量。 */
        private const val DUCK_VOLUME = 0.2f
        private const val ACTION_STOP = "koharia.tts.STOP"
        private const val ACTION_PLAY = "koharia.tts.PLAY"
        private const val ACTION_PAUSE = "koharia.tts.PAUSE"
        private const val ACTION_NEXT = "koharia.tts.NEXT"
        private const val ACTION_PREV = "koharia.tts.PREV"

        // PendingIntent request codes：每个 action 唯一,避免 FLAG_UPDATE_CURRENT 时
        // extras 被意外覆盖（不同 action 的 Intent 不能复用同一个 requestCode）。
        private const val PI_REQ_STOP = 0
        private const val PI_REQ_PREV = 1
        private const val PI_REQ_PLAY_PAUSE = 2
        private const val PI_REQ_NEXT = 3
        const val EXTRA_CHAPTER_ID = "chapterId"
        const val EXTRA_MANGA_ID = "mangaId"
        const val EXTRA_HREF = "href"
        const val EXTRA_PROGRESSION = "progression"
        const val EXTRA_TEXT_ANCHOR = "textAnchor"
        const val EXTRA_ANCHOR_BEFORE = "anchorBefore"

        /**
         * 可选：DOM 起始偏移 —— 视口顶部第一段可见文字在章节正文中的字符偏移。
         * 由阅读器 WebView 抽取（与句子/高亮同一文本偏移空间）。负值表示不可用。
         */
        const val EXTRA_START_OFFSET = "startOffset"

        /**
         * 可选：上次朗读到的句子文本（来自持久化层）。**调用方显式传入**才生效；
         * 主动按播放通常传 null（从当前视口起播）。TtsService 不再自动读取持久化，
         * 避免翻页中途 snippet 缺失时持久化位置劫持起播点。
         */
        const val EXTRA_PERSISTED_SENTENCE_TEXT = "persistedSentenceText"

        /**
         * 可选：调用方**预提取**章节正文的短 token。正文本身走进程内 [TtsChapterTextStore]。
         *
         * 为什么不直接传正文：整章正文放进 Intent 会被 Binder 序列化，大单文件 EPUB
         * 会抛 TransactionTooLargeException（review P1）。token→正文的映射只在本进程内，
         * Service 用 token 一次性取回正文（取出即移除）。
         *
         * 预提取文本**优先级高于** [ChapterTextExtractor]，因为：
         * - 与高亮 JS tree walker 使用同一文本节点拼接顺序，TTS 句子高亮天然对齐；
         * - 已经在阅读器渲染时拿到了文本，毫秒级；ChapterTextExtractor 走
         *   "读字节→Jsoup 解析"在远程 Komga / 大章节上经常 10+ 秒。
         */
        const val EXTRA_TEXT_TOKEN = "textToken"
        private const val DEFAULT_VOICE = "冰糖"

        /**
         * 进程级播放音色（Phase 3 step 3）。[observeVoicePreference] 写入，
         * [startPlayback] 在构造 [SentencePrefetcher] 前读 `value`。
         *
         * 初始值与 [TtsPreferences.DEFAULT_VOICE_ID] 对齐，保证阅读器
         * 还没 attach 时读 `_voice.value` 也能拿到合理默认。
         *
         * v0.4.2-63:音色已按 vendor 分槽(`tts_voice_id_<vendorId>`),此处初始值仍是 MiMo 的
         * 冰糖;服务 `onCreate` 里 [observeVoicePreference] 会立刻吐出**当前 vendor** 的实际
         * 音色,而 `startPlayback` 取 `_voice.value` 一定晚于 `onCreate`,所以届时必然正确。
         */
        private val _voice = MutableStateFlow(TtsPreferences.DEFAULT_VOICE_ID)
        val voice: StateFlow<String> = _voice.asStateFlow()

        /**
         * 防抖延迟：每次 `setCurrent` 触发一次；750ms 内新一句触发会取消旧 job，
         * 保证 DB 写入频率 ≤ ~1.3 Hz，同时不影响用户连续翻页体验。
         */
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 750L

        /**
         * 章节自然播完后，等待阅读器接管自动续播的窗口。
         * 阅读器在这段时间内导航下一章并重新 start；超时无人接管则自毁。
         */
        private const val AUTO_ADVANCE_GRACE_MS = 1_500L

        /**
         * Hardcoded fallback used when ChapterTextExtractor returns nothing
         * (reader session already released, etc). Lets the user at least hear
         * the TTS pipeline end-to-end.
         */
        private val FALLBACK_TEXT = """
            欢迎使用 Koharia 朗读功能。这是一个测试朗读文本。
            当前章节的文本提取失败，可能是因为阅读会话已经关闭。
            请回到阅读页面后再试一次。
        """.trimIndent()

        fun start(
            context: Context,
            chapterId: Long,
            mangaId: Long,
            href: String,
            progression: Double,
            textAnchor: String?,
            anchorIsBefore: Boolean,
            startOffset: Int,
            persistedSentenceText: String? = null,
            textToken: String? = null,
        ) {
            val intent = Intent(context, TtsService::class.java).apply {
                putExtra(EXTRA_CHAPTER_ID, chapterId)
                putExtra(EXTRA_MANGA_ID, mangaId)
                putExtra(EXTRA_HREF, href)
                putExtra(EXTRA_PROGRESSION, progression)
                putExtra(EXTRA_TEXT_ANCHOR, textAnchor)
                putExtra(EXTRA_ANCHOR_BEFORE, anchorIsBefore)
                putExtra(EXTRA_START_OFFSET, startOffset)
                if (persistedSentenceText != null) {
                    putExtra(EXTRA_PERSISTED_SENTENCE_TEXT, persistedSentenceText)
                }
                if (textToken != null) {
                    putExtra(EXTRA_TEXT_TOKEN, textToken)
                }
            }
            // Allow start without explicit foreground service type — Android 8+ requires it,
            // but TtsService declares foregroundServiceType in the manifest.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TtsService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /**
         * 进程级播放 UI 状态。**对外可观察**（阅读器 / 调试用），对内由 `updateTtsPlaybackState` 写入。
         *
         * 用 companion 而不是 instance 字段的原因：
         * 1. **进程级可见**：阅读器可能在 TTS Service 还没 onCreate 时就读状态；
         * 2. **抗 process death**：START_NOT_STICKY 下进程被杀后实例字段会被清零；
         *    companion 字段存活于 process 整个生命周期。
         */
        private val _playbackState = MutableStateFlow(TtsPlaybackState.STOPPED)

        /** 对外只读视图 ([StateFlow])，驱动 [koharia.epub.control.TtsControlPanel] 图标。 */
        val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

        /**
         * Phase 3.3：把 in-app [TtsAction] 派到 [TtsService]。阅读器内的 [koharia.epub.control.TtsControlPanel]
         * 用这个代替直接 `startService(Intent)` —— 应用层只用 enum，Service 内部仍走
         * ACTION_* + 串行 `dispatchControl`，与通知栏 / 锁屏 / 蓝牙共用同一执行路径。
         *
         * 新增动作：扩 [TtsAction] enum + 这里的 `when`。
         */
        fun dispatch(context: Context, ttsAction: TtsAction) {
            val intentAction = when (ttsAction) {
                TtsAction.PLAY -> ACTION_PLAY
                TtsAction.PAUSE -> ACTION_PAUSE
                TtsAction.PREV -> ACTION_PREV
                TtsAction.NEXT -> ACTION_NEXT
                TtsAction.STOP -> ACTION_STOP
            }
            context.startService(Intent(context, TtsService::class.java).apply { action = intentAction })
        }
    }
}
