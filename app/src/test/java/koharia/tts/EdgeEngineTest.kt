package koharia.tts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Edge TTS 单测。
 *
 * **不依赖真机连 Microsoft 服务端** —— 测的是我们能不能算对 Sec-MS-GEC hash。
 * 真机连通性会受网络 / 协议更新影响,不应该靠反复装机验证。
 *
 * 参考实现用 Python 算 oracle 值(见 commit bash python 脚本),Kotlin 这边用相同输入
 * 跑算法,结果必须 byte-for-byte 一致 —— 否则服务端必然 403。
 *
 * v0.4.2-55 真机 403 的根因就是把 `currentUnixSecs + WINDOWS_EPOCH_OFFSET_SECS` 的 `+`
 * 误写成 `−`(WFT 不能从 unix 减)。这个测试如果当时有,一行就锁死。
 */
class EdgeEngineTest {

    private fun makeEngine(): EdgeEngine = EdgeEngine()

    // ===== Sec-MS-GEC reference values =====

    /**
     * Oracle 来自 Python `edge-tts` v7.2.7 `drm.py:DRM.generate_sec_ms_gec`:
     * ```python
     * TR = '6A5AA1D4EAFF4E9FB37E23D68491D6F4'
     * WIN_EPOCH = 11644473600
     * def gec(unix):
     *   ticks = unix + WIN_EPOCH
     *   ticks -= ticks % 300          # 5 分钟窗口取整
     *   ticks *= 10_000_000            # 秒 → 100-ns intervals
     *   return sha256(f'{ticks:.0f}{TR}'.encode('ascii')).hexdigest().upper()
     * gec(1726400000)  # 2024-09-15 ~12:53 UTC
     * # → '73F19C23904AD1C2614AEBB0672009BA9A4106BE28A79A63211092D1846363FA'
     * ```
     *
     * **v0.4.2-55 oracle 错了**:base64 + 加冒号。v0.4.2-57 改正:hex.upper + 无冒号。
     */
    @Test
    fun `secMsGec matches Python oracle at unix 1726400000 (2024-09-15)`() {
        makeEngine().generateSecMsGec(1726400000L) shouldBe
            "73F19C23904AD1C2614AEBB0672009BA9A4106BE28A79A63211092D1846363FA"
    }

    @Test
    fun `secMsGec matches Python oracle at unix 1735689600 (2025-01-01 UTC)`() {
        makeEngine().generateSecMsGec(1735689600L) shouldBe
            "B0EDD22C7C09868E2F24C10264A8A3EB877773A7B6040B68AFA4FBCBABEA0238"
    }

    @Test
    fun `secMsGec matches Python oracle at unix 1700000000 (2023-11-14)`() {
        makeEngine().generateSecMsGec(1700000000L) shouldBe
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF"
    }

    // ===== Sec-MS-GEC window behavior =====

    @Test
    fun `secMsGec is deterministic for same input`() {
        val engine = makeEngine()
        engine.generateSecMsGec(1726400000L) shouldBe engine.generateSecMsGec(1726400000L)
    }

    @Test
    fun `secMsGec is stable within 5-minute window`() {
        // 算法:secs = floor((unix + 11644473600) / 300)
        // unix=0  → WFT=11644473600 → secs=38814912(精确整除)
        // unix=299 → WFT=11644473899 → secs=floor(38814912.99666)=38814912(同窗口)
        // hash 应一致
        val engine = makeEngine()
        engine.generateSecMsGec(0L) shouldBe engine.generateSecMsGec(299L)
    }

    @Test
    fun `secMsGec changes at 5-minute boundary`() {
        // unix=299 → secs=38814912
        // unix=300 → WFT=11644473900 → secs=38814913(精确整除,跳到下一窗口)
        // hash 必须不同
        val engine = makeEngine()
        engine.generateSecMsGec(299L) shouldNotBe engine.generateSecMsGec(300L)
    }

    @Test
    fun `secMsGec window width is exactly 300 seconds`() {
        // 1 ≤ window ≤ 300 是同窗口;[300, 599] 是下一窗口;...
        // 抽样几个边界点确认 300 秒精确性
        val engine = makeEngine()
        // 同一窗口边界对
        engine.generateSecMsGec(299L) shouldBe engine.generateSecMsGec(0L)
        engine.generateSecMsGec(599L) shouldBe engine.generateSecMsGec(300L)
        // 跨窗口边界对
        engine.generateSecMsGec(300L) shouldNotBe engine.generateSecMsGec(299L)
        engine.generateSecMsGec(600L) shouldNotBe engine.generateSecMsGec(599L)
    }

    // ===== SSML escaping =====

    @Test
    fun `escapeXml escapes all five XML special chars`() {
        val engine = makeEngine()
        // 通过 buildSsml 间接验证(escapeXml 是 private)
        val ssml = engine.buildSsml("zh-CN-XiaoxiaoNeural", "a&b<c>d\"e'f")
        // & → &amp;  < → &lt;  > → &gt;  " → &quot;  ' → &apos;
        ssml shouldBe "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='zh-CN-XiaoxiaoNeural'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>a&amp;b&lt;c&gt;d&quot;e&apos;f</prosody>" +
            "</voice></speak>"
    }

    @Test
    fun `escapeXml leaves plain text unchanged`() {
        val engine = makeEngine()
        val ssml = engine.buildSsml("en-US-JennyNeural", "Hello world 123")
        ssml shouldBe "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='en-US-JennyNeural'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>Hello world 123</prosody>" +
            "</voice></speak>"
    }

    @Test
    fun `escapeXml picks xml-lang from voice prefix`() {
        val engine = makeEngine()
        engine.buildSsml("zh-CN-XiaoxiaoNeural", "x") shouldContain "xml:lang='zh-CN'"
        engine.buildSsml("en-US-JennyNeural", "x") shouldContain "xml:lang='en-US'"
    }

    // ===== configuration =====

    @Test
    fun `engineId is edge and displayName mentions Microsoft Edge`() {
        val engine = makeEngine()
        engine.engineId shouldBe "edge"
        engine.displayName shouldBe "Microsoft Edge TTS"
    }

    @Test
    fun `isConfigured always true (Edge TTS needs no key)`() {
        makeEngine().isConfigured() shouldBe true
    }

    @Test
    fun `listVoices contains all 6 hardcoded presets`() {
        val presets = kotlinx.coroutines.runBlocking { makeEngine().listVoices() }
        val expected = setOf(
            "zh-CN-XiaoxiaoNeural",
            "zh-CN-YunxiNeural",
            "zh-CN-YunyangNeural",
            "en-US-JennyNeural",
            "en-US-AriaNeural",
            "en-US-GuyNeural",
        )
        presets.map { it.id }.toSet() shouldBe expected
        presets.all { it.type == VoiceType.PRESET } shouldBe true
    }
}
