package koharia.tts.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import koharia.epub.session.EpubReaderSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ChapterTextExtractorTest {

    // ===== plainTextFrom: XHTML bytes -> plain text =====

    @Test
    fun `plainTextFrom strips HTML tags and returns plain text`() {
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <h1>Chapter 1</h1>
                <p>Hello <em>world</em>. This is a test.</p>
                <p>Second paragraph here.</p>
              </body>
            </html>
        """.trimIndent()

        val result = ChapterTextExtractor.plainTextFrom(xhtml.toByteArray(Charsets.UTF_8), baseUri = "ch1.xhtml")

        result.shouldNotBe(null)
        result!! shouldContain "Hello"
        result shouldContain "world"
        result shouldContain "This is a test"
        result shouldNotContain "<p>"
        result shouldNotContain "<em>"
        result shouldNotContain "<h1>"
    }

    @Test
    fun `plainTextFrom preserves paragraph breaks as newlines`() {
        val xhtml = """
            <html><body>
              <p>First paragraph.</p>
              <p>Second paragraph.</p>
              <p>Third paragraph.</p>
            </body></html>
        """.trimIndent()

        val result = ChapterTextExtractor.plainTextFrom(xhtml.toByteArray(Charsets.UTF_8), baseUri = "ch1.xhtml")

        result.shouldNotBe(null)
        // Jsoup's wholeText() inserts newlines between block elements.
        val lines = result!!.lineSequence().filter { it.isNotBlank() }.toList()
        lines.size shouldBe 3
        lines[0] shouldContain "First paragraph"
        lines[1] shouldContain "Second paragraph"
        lines[2] shouldContain "Third paragraph"
    }

    @Test
    fun `plainTextFrom detects UTF-16 BOM from byte stream`() {
        val xhtml = "<html><body><p>你好世界。</p></body></html>"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xhtml.toByteArray(Charsets.UTF_16LE)

        val result = ChapterTextExtractor.plainTextFrom(bytes, baseUri = "ch1.xhtml")

        result shouldContain "你好"
    }

    @Test
    fun `plainTextFrom returns null for blank body`() {
        val result = ChapterTextExtractor.plainTextFrom(
            "<html><body></body></html>".toByteArray(Charsets.UTF_8),
            baseUri = "ch1.xhtml",
        )

        result shouldBe null
    }

    @Test
    fun `plainTextFrom tolerates empty bytes without throwing`() {
        val result = ChapterTextExtractor.plainTextFrom(ByteArray(0), baseUri = "ch1.xhtml")

        // The contract is "doesn't throw"; empty input yields null (blank body).
        result shouldBe null
    }

    // ===== plainTextFrom: non-rendered element filtering (TTS highlight offset alignment) =====

    @Test
    fun `plainTextFrom strips nav epub-type toc so TTS offsets align with WebView DOM`() {
        // 章节开头有 TOC 列表（Readium WebView 用自带导航 UI 覆盖，不渲染）；
        // 整段必须从提取结果中移除，否则 TTS 句子高亮的字符偏移会跑偏。
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <h1>目录</h1>
                  <ol>
                    <li><a href="#ch1">第一章 楔子</a></li>
                    <li><a href="#ch2">第二章 起始</a></li>
                  </ol>
                </nav>
                <h1>第一章 楔子</h1>
                <p>这是正文第一段。</p>
              </body>
            </html>
        """.trimIndent()

        val result = ChapterTextExtractor.plainTextFrom(xhtml.toByteArray(Charsets.UTF_8), baseUri = "ch1.xhtml")

        result.shouldNotBe(null)
        // TOC 标题、链接文字不应出现
        result!! shouldNotContain "目录"
        result shouldNotContain "第一章 楔子</a>"
        // 正文必须保留
        result shouldContain "这是正文第一段"
        // 正文必须出现在提取结果的最前面（即没有 TOC 前缀）
        result.trim().startsWith("第一章") shouldBe true
    }

    @Test
    fun `plainTextFrom strips script style noscript hidden aria-hidden`() {
        val xhtml = """
            <html><body>
              <script>var x = "ignore me";</script>
              <style>p { color: red; }</style>
              <noscript>fallback content</noscript>
              <p hidden>hidden paragraph</p>
              <p aria-hidden="true">aria-hidden paragraph</p>
              <p>visible paragraph</p>
            </body></html>
        """.trimIndent()

        val result = ChapterTextExtractor.plainTextFrom(xhtml.toByteArray(Charsets.UTF_8), baseUri = "ch1.xhtml")

        result.shouldNotBe(null)
        result!! shouldNotContain "ignore me"
        result shouldNotContain "color: red"
        result shouldNotContain "fallback content"
        result shouldNotContain "hidden paragraph"
        result shouldNotContain "aria-hidden paragraph"
        result shouldContain "visible paragraph"
    }

    @Test
    fun `plainTextFrom strips inline display none visibility hidden`() {
        val xhtml = """
            <html><body>
              <p style="display:none">display none</p>
              <p style="display: none">display none with space</p>
              <p style="visibility:hidden">visibility hidden</p>
              <p style="visibility: hidden">visibility hidden with space</p>
              <p>kept</p>
            </body></html>
        """.trimIndent()

        val result = ChapterTextExtractor.plainTextFrom(xhtml.toByteArray(Charsets.UTF_8), baseUri = "ch1.xhtml")

        result.shouldNotBe(null)
        result!! shouldNotContain "display none"
        result shouldNotContain "visibility hidden"
        result shouldContain "kept"
    }

    @Test
    fun `plainTextFrom keeps aside footnotes and headers (Readium renders them)`() {
        // 反向回归：asides（footnote 行内末尾）、header（页头）、h1（标题）
        // 在 Readium 默认样式下都应保留在提取结果中。
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <header><h1>Chapter Title</h1></header>
                <p>正文段落。</p>
                <aside epub:type="footnote">脚注内容</aside>
              </body>
            </html>
        """.trimIndent()

        val result = ChapterTextExtractor.plainTextFrom(xhtml.toByteArray(Charsets.UTF_8), baseUri = "ch1.xhtml")

        result.shouldNotBe(null)
        result!! shouldContain "Chapter Title"
        result shouldContain "正文段落"
        result shouldContain "脚注内容"
    }

    // ===== extract: session wiring =====

    @Test
    fun `extract returns null when no live session exists`() {
        val repository = mockk<EpubReaderSessionRepository>()
        every { repository.getForPagination(any()) } returns null
        val extractor = ChapterTextExtractor(sessionRepository = repository)

        runBlocking {
            extractor.extract(chapterId = 1L, href = "ch1.xhtml") shouldBe null
        }
    }
}
