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
            throw DocumentEngineException("Markdown file is larger than the supported 64 MiB limit")
        }
        val markdown = decodeText(bytes)

        @Suppress("DEPRECATION")
        val rendered = Html.fromHtml(MarkdownHtmlRenderer.render(markdown), Html.FROM_HTML_MODE_LEGACY)
        val text: CharSequence = rendered.takeIf { it.isNotBlank() } ?: markdown
        return TextDocumentContent(
            context = context,
            text = text,
            metadata = DocumentMetadata(title = file.name?.substringBeforeLast('.')),
        ).open(settings)
    }
}

private const val MAX_MARKDOWN_BYTES = 64 * 1024 * 1024
