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

    @Test
    fun `eviction drops oldest entries when over capacity`(@TempDir tempDir: Path) {
        // 每个 payload 20 字节，上限 30 → 任意时刻最多驻留一个文件。
        val cache = TtsCache(tempDir.toFile(), maxBytes = 30L)

        cache.put(voice = "v", style = null, text = "first", audioData = ByteArray(20) { 1 })
        cache.put(voice = "v", style = null, text = "second", audioData = ByteArray(20) { 2 })
        cache.put(voice = "v", style = null, text = "third", audioData = ByteArray(20) { 3 })

        (cache.sizeBytes() <= 30L) shouldBe true
        cache.exists(voice = "v", style = null, text = "third") shouldBe true
        cache.exists(voice = "v", style = null, text = "first") shouldBe false
        // 刚写入的条目必须能完整读回
        cache.get(voice = "v", style = null, text = "third") shouldBe ByteArray(20) { 3 }
    }

    @Test
    fun `get refreshes recency so a hot entry survives eviction`(@TempDir tempDir: Path) {
        // 上限 40 → 恰好容纳两个 20 字节文件；写入第三个时需淘汰一个。
        val cache = TtsCache(tempDir.toFile(), maxBytes = 40L)

        cache.put(voice = "v", style = null, text = "A", audioData = ByteArray(20) { 1 })
        sleepQuietly()
        cache.put(voice = "v", style = null, text = "B", audioData = ByteArray(20) { 2 })
        sleepQuietly()
        // 命中 A，刷新 mtime —— A 成为"最近使用"，B 成为最久未使用。
        cache.get(voice = "v", style = null, text = "A") shouldBe ByteArray(20) { 1 }
        sleepQuietly()
        cache.put(voice = "v", style = null, text = "C", audioData = ByteArray(20) { 3 })

        cache.exists(voice = "v", style = null, text = "A") shouldBe true
        cache.exists(voice = "v", style = null, text = "B") shouldBe false
        cache.exists(voice = "v", style = null, text = "C") shouldBe true
    }

    @Test
    fun `atomic write leaves no temp files behind`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        repeat(5) { i ->
            cache.put(voice = "v", style = null, text = "t$i", audioData = ByteArray(10) { i.toByte() })
        }

        val leftovers = tempDir.toFile().walkTopDown()
            .filter { it.isFile && it.name.contains(".tmp-") }
            .toList()
        leftovers shouldBe emptyList()
    }

    @Test
    fun `does not evict the entry just written even when over capacity`(@TempDir tempDir: Path) {
        // 上限 10 < payload 20：已超限，但刚写入的文件受保护，必须保留。
        val cache = TtsCache(tempDir.toFile(), maxBytes = 10L)

        cache.put(voice = "v", style = null, text = "only", audioData = ByteArray(20) { 7 })

        cache.get(voice = "v", style = null, text = "only") shouldBe ByteArray(20) { 7 }
    }

    @Test
    fun `concurrent puts are safe and all entries round-trip`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile(), maxBytes = 1024L * 1024)
        val threads = (0 until 16).map { i ->
            Thread {
                repeat(4) { j ->
                    cache.put(voice = "v", style = null, text = "t-$i-$j", audioData = ByteArray(64) { i.toByte() })
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        for (i in 0 until 16) {
            for (j in 0 until 4) {
                cache.get(voice = "v", style = null, text = "t-$i-$j") shouldBe ByteArray(64) { i.toByte() }
            }
        }
    }

    @Test
    fun `overwriting an entry keeps size accounting exact`(@TempDir tempDir: Path) {
        // 淘汰判定改用增量维护的占用值；"覆盖写"若算错，后续淘汰就会失真。
        val cache = TtsCache(tempDir.toFile(), maxBytes = 100L)

        cache.put(voice = "v", style = null, text = "same", audioData = ByteArray(40) { 1 })
        cache.sizeBytes() shouldBe 40L

        cache.put(voice = "v", style = null, text = "same", audioData = ByteArray(10) { 2 })
        cache.sizeBytes() shouldBe 10L
        cache.get(voice = "v", style = null, text = "same") shouldBe ByteArray(10) { 2 }
    }

    @Test
    fun `incremental accounting keeps the cache under the cap`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile(), maxBytes = 100L)

        repeat(20) { i ->
            cache.put(voice = "v", style = null, text = "t$i", audioData = ByteArray(30) { i.toByte() })
        }

        (cache.sizeBytes() <= 100L) shouldBe true
        // 刚写入的条目必须完整可读（未被自身淘汰）
        cache.get(voice = "v", style = null, text = "t19") shouldBe ByteArray(30) { 19 }
    }

    @Test
    fun `sizeBytes stays exact after clearAll followed by new writes`(@TempDir tempDir: Path) {
        val cache = TtsCache(tempDir.toFile())
        cache.put(voice = "v", style = null, text = "a", audioData = ByteArray(7) { 1 })

        cache.clearAll()
        cache.put(voice = "v", style = null, text = "b", audioData = ByteArray(9) { 2 })

        // clearAll 若没重置内部占用缓存，这里会读到旧值
        cache.sizeBytes() shouldBe 9L
    }

    private fun sleepQuietly(ms: Long = 20L) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
