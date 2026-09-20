package koharia.epub.locator

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EpubHrefDecoderTest {

    @Test
    fun `plain path without fragment keeps path and null fragment`() {
        val decoded = EpubHrefDecoder.decode("ch001.xhtml")

        decoded.path shouldBe "ch001.xhtml"
        decoded.fragment.shouldBeNull()
    }

    @Test
    fun `literal hash splits path and fragment`() {
        val decoded = EpubHrefDecoder.decode("ch001.xhtml#sec2")

        decoded.path shouldBe "ch001.xhtml"
        decoded.fragment shouldBe "sec2"
    }

    @Test
    fun `percent encoded hash is treated as the fragment delimiter`() {
        // 本仓库同源 EPUB 的真实形态：分隔符写成 %23，片段是未编码的中文
        val decoded = EpubHrefDecoder.decode("ch003.xhtml%23简单")

        decoded.path shouldBe "ch003.xhtml"
        decoded.fragment shouldBe "简单"
    }

    @Test
    fun `plus sign is preserved literally and never becomes a space`() {
        val decoded = EpubHrefDecoder.decode("a+b.xhtml")

        decoded.path shouldBe "a+b.xhtml"
        decoded.fragment.shouldBeNull()
    }

    @Test
    fun `percent escapes in path are decoded once`() {
        EpubHrefDecoder.decode("a%2Fb.xhtml").path shouldBe "a/b.xhtml"
        EpubHrefDecoder.decode("a%3Fb.xhtml").path shouldBe "a?b.xhtml"
    }

    @Test
    fun `fragment percent escapes are decoded`() {
        val decoded = EpubHrefDecoder.decode("ch1.xhtml#a%20b")

        decoded.path shouldBe "ch1.xhtml"
        decoded.fragment shouldBe "a b"
    }

    @Test
    fun `blank fragment is reported as null`() {
        val decoded = EpubHrefDecoder.decode("ch1.xhtml#")

        decoded.path shouldBe "ch1.xhtml"
        decoded.fragment.shouldBeNull()
    }

    @Test
    fun `malformed escapes do not throw and are preserved`() {
        EpubHrefDecoder.decode("ch1%zz.xhtml").path shouldBe "ch1%zz.xhtml"
        EpubHrefDecoder.decode("ch1%").path shouldBe "ch1%"
    }

    @Test
    fun `supplementary characters survive a mixed literal and escape fragment`() {
        // emoji 是 UTF-16 代理对：按 Char 逐个编码会把两半各写成一个孤立的 code unit（→ '?'）
        val emoji = "\uD83D\uDE00"
        val decoded = EpubHrefDecoder.decode("ch1.xhtml#a%20$emoji")

        decoded.path shouldBe "ch1.xhtml"
        decoded.fragment shouldBe "a $emoji"
    }

    @Test
    fun `supplementary characters survive in the path when an escape is present`() {
        val emoji = "\uD83D\uDE00"

        EpubHrefDecoder.decode("$emoji%2Fb.xhtml").path shouldBe "$emoji/b.xhtml"
    }

    @Test
    fun `empty input yields empty path and null fragment`() {
        val decoded = EpubHrefDecoder.decode("")

        decoded.path shouldBe ""
        decoded.fragment.shouldBeNull()
    }
}
