package koharia.tts.player

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrimGaplessPcmTest {

    /** 把 N 个 16-bit 采样拼成 PCM 字节（小端），便于肉眼验证 trim 行为。 */
    private fun pcmOf(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toShort()
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** 从 PCM 字节读回采样（用于对称校验）。channels > 1 时一帧内连续读取所有声道。 */
    private fun samplesOf(pcm: ByteArray, channels: Int = 1): List<Int> {
        val frameBytes = 2 * channels
        require(pcm.size % frameBytes == 0) {
            "pcm.size=${pcm.size} not a multiple of frameBytes=$frameBytes"
        }
        val out = mutableListOf<Int>()
        var i = 0
        while (i < pcm.size) {
            var sampleBase = i
            repeat(channels) {
                val lo = pcm[sampleBase].toInt() and 0xFF
                val hi = pcm[sampleBase + 1].toInt()
                out += (hi shl 8) or lo
                sampleBase += 2
            }
            i += frameBytes
        }
        return out
    }

    @Test
    fun `mono - trims leading delay samples and trailing padding samples`() {
        // 10 个采样 [0..9]，delay=4，padding=2 → 保留 [4, 5, 6, 7]
        val pcm = pcmOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 4, paddingSamples = 2)

        samplesOf(trimmed) shouldBe listOf(4, 5, 6, 7)
    }

    @Test
    fun `mono - keeps zero delay and zero padding intact`() {
        val pcm = pcmOf(0, 1, 2, 3, 4)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 0, paddingSamples = 0)

        samplesOf(trimmed) shouldBe listOf(0, 1, 2, 3, 4)
    }

    @Test
    fun `mono - zero delay with trailing padding only`() {
        val pcm = pcmOf(0, 1, 2, 3, 4, 5)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 0, paddingSamples = 2)

        samplesOf(trimmed) shouldBe listOf(0, 1, 2, 3)
    }

    @Test
    fun `mono - delay only with zero padding`() {
        val pcm = pcmOf(0, 1, 2, 3, 4, 5)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 2, paddingSamples = 0)

        samplesOf(trimmed) shouldBe listOf(2, 3, 4, 5)
    }

    @Test
    fun `mono - clip shorter than delay plus padding returns original PCM as fallback`() {
        // 5 samples total, delay=4 padding=2 → would be -1 frame. Return original.
        val pcm = pcmOf(0, 1, 2, 3, 4)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 4, paddingSamples = 2)

        samplesOf(trimmed) shouldBe listOf(0, 1, 2, 3, 4)
    }

    @Test
    fun `mono - clip exactly equal to delay plus padding returns original PCM as fallback`() {
        // 5 samples = 4 delay + 2 padding + (-1) remainder → falls into "would-be-empty" path
        val pcm = pcmOf(0, 1, 2, 3, 4)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 3, paddingSamples = 2)

        samplesOf(trimmed) shouldBe listOf(0, 1, 2, 3, 4)
    }

    @Test
    fun `stereo - interleaved samples are trimmed symmetrically`() {
        // stereo frame = [L, R] = 4 bytes; 5 frames = 10 samples [L0, R0, L1, R1, ..., L4, R4]
        val pcm = pcmOf(0, 100, 1, 101, 2, 102, 3, 103, 4, 104)
        val trimmed = trimGaplessPcm(pcm, channels = 2, delaySamples = 1, paddingSamples = 1)

        // delay=1 frame from front (drop [L0, R0]) + padding=1 frame from back (drop [L4, R4])
        // Keep frames 1..3 = [L1, R1, L2, R2, L3, R3] = 6 samples
        samplesOf(trimmed, channels = 2) shouldBe listOf(1, 101, 2, 102, 3, 103)
    }

    @Test
    fun `large realistic config - mono trim keeps most of the audio`() {
        // ~3 seconds at 24kHz mono = 72000 frames; trim should keep 72000 - 576 - 768 = 70656 frames
        val pcm = ByteArray(72000 * 2)
        for (i in 0 until 72000) {
            pcm[i * 2] = (i and 0xFF).toByte()
            pcm[i * 2 + 1] = ((i shr 8) and 0xFF).toByte()
        }
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 576, paddingSamples = 768)

        // 72000 - 576 - 768 = 70656 samples
        trimmed.size shouldBe (70656 * 2)
        // First sample should be original frame 576 (low byte = 576 mod 256 = 64)
        trimmed[0].toInt() and 0xFF shouldBe (576 and 0xFF)
        // Last sample should be original frame 71231 (the (70655)th kept frame)
        val lastOriginalFrame = 72000 - 768 - 1
        trimmed[trimmed.size - 2].toInt() and 0xFF shouldBe (lastOriginalFrame and 0xFF)
    }

    @Test
    fun `negative delay and padding are treated as zero`() {
        val pcm = pcmOf(10, 20, 30, 40)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = -5, paddingSamples = -3)

        samplesOf(trimmed) shouldBe listOf(10, 20, 30, 40)
    }

    @Test
    fun `empty pcm is returned as-is`() {
        val trimmed = trimGaplessPcm(
            pcm = ByteArray(0),
            channels = 1,
            delaySamples = 100,
            paddingSamples = 100,
        )
        trimmed.size shouldBe 0
    }

    @Test
    fun `non-frame-aligned pcm is returned as-is to avoid sample mis-alignment`() {
        // 5 bytes: not a multiple of 2 (mono) or 4 (stereo)
        val pcm = byteArrayOf(0, 1, 2, 3, 4)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 1, paddingSamples = 1)

        trimmed shouldBe pcm
    }

    @Test
    fun `padding larger than total returns original PCM as fallback`() {
        val pcm = pcmOf(1, 2, 3)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 0, paddingSamples = 100)

        samplesOf(trimmed) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `delay plus padding equals exactly the total keeps nothing - returns original`() {
        val pcm = pcmOf(1, 2, 3, 4, 5)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 3, paddingSamples = 2)

        // keepToFrame = 5 - 2 = 3, keepFromFrame = 3 → keepToFrame <= keepFromFrame
        // Falls back to original
        samplesOf(trimmed) shouldBe listOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `zero channels falls back to mono assumption`() {
        // Defensive: channels <= 0 is coerced to 1 so we don't divide by zero
        val pcm = pcmOf(0, 1, 2, 3, 4, 5)
        val trimmed = trimGaplessPcm(pcm, channels = 0, delaySamples = 1, paddingSamples = 1)

        samplesOf(trimmed, channels = 1) shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun `large realistic config - trim is fast and allocates one array`() {
        // ~5 seconds at 24kHz = 120000 frames; trim should keep most of it
        val pcm = ByteArray(120000 * 2)
        val trimmed = trimGaplessPcm(
            pcm = pcm,
            channels = 1,
            delaySamples = 576,
            paddingSamples = 768,
        )
        // 120000 - 576 - 768 = 118656
        trimmed.size shouldBe (118656 * 2)
    }

    @Test
    fun `samples returned are not shared with input`() {
        val pcm = pcmOf(0, 1, 2, 3, 4, 5)
        val trimmed = trimGaplessPcm(pcm, channels = 1, delaySamples = 1, paddingSamples = 1)

        trimmed shouldHaveSize 4 * 2
        // Mutating input must not affect output
        pcm[0] = 0x55
        trimmed[0].toInt() shouldBe 1
    }
}
