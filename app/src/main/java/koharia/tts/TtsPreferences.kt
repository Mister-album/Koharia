package koharia.tts

import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat

/**
 * 全局 TTS 偏好（unscoped）——朗读相关设置不属于某个 Komga 服务器或某本书，
 * 因此显式使用**未作用域**的 [PreferenceStore]（见 AGENTS.md「Scoped/global preferences」）。
 *
 * Phase 3 step 4: + voiceId(音色选择)
 * Phase 4: + vendorId(TTS 厂商选择)
 * v0.4.2-63: 音色改 **per-vendor** 存储(`tts_voice_id_<vendorId>`)—— 旧版是单一 key,
 *   切换 vendor 只能 reset,来回切一次就丢一次选择(MiMo→Edge→MiMo 会丢失 MiMo 侧的音色)。
 *   旧 key 由 [migrateLegacyVoiceId] 一次性迁移。
 */
class TtsPreferences(preferenceStore: PreferenceStore) {

    /**
     * 朗读语速，以「十分之一倍率」为整数存储（10 = 1.0x，5 = 0.5x，20 = 2.0x）。
     *
     * 用整数而非 Float 的原因：跨设备备份/恢复时整数无浮点精度漂移；
     * 同时与 Material3 `Slider` 的离散步进（[MIN_SPEED_TENTHS]..[MAX_SPEED_TENTHS]）天然对齐。
     */
    val speedTenths: Preference<Int> = preferenceStore.getInt(KEY_SPEED_TENTHS, DEFAULT_SPEED_TENTHS)

    /** 归一化到 [MIN_SPEED_TENTHS]..[MAX_SPEED_TENTHS] 的语速倍率。 */
    fun speed(): Float = speedTenths.get().coerceIn(MIN_SPEED_TENTHS, MAX_SPEED_TENTHS) / 10f

    /**
     * Phase 4:当前 TTS 厂商 id。
     *
     * 必须是 [TtsVendor.ALL] 里存在的 id;TtsService 收到未知值时回退到
     * [TtsVendor.DEFAULT](MiMo)。设置页 UI 只渲染 [TtsVendor.ALL] 列表,
     * 非法写入只可能来自手改 prefs XML。
     */
    val vendorId: Preference<String> = preferenceStore.getString(KEY_VENDOR_ID, DEFAULT_VENDOR_ID)

    /**
     * 每个 vendor 各自的音色偏好(key = `tts_voice_id_<vendorId>`)。
     *
     * **为什么 per-vendor**：`voiceId` 的语义依赖当前 vendor —— 同一个 id 在不同厂商下
     * 指向不同音色,且**互不合法**(Edge 只认 `zh-CN-*Neural`,MiMo 只认 `冰糖` 等)。
     * 单一 key 下切换 vendor 只能把选择 reset 掉,用户来回切一次就丢一次。
     *
     * **为什么在构造期一次性建好,而不是每次调 `getString`**：
     *  1. 复用同一 [Preference] 实例 → `changes()` 订阅稳定、不重复分配;
     *  2. [tachiyomi.core.common.preference.InMemoryPreferenceStore](单测用)的 `getString`
     *     **每次返回新实例**且 `set()` 不会落回内部 map —— 若每次都新建,单测里
     *     "写后读"永远读到默认值(假绿/假红)。
     */
    private val voiceIdPrefs: Map<String, Preference<String>> =
        TtsVendor.ALL.associate { vendor ->
            vendor.id to preferenceStore.getString(
                KEY_VOICE_ID_PREFIX + vendor.id,
                vendor.defaultVoiceId(),
            )
        }

    init {
        migrateLegacyVoiceId(preferenceStore)
    }

    /**
     * 取指定 vendor(默认**当前** vendor)的音色偏好。
     *
     * `vendorId` 参数只在需要显式读写"某个非当前 vendor 的槽位"时用(迁移、单测)。
     * 槽位未设置时 `get()` 返回该 vendor 的 [TtsVendor.defaultVoiceId],天然合法 ——
     * 因此切 vendor **不需要任何 reset 逻辑**。
     */
    fun voiceIdFor(vendorId: String = this.vendorId.get()): Preference<String> =
        voiceIdPrefs.getValue(TtsVendor.fromId(vendorId).id)

    /**
     * 当前 vendor 的合法 voice id 集合(动态查 [TtsVendor.presetVoices])。
     *
     * 取代旧的 [VALID_VOICE_IDS] 常量 —— 后者只含 MiMo,Edge 引入后失效。
     * UI 渲染音色列表 / TtsService 验证用户输入都走这里。
     */
    fun validVoiceIds(): Set<String> =
        TtsVendor.fromId(vendorId.get()).presetVoices().mapTo(mutableSetOf()) { it.id }

    /**
     * 一次性迁移：把 v0.4.2-62 及之前的**单值** key [KEY_VOICE_ID_LEGACY] 的值搬到它
     * 真正归属的 vendor 槽位,然后删除旧 key。
     *
     * 旧值不带 vendor 信息,因此用 `presetVoices()` 反查归属 —— Edge 的 id 形如
     * `zh-CN-XiaoxiaoNeural`、MiMo 的形如 `冰糖`,不会歧义。
     *
     * **幂等**：删除后 [Preference.isSet] 为 false,后续进程直接返回。
     * 无法归属的值(手改 prefs XML / 旧厂商已被移除)丢弃并 WARN —— 各 vendor 槽位
     * 各自回落到自己的 `defaultVoiceId()`,不会把非法 id 喂给引擎。
     */
    private fun migrateLegacyVoiceId(store: PreferenceStore) {
        val legacy = store.getString(KEY_VOICE_ID_LEGACY, "")
        if (!legacy.isSet()) return

        val value = legacy.get()
        val owner = TtsVendor.ALL.firstOrNull { vendor ->
            vendor.presetVoices().any { it.id == value }
        }

        if (owner != null) {
            voiceIdPrefs[owner.id]?.set(value)
            logcat(LogPriority.INFO) {
                "[TtsPreferences] migrated legacy voice '$value' -> vendor '${owner.id}'"
            }
        } else {
            logcat(LogPriority.WARN) {
                "[TtsPreferences] dropping legacy voice '$value' (no vendor claims it)"
            }
        }
        legacy.delete()
    }

    companion object {
        /** 0.5x —— 最慢。 */
        const val MIN_SPEED_TENTHS = 5

        /** 2.0x —— 最快。 */
        const val MAX_SPEED_TENTHS = 20

        /** 1.0x —— 正常语速。 */
        const val DEFAULT_SPEED_TENTHS = 10

        private const val KEY_SPEED_TENTHS = "tts_speed_tenths"

        /** Phase 4: 首次安装默认 vendor(MiMo)。 */
        const val DEFAULT_VENDOR_ID = "mimo"
        private const val KEY_VENDOR_ID = "tts_vendor_id"

        /**
         * MiMo 的默认音色(冰糖)。
         *
         * 历史常量：per-vendor 存储后各 vendor 的默认值统一走 [TtsVendor.defaultVoiceId],
         * 此常量仅供 [koharia.tts.TtsService] 的进程级 `_voice` 初始值与单测断言引用。
         */
        const val DEFAULT_VOICE_ID = "冰糖"

        /** v0.4.2-62 及之前的**单值**音色 key;仅供 [migrateLegacyVoiceId] 读取。 */
        private const val KEY_VOICE_ID_LEGACY = "tts_voice_id"

        /** per-vendor 音色 key 前缀,实际 key = `tts_voice_id_<vendorId>`。 */
        private const val KEY_VOICE_ID_PREFIX = "tts_voice_id_"

        /**
         * @deprecated 改用 [validVoiceIds] —— 后者 vendor-aware。
         * 仅保留供现有单元测试引用(stays-in-sync 锁);不要在生产代码使用。
         */
        @Deprecated(
            "Use validVoiceIds() — vendor-aware set",
            ReplaceWith("TtsPreferences(preferenceStore).validVoiceIds()"),
        )
        val VALID_VOICE_IDS: Set<String> = MimoEngine.PRESET_VOICES.mapTo(mutableSetOf()) { it.id }
    }
}
