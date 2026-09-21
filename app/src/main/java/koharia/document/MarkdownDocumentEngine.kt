package koharia.document

import android.content.Context
import android.text.Html
import com.hippo.unifile.UniFile
import koharia.media.LocalMediaFormats

object MarkdownDocumentEngine : DocumentEngine {
    override val id: String = "markdown"
    override val extensions: Set<String> = LocalMediaFormats.markdown.extensions

    override fun open(
        context: Context,
        file: UniFile,
        settings: DocumentRenderSettings,
    ): DocumentSession {
        val bytes = file.openInputStream().use { it.readAtMost(MAX_MARKDOWN_BYTES + 1) }
        if (bytes.size > MAX_MARKDOWN_BYTES) {
            throw DocumentEngineException("Markdown file is larger than the supported 16 MiB limit")
        }
        val markdown = decodeText(bytes)

        val html = MarkdownHtmlRenderer.render(markdown)
        // Headings are extracted from the rendered HTML (not the post-`Html.fromHtml` Spanned)
        // because Android's legacy HTML parser strips heading structure — only the
        // pre-rendered HTML retains the `<h1>`–`<h6>` tags we want to enumerate.
        val headingTitles = MarkdownHtmlRenderer.extractHeadings(html)

        @Suppress("DEPRECATION")
        val rendered = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        val text: CharSequence = rendered.takeIf { it.isNotBlank() } ?: markdown
        return TextDocumentContent(
            context = context,
            text = text,
            metadata = DocumentMetadata(title = file.name?.substringBeforeLast('.')),
            headingTitles = headingTitles,
        ).open(settings)
    }
}

/**
 * Deliberately lower than [MAX_TEXT_BYTES] (64 MiB). The Markdown path holds several large
 * representations at once — the raw bytes, the decoded UTF-16 string (~2×), the intellij-markdown
 * AST, the generated HTML, the `Html.fromHtml` Spanned, plus the retained `publisherText` and its
 * `toString()` copy in [TextDocumentContent] — so peak usage is roughly 5–8× the file size. At
 * 64 MiB that is hundreds of MiB and the process would die with an OutOfMemoryError instead of the
 * graceful [DocumentEngineException] this limit is meant to produce. A full-length book in Markdown
 * is well under 16 MiB.
 */
private const val MAX_MARKDOWN_BYTES = 16 * 1024 * 1024
