package koharia.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class MimoEngineTest {

    private val mp3FrameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte())

    private fun makeEngine() = MimoEngine(apiKeyProvider = { "test-key-for-unit-tests" })

    private fun encode(mp3Bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(mp3Bytes)

    private fun responseWith(dataB64: String): String = """
        {
          "id": "test-id",
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "",
              "audio": {
                "id": "audio-id",
                "data": "$dataB64",
                "expires_at": null,
                "transcript": null
              }
            },
            "finish_reason": "stop"
          }]
        }
    """.trimIndent()

    @Test
    fun `parseResponse decodes base64 audio and metadata`() {
        val engine = makeEngine()
        val body = responseWith(encode(mp3FrameHeader))

        val result = engine.parseResponse(body, elapsedMs = 123)

        result.audioData shouldBe mp3FrameHeader
        result.format shouldBe AudioFormat.MP3
        result.sampleRate shouldBe MimoEngine.MIMO_SAMPLE_RATE
        result.channels shouldBe MimoEngine.MIMO_CHANNELS
        result.elapsedMs shouldBe 123L
        // Duration is byte-rate-based estimate: 4 bytes * 8 * 1000 / 66000 ≈ 0ms
        (result.durationMs >= 0L) shouldBe true
    }

    @Test
    fun `parseResponse estimates duration from byte size`() {
        val engine = makeEngine()
        // 6600 bytes at ~66kbps → ~1000ms estimated
        val payload = ByteArray(6600) { 0 }
        val body = responseWith(encode(payload))

        val result = engine.parseResponse(body, elapsedMs = 50)

        // (6600 * 8 * 1000) / 66000 = 800ms
        result.durationMs shouldBe 800L
    }

    @Test
    fun `parseResponse throws on missing audio field`() {
        val engine = makeEngine()
        val body = """{"choices":[{"message":{"audio":null},"finish_reason":"stop"}]}"""

        val ex = assertThrows<MimoException> {
            engine.parseResponse(body, elapsedMs = 10)
        }
        (ex.message != null) shouldBe true
    }

    @Test
    fun `parseResponse throws on missing data field`() {
        val engine = makeEngine()
        val body = """{"choices":[{"message":{"audio":{"expires_at":null}},"finish_reason":"stop"}]}"""

        assertThrows<MimoException> {
            engine.parseResponse(body, elapsedMs = 10)
        }
    }

    @Test
    fun `isConfigured reflects api key presence`() {
        MimoEngine(apiKeyProvider = { "" }).isConfigured() shouldBe false
        MimoEngine(apiKeyProvider = { "sk-test" }).isConfigured() shouldBe true
    }

    @Test
    fun `listVoices returns non-empty preset list`() {
        val voices = kotlinx.coroutines.runBlocking { makeEngine().listVoices() }

        (voices.isNotEmpty()) shouldBe true
        voices.any { it.id == "冰糖" } shouldBe true
        voices.any { it.language == "en" } shouldBe true
    }

    @Test
    fun `synthesize rejects unconfigured engine`() {
        val engine = MimoEngine(apiKeyProvider = { "" })
        val sentence = Sentence(
            chapterHref = "ch1",
            index = 0,
            startOffset = 0,
            endOffset = 3,
            text = "你好。",
        )

        assertThrows<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking {
                engine.synthesize(sentence, SynthesisRequest(voice = "冰糖"))
            }
        }
    }
}
