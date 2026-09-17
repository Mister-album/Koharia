package koharia.tts.reader

import koharia.epub.session.EpubReaderSessionRepository
import logcat.LogPriority
import org.jsoup.Jsoup
import org.readium.r2.shared.util.getOrElse
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream

/**
 * 把当前 EPUB 章节的 XHTML 提取为纯文本。
 *
 * 数据流：
 *   EpubReaderSessionRepository.getForPagination(chapterId) → 活跃会话
 *   → publication.readingOrder 按 href 匹配 Link
 *   → publication.get(link).read() 拿原始字节（远程走已缓存的 HTTP 客户端，本地走文件）
 *   → Jsoup 解析（自动识别 XML 声明编码）
 *   → body().wholeText() — 但先过滤掉 WebView 不渲染的元素，让偏移与 DOM 对齐
 *
 * 不再直接查 EpubCacheManager：缓存键是 Readium 请求的完整绝对 URL + 指纹
 * publicationKey，离线重建必然 miss；而会话资源读取天然覆盖远程/本地/整书缓存
 * 三条路径。
 */
class ChapterTextExtractor(
    private val sessionRepository: EpubReaderSessionRepository = Injekt.get(),
) {

    /**
     * @param chapterId 章节数据库 id（会话仓库的键）
     * @param href 当前章节 href（相对或绝对均可，按尾段宽松匹配）
     * @return 章节纯文本，无 HTML 标签，保留段落换行；找不到返回 null
     */
    suspend fun extract(chapterId: Long, href: String): String? {
        logcat(LogPriority.INFO) { "[ChapterTextExtractor] extract START chapterId=$chapterId href='$href'" }
        val session = sessionRepository.getForPagination(chapterId)
        if (session == null) {
            logcat(LogPriority.WARN) {
                "[ChapterTextExtractor] no live reader session for chapterId=$chapterId"
            }
            return null
        }
        logcat(LogPriority.INFO) { "[ChapterTextExtractor] session ok; looking up link" }
        val publication = session.publication
        val wanted = href.resourceKey()
        val link = publication.readingOrder.firstOrNull { candidate ->
            val key = candidate.href.toString().resourceKey()
            key.isNotEmpty() && (key == wanted || key.endsWith("/$wanted") || wanted.endsWith("/$key"))
        }
            ?: publication.readingOrder.firstOrNull().takeIf { wanted.isEmpty() }
        if (link == null) {
            logcat(LogPriority.WARN) {
                "[ChapterTextExtractor] href=$href not in readingOrder(${publication.readingOrder.size}) chapterId=$chapterId"
            }
            return null
        }
        logcat(LogPriority.INFO) { "[ChapterTextExtractor] link ok href=${link.href}; reading bytes" }

        val bytes = try {
            val resource = publication.get(link) ?: return null.also {
                logcat(LogPriority.WARN) { "[ChapterTextExtractor] publication.get returned null for $href" }
            }
            try {
                resource.read().getOrElse { error ->
                    logcat(LogPriority.ERROR) { "[ChapterTextExtractor] failed to read $href: $error" }
                    null
                }
            } finally {
                resource.close()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[ChapterTextExtractor] read error for $href" }
            null
        } ?: return null
        logcat(LogPriority.INFO) { "[ChapterTextExtractor] bytes read len=${bytes.size}; parsing" }

        return plainTextFrom(bytes, baseUri = link.href.toString())
    }

    /** 与 KomgaEpubPublicationService 的口径一致：去 query/fragment、去前导斜杠。 */
    private fun String.resourceKey(): String =
        substringBefore('#')
            .substringBefore('?')
            .trimStart('/')

    companion object {

        /**
         * 与 Readium WebView 不渲染的元素保持一致。
         * 这里列出的元素会被 [plainTextFrom] 在提取前移除，目的是让生成的字符偏移与
         * WebView DOM tree walker 计算的偏移一致，从而 TTS 句子高亮能命中。
         *
         * 来源：
         * - `<script>` / `<style>` / `<noscript>` 永远不渲染为可见文本
         * - `[hidden]` HTML 标准属性
         * - `display:none` / `visibility:hidden` 内联样式（[hidden] 也是）
         * - `<nav epub:type="toc">` / `<nav epub:type="landmarks">` /
         *   `<nav epub:type="page-list">` Readium 用自带导航 UI 覆盖
         * - `aria-hidden="true"` ARIA 语义上的不可见（Readium 的 footnote popup 容器常用）
         *
         * **不在**这里过滤的元素（被故意保留）：
         * - `<header>` / `<footer>`：Readium 通常渲染为页头页脚，应保留
         * - `<aside>`：Readium 默认将 footnote 渲染为行内末尾，保留
         * - `<h1>`–`<h6>`：标题，保留
         */
        internal val NON_RENDERED_SELECTOR: String =
            "script, style, noscript, " +
                "nav[epub:type=\"toc\"], nav[epub:type=\"landmarks\"], nav[epub:type=\"page-list\"], " +
                "[hidden], [aria-hidden=\"true\"], " +
                "[style*=\"display:none\"], [style*=\"display: none\"], " +
                "[style*=\"visibility:hidden\"], [style*=\"visibility: hidden\"]"

        /**
         * XHTML 字节 → 纯文本。编码由 Jsoup 从 BOM/XML 声明自动探测。
         * 解析失败或正文为空返回 null。
         *
         * 关键步骤：从 DOM 中移除 WebView 不渲染的元素（[NON_RENDERED_SELECTOR]），
         * 再用 [org.jsoup.nodes.Element.wholeText] 拼字符串。偏移必须与
         * `EpubReaderFragment` 中的 JS tree walker 在 DOM 上计算出的偏移一致，
         * 否则 TTS 句子高亮会 `range-not-found`。
         */
        internal fun plainTextFrom(bytes: ByteArray, baseUri: String): String? {
            val startMs = System.currentTimeMillis()
            return try {
                logcat(LogPriority.INFO) { "[ChapterTextExtractor] Jsoup.parse START (${bytes.size} bytes)" }
                val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, baseUri)
                logcat(LogPriority.INFO) {
                    "[ChapterTextExtractor] Jsoup.parse DONE in ${System.currentTimeMillis() - startMs}ms"
                }
                val body = doc.body() ?: return null
                val removeMs = System.currentTimeMillis()
                body.select(NON_RENDERED_SELECTOR).remove()
                logcat(LogPriority.INFO) {
                    "[ChapterTextExtractor] NON_RENDERED select+remove DONE in ${System.currentTimeMillis() - removeMs}ms"
                }
                val wholeMs = System.currentTimeMillis()
                val text = body.wholeText().trim().takeIf { it.isNotEmpty() }
                logcat(LogPriority.INFO) {
                    "[ChapterTextExtractor] wholeText DONE in ${System.currentTimeMillis() - wholeMs}ms, " +
                        "final len=${text?.length ?: 0}"
                }
                text
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) {
                    "[ChapterTextExtractor] failed to parse $baseUri"
                }
                null
            }
        }
    }
}
