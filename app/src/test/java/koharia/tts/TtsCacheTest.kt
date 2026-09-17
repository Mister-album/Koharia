package koharia.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class TtsCacheTest {

    @Test
    fun `cache roundtrip preserves bytes`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        cache.put(voice = "冰糖", style = null, text = "测试", audioData = payload)
        val retrieved = cache.get(voice = "冰糖", style = null, text = "测试")

        retrieved shouldBe payload
    }

    @Test
    fun `cache key discriminates by voice and style`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        cache.put(voice = "冰糖", style = null, text = "你好", audioData = byteArrayOf(0xFF.toByte()))

        // Same voice + style + text → hit
        cache.exists(voice = "冰糖", style = null, text = "你好") shouldBe true

        // Different style → miss
        cache.exists(voice = "冰糖", style = "开心", text = "你好") shouldBe false

        // Different voice → miss
        cache.exists(voice = "茉莉", style = null, text = "你好") shouldBe false

        // Different text → miss
        cache.exists(voice = "冰糖", style = null, text = "再见") shouldBe false
    }

    @Test
    fun `cache key is consistent across invocations`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        cache.put(voice = "v", style = "s", text = "t", audioData = byteArrayOf(1))

        // First retrieval should succeed; second too (idempotent key derivation).
        val first = cache.get(voice = "v", style = "s", text = "t")
        val second = cache.get(voice = "v", style = "s", text = "t")

        (first != null) shouldBe true
        (second != null) shouldBe true
        first shouldBe second
    }

    @Test
    fun `clearAll removes everything`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        cache.put(voice = "v", style = null, text = "t1", audioData = byteArrayOf(1, 2, 3))
        cache.put(voice = "v", style = null, text = "t2", audioData = byteArrayOf(4, 5, 6))

        cache.sizeBytes() shouldBe 6L

        cache.clearAll()
        cache.sizeBytes() shouldBe 0L
        cache.exists(voice = "v", style = null, text = "t1") shouldBe false
        cache.exists(voice = "v", style = null, text = "t2") shouldBe false
    }

    @Test
    fun `cache survives null style same as missing style`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        cache.put(voice = "冰糖", style = null, text = "你好", audioData = byteArrayOf(0xAB.toByte()))

        // get with null style must hit the same entry as a put with null style
        cache.get(voice = "冰糖", style = null, text = "你好") shouldBe byteArrayOf(0xAB.toByte())
        cache.exists(voice = "冰糖", style = null, text = "你好") shouldBe true
    }
}
