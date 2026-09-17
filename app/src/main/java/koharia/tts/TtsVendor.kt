package koharia.tts

/**
 * TTS 厂商抽象。每个具体厂商是一个 [data object],暴露:
 *  - 稳定 id(用于 prefs / Injekt / 日志)
 *  - 用户可见名(用于设置页)
 *  - 是否需要 API key
 *  - 一个 [createEngine] 工厂,接收用户输入的 key + 自定义 base URL,产出 [TtsEngine]。
 *
 * **不再注入 [TtsEngine] 单例**:TtsService 在 `observeVendorPreference()` 里
 * 根据当前 [koharia.tts.TtsPreferences.vendorId] + [TtsSecurePreferences] 重新构建,
 * 切换 vendor 时无需重启 Service。
 *
 * 新增厂商只需:1) 在这里加一个 `data object` 2) 实现 [createEngine] 3) 在
 * [PRESET_VOICES] / [defaultVoiceId] 里挂预设音色。设置页 UI 自动从 [ALL] 渲染,
 * 无需改动 SettingsScreen。
 */
sealed class TtsVendor(
    /** 稳定 id,写入 [koharia.tts.TtsPreferences.vendorId] prefs。 */
    val id: String,

    /** 用户可见名(i18n 后续可抽,先硬编码中英)。 */
    val displayName: String,

    /** 是否需要 API key。false 时 [createEngine] 忽略 apiKey 参数。 */
    val needsApiKey: Boolean,

    /** 获取 API key 的官方入口(设置页渲染为可点击链接)。 */
    val apiKeyHintUrl: String? = null,
) {
    /**
     * 构造一个 [TtsEngine] 实例。
     *
     * 调用方负责在每次切换 vendor 时重新调用本方法构建新引擎(vendor 切换是
     * 跨实现类型的改变,必须重建);**key / base URL 变化不需要重建** —— 用 lookup
     * 形式传入 lambda,引擎每次 [TtsEngine.synthesize] 调用都会重新读取,
     * 这样用户改 key 后**当前正在飞的 playback 下一句就用新 key**。
     *
     * 引擎**轻量**,每次重建只 new 一次,OkHttp client 共享([MimoEngine] /
     * [EdgeEngine] 各自持有)。
     *
     * @param apiKeyLookup 每次 synthesize 调用前重新执行,返回当前 vendor 的 API key
     *   (通常读 [TtsSecurePreferences.getApiKey])。`null` / blank 时由
     *   [TtsEngine.isConfigured] 返回 false,synthesize 调用立即 fail-fast。
     * @param baseUrlLookup 每次 synthesize 调用前重新执行,返回 base URL 覆盖
     *   (通常读 [TtsSecurePreferences.getBaseUrl]);`null` 时引擎用 [MimoEngine.DEFAULT_BASE_URL]。
     */
    abstract fun createEngine(
        apiKeyLookup: () -> String?,
        baseUrlLookup: () -> String?,
    ): TtsEngine

    /** 当前 vendor 的预设音色列表(用作设置页 UI 渲染 + 合法性兜底)。 */
    abstract fun presetVoices(): List<Voice>

    /** 当前 vendor 的默认音色(用户首次安装 / 切换 vendor 时 fallback 用)。 */
    abstract fun defaultVoiceId(): String

    // ===== 具体 vendor =====

    /**
     * MiMo TTS(Phase 1 引入)。
     *
     * API 协议:OpenAI 兼容 chat/completions。详见 `docs/tts/miMo-api-behavior.md`。
     * 音色:8 个预设(冰糖/茉莉/苏打/白桦 + Mia/Chloe/Milo/Dean)。
     */
    data object Mimo : TtsVendor(
        id = "mimo",
        displayName = "MiMo TTS",
        needsApiKey = true,
        apiKeyHintUrl = "https://platform.xiaomimimo.com/",
    ) {
        override fun createEngine(apiKeyLookup: () -> String?, baseUrlLookup: () -> String?): TtsEngine =
            MimoEngine(
                apiKeyProvider = apiKeyLookup,
                baseUrlProvider = { baseUrlLookup() ?: MimoEngine.DEFAULT_BASE_URL },
            )

        override fun presetVoices(): List<Voice> = MimoEngine.PRESET_VOICES

        override fun defaultVoiceId(): String = MimoEngine.DEFAULT_VOICE_ID
    }

    /**
     * Microsoft Edge TTS(Phase 4 引入,免 key 免费备用)。
     *
     * API 协议:WebSocket `wss://speech.platform.bing.com/.../edge/v1`,
     * TrustedClientToken 写死在引擎内(社区共识常量,服务端校验)。
     * 音色:6 个预设(zh-CN Xiaoxiao/Yunxi/Yunyang + en-US Jenny/Aria/Guy)。
     *
     * **稳定性注意**:Edge TTS 是社区逆向协议,微软可能调整而失效。
     * 若失效,本引擎 [synthesize] 会抛 [EdgeException],用户切回 MiMo 即可。
     */
    data object Edge : TtsVendor(
        id = "edge",
        displayName = "Microsoft Edge TTS",
        needsApiKey = false,
        apiKeyHintUrl = null,
    ) {
        override fun createEngine(apiKeyLookup: () -> String?, baseUrlLookup: () -> String?): TtsEngine {
            // baseUrlLookup 留作未来自定义 Edge 节点(目前用不到)
            return EdgeEngine()
        }

        override fun presetVoices(): List<Voice> = EdgeEngine.PRESET_VOICES

        override fun defaultVoiceId(): String = EdgeEngine.DEFAULT_VOICE_ID
    }

    companion object {
        /**
         * 所有可用 vendor(设置页 UI 渲染来源,顺序即显示顺序)。
         *
         * **必须 `by lazy`**:[Mimo] / [Edge] 是 [TtsVendor] 的嵌套 `data object`,
         * 它们的 `INSTANCE` 在各自 `<clinit>` 末尾才赋值,而 `<clinit>` 又必须先等
         * 超类 [TtsVendor] 初始化完成。若在 companion 初始化期间直接求值
         * `listOf(Mimo, Edge)`,一旦**首个被访问的成员就是 `TtsVendor.Mimo` /
         * `TtsVendor.Edge`**,该 object 仍在初始化中,`INSTANCE` 还是 null →
         * [ALL] 永久持有 null 元素 → [fromId] 和设置页遍历全部 NPE。
         *
         * 实测:`TtsPreferencesTest` 中首个执行的用例改成直接引用 `TtsVendor.Mimo`
         * 之后,所有走 [fromId] 的用例立刻 NPE(`ALL` 首元素为 null)。
         * 改 lazy 后求值推迟到首次访问,此时嵌套 object 早已完成初始化。
         */
        val ALL: List<TtsVendor> by lazy { listOf(Mimo, Edge) }

        /** 首次安装默认 vendor。同样必须 `by lazy`,原因见 [ALL]。 */
        val DEFAULT: TtsVendor by lazy { Mimo }

        /**
         * 根据 prefs 里的 vendorId 查找 vendor。
         *
         * 找不到时回退 [DEFAULT],保证 [TtsService] 永远拿到一个可构造的引擎。
         * (理论不应发生,因为 [koharia.tts.TtsPreferences.vendorId] 的写入路径
         * 只接受 [ALL] 里的 id;此处兜底防手改 prefs XML。)
         */
        fun fromId(id: String?): TtsVendor = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
