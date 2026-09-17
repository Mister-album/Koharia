package koharia.tts

import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * 直接对**真实 [EdgeEngine]** 跑完整端到端(JVM + OkHttp + 公网)。
 *
 * 与 `EdgeEngineFullFlowIntegrationTest` 的区别:那个测试重新拼了一遍协议帧,
 * 这个测试**调用生产代码** [EdgeEngine.synthesize],验证:
 *  1. `ConnectionSpec.RESTRICTED_TLS` 能拿到 WebSocket 101
 *  2. speech.config + SSML 帧格式被服务端接受
 *  3. `Path:turn.end` 结束标记被正确识别 → 返回 MP3 字节
 *
 * 覆盖的核心回归:`Path:audio` → `Path:turn.end` 的 end-marker bug
 * (v0.4.2-58 发现:写错 marker 导致 coroutine 永不 resume)。
 *
 * 需要 `testOptions.unitTests.isReturnDefaultValues = true`
 * (让 EdgeEngine 里的 `logcat()` → `android.util.Log` 不抛异常)。
 *
 * **联网门控**：默认跳过（CI 不跑公网），要跑显式打开：
 * `./gradlew.bat :app:testDebugUnitTest -PkohariaNetworkTests=true`
 */
@EnabledIfSystemProperty(named = "koharia.networkTests", matches = "true")
class EdgeEngineLiveTest {

    @Test
    fun `synthesize returns real mp3 bytes from Microsoft Edge TTS`() {
        val engine = EdgeEngine()
        val text = "Hello from Koharia live test."

        val result = runBlocking {
            engine.synthesize(
                sentence = Sentence(
                    chapterHref = "EPUB/text/ch1.xhtml",
                    index = 0,
                    startOffset = 0,
                    endOffset = text.length,
                    text = text,
                ),
                request = SynthesisRequest(voice = "en-US-JennyNeural"),
            )
        }

        println(
            "EdgeEngineLiveTest: synthesized ${result.audioData.size} bytes, " +
                "format=${result.format}, durationMs=${result.durationMs}",
        )

        result.audioData.size.shouldBeGreaterThan(1000)
    }
}
