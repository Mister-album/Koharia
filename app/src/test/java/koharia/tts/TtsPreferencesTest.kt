package koharia.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TtsPreferencesTest {

    private fun preferences() = TtsPreferences(InMemoryPreferenceStore())

    @Test
    fun `default speed is 1x`() {
        preferences().speed() shouldBe 1.0f
    }

    @Test
    fun `speed reflects stored tenths`() {
        val prefs = preferences()
        prefs.speedTenths.set(15)
        prefs.speed() shouldBe 1.5f
    }

    @Test
    fun `speed clamps below minimum to 0_5x`() {
        val prefs = preferences()
        prefs.speedTenths.set(1)
        prefs.speed() shouldBe 0.5f
    }

    @Test
    fun `speed clamps above maximum to 2x`() {
        val prefs = preferences()
        prefs.speedTenths.set(99)
        prefs.speed() shouldBe 2.0f
    }

    // ===== v0.4.2-63: 音色按 vendor 分槽存储 =====

    @Test
    fun `voiceIdFor defaults to the vendor default when pref unset`() {
        preferences().voiceIdFor("mimo").get() shouldBe TtsPreferences.DEFAULT_VOICE_ID
        preferences().voiceIdFor("edge").get() shouldBe EdgeEngine.DEFAULT_VOICE_ID
    }

    @Test
    fun `voiceIdFor reads stored value`() {
        val prefs = preferences()
        prefs.voiceIdFor("mimo").set("Mia")
        prefs.voiceIdFor("mimo").get() shouldBe "Mia"
    }

    @Test
    fun `voiceIdFor resolves to the current vendor when vendorId is omitted`() {
        val prefs = preferences()
        prefs.vendorId.set("edge")
        prefs.voiceIdFor().set("zh-CN-YunxiNeural")

        prefs.voiceIdFor().get() shouldBe "zh-CN-YunxiNeural"
        // 写进了 edge 槽位,mimo 槽位不受影响
        prefs.voiceIdFor("mimo").get() shouldBe TtsPreferences.DEFAULT_VOICE_ID
    }

    @Test
    fun `each vendor keeps its own voice selection`() {
        val prefs = preferences()
        prefs.voiceIdFor("mimo").set("茉莉")
        prefs.voiceIdFor("edge").set("zh-CN-YunxiNeural")

        prefs.voiceIdFor("mimo").get() shouldBe "茉莉"
        prefs.voiceIdFor("edge").get() shouldBe "zh-CN-YunxiNeural"
    }

    @Test
    fun `switching vendor and back preserves the previous selection`() {
        // 回归锁：单值 key 时代这里必然丢 —— TtsService.observeVendorPreference 会把
        // voice reset 成新 vendor 的默认值,切回来只剩默认音色。per-vendor 存储后
        // 用户的选择必须还在。
        val prefs = preferences()
        prefs.voiceIdFor("mimo").set("茉莉")

        prefs.vendorId.set("edge")
        prefs.voiceIdFor().get() shouldBe EdgeEngine.DEFAULT_VOICE_ID
        prefs.voiceIdFor().set("zh-CN-YunxiNeural")

        prefs.vendorId.set("mimo")
        prefs.voiceIdFor().get() shouldBe "茉莉"
    }

    // ===== v0.4.2-63: 旧单值 key 的一次性迁移 =====

    @Test
    fun `legacy Mimo voice key is migrated to the mimo slot`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference("tts_voice_id", "Mia", "")),
        )

        val prefs = TtsPreferences(store)

        prefs.voiceIdFor("mimo").get() shouldBe "Mia"
        prefs.voiceIdFor("edge").get() shouldBe EdgeEngine.DEFAULT_VOICE_ID
    }

    @Test
    fun `legacy Edge voice key is migrated to the edge slot`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("tts_voice_id", "zh-CN-YunxiNeural", ""),
            ),
        )

        val prefs = TtsPreferences(store)

        prefs.voiceIdFor("edge").get() shouldBe "zh-CN-YunxiNeural"
        prefs.voiceIdFor("mimo").get() shouldBe TtsPreferences.DEFAULT_VOICE_ID
    }

    @Test
    fun `unmappable legacy voice key is dropped without polluting vendor slots`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference("tts_voice_id", "不存在的音色", "")),
        )

        val prefs = TtsPreferences(store)

        prefs.voiceIdFor("mimo").get() shouldBe TtsPreferences.DEFAULT_VOICE_ID
        prefs.voiceIdFor("edge").get() shouldBe EdgeEngine.DEFAULT_VOICE_ID
    }

    @Test
    fun `absent legacy key leaves every vendor slot at its default`() {
        val prefs = preferences()

        prefs.voiceIdFor("mimo").get() shouldBe TtsPreferences.DEFAULT_VOICE_ID
        prefs.voiceIdFor("edge").get() shouldBe EdgeEngine.DEFAULT_VOICE_ID
    }

    @Test
    fun `validVoiceIds contains all 8 preset voices from MimoEngine PRESET_VOICES`() {
        val ids = preferences().validVoiceIds()
        setOf("冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean") shouldBe ids
    }

    @Test
    fun `validVoiceIds stays in sync with MimoEngine PRESET_VOICES`() {
        // 防回归：如果将来 PRESET_VOICES 加了/删了音色,确保 VALID_VOICE_IDS 也跟着变。
        val fromEngine = MimoEngine.PRESET_VOICES.mapTo(mutableSetOf()) { it.id }
        preferences().validVoiceIds() shouldBe fromEngine
    }

    // ===== Phase 4: vendor 偏好 =====

    @Test
    fun `vendorId defaults to mimo`() {
        preferences().vendorId.get() shouldBe TtsPreferences.DEFAULT_VENDOR_ID
    }

    @Test
    fun `vendorId reads stored value`() {
        val prefs = preferences()
        prefs.vendorId.set("edge")
        prefs.vendorId.get() shouldBe "edge"
    }

    @Test
    fun `validVoiceIds returns Edge preset voices when vendor is edge`() {
        val prefs = preferences()
        prefs.vendorId.set("edge")
        val ids = prefs.validVoiceIds()
        val expected = EdgeEngine.PRESET_VOICES.mapTo(mutableSetOf()) { it.id }
        ids shouldBe expected
        // 不应混入 MiMo 音色(冰糖 等)
        (ids intersect MimoEngine.PRESET_VOICES.mapTo(mutableSetOf()) { it.id }).isEmpty() shouldBe true
    }

    @Test
    fun `validVoiceIds dynamically reflects vendor switch without restart`() {
        val prefs = preferences()
        // 默认 vendor = mimo: 看到 8 个 MiMo 音色
        prefs.validVoiceIds().size shouldBe MimoEngine.PRESET_VOICES.size
        // 切到 edge: 看到 6 个 Edge 音色
        prefs.vendorId.set("edge")
        prefs.validVoiceIds().size shouldBe EdgeEngine.PRESET_VOICES.size
        // 切回 mimo: 又看到 8 个 MiMo
        prefs.vendorId.set("mimo")
        prefs.validVoiceIds().size shouldBe MimoEngine.PRESET_VOICES.size
    }

    // ===== 回归锁:「Edge 引擎音色选不了」 =====

    @Test
    fun `every vendor only renders voices that are valid for that vendor`() {
        // 阅读设置的音色列表此前写死 MimoEngine.PRESET_VOICES,于是 vendor=edge 时
        // 渲染的是 MiMo 音色;用户点任意一行 → 写入的 id 在 Edge 下非法 →
        // TtsService.observeVoicePreference 静默回退成 Edge 默认音色,点了没反应。
        // UI 现在渲染 TtsVendor.presetVoices(),它必须与 validVoiceIds() 同源。
        TtsVendor.ALL.forEach { vendor ->
            val prefs = preferences()
            prefs.vendorId.set(vendor.id)

            val rendered = vendor.presetVoices().map { it.id }.toSet()

            rendered shouldBe prefs.validVoiceIds()
        }
    }

    @Test
    fun `every vendor default voice is among its own preset voices`() {
        // 默认音色是非法音色的回退目标;它自己不可选的话,UI 会出现「无任何高亮」
        // 且 observeVoicePreference 的回退值不在列表内。
        TtsVendor.ALL.forEach { vendor ->
            val ids = vendor.presetVoices().map { it.id }.toSet()
            (vendor.defaultVoiceId() in ids) shouldBe true
        }
    }

    @Test
    fun `Edge vendor preset voices match the ids EdgeEngine accepts`() {
        // EdgeEngine.synthesize 用 VALID_VOICE_IDS 做 require 校验;若厂商列表与
        // 引擎校验集分叉,会出现「列表里能选、合成时被拒」的同类问题。
        TtsVendor.Edge.presetVoices().map { it.id }.toSet() shouldBe EdgeEngine.VALID_VOICE_IDS
    }

    @Test
    fun `Mimo vendor preset voices match MimoEngine PRESET_VOICES`() {
        TtsVendor.Mimo.presetVoices() shouldBe MimoEngine.PRESET_VOICES
    }
}
