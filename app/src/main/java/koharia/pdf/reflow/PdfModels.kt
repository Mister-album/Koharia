package koharia.pdf.reflow

import kotlinx.serialization.Serializable

@Serializable
data class PdfBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right - left).coerceAtLeast(0f)
    val height get() = (bottom - top).coerceAtLeast(0f)
    fun intersects(other: PdfBox) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

@Serializable
data class PdfTextStyle(val font: String?, val size: Float, val weight: Int?, val italic: Boolean, val color: Int?)

@Serializable
data class PdfGlyph(
    val index: Int,
    val text: String,
    val box: PdfBox,
    val rawBox: PdfBox,
    val style: Int,
    val angle: Float,
)

@Serializable
data class PdfGraphic(val kind: Int, val box: PdfBox, val asset: String? = null)

@Serializable
data class PdfPageFacts(
    val index: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val cropBox: PdfBox,
    val styles: List<PdfTextStyle>,
    val glyphs: List<PdfGlyph>,
    val graphics: List<PdfGraphic>,
    val fallbackAsset: String? = null,
    val hasUnmappedText: Boolean = false,
    val annotations: List<PdfAnnotation> = emptyList(),
)

@Serializable
data class PdfAnnotation(val index: Int, val box: PdfBox, val text: String)

@Serializable
data class PdfOutline(val title: String, val page: Int, val level: Int)

@Serializable
data class PdfExtractionIndex(
    val pageCount: Int,
    val textCharacters: Int,
    val outlines: List<PdfOutline>,
    val availablePages: List<Int>? = null,
    val bodyFontSize: Float? = null,
)

@Serializable
data class PdfSourceSpan(val page: Int, val firstCharacter: Int, val lastCharacter: Int, val box: PdfBox)

@Serializable
data class PdfRun(
    val text: String,
    val style: PdfTextStyle,
    val source: PdfSourceSpan,
    val ruby: PdfRuby? = null,
    val script: String? = null,
    val noteId: String? = null,
)

@Serializable
data class PdfRuby(val text: String, val source: PdfSourceSpan, val characters: List<Int> = emptyList())

data class PdfFootnote(val id: String, val marker: String, val text: String, val source: PdfSourceSpan)

@Serializable
data class PdfBlock(
    val id: String,
    val kind: String,
    val runs: List<PdfRun> = emptyList(),
    val asset: String? = null,
    val source: PdfSourceSpan,
    val confidence: Float = 1f,
)

@Serializable
data class PdfMappedBlock(val id: String, val href: String, val source: PdfSourceSpan, val context: String)

@Serializable
data class PdfReflowManifest(
    val revision: String,
    val sourceHash: String,
    val pageCount: Int,
    val blocks: List<PdfMappedBlock>,
    val isComplete: Boolean = true,
    val chunkSize: Int = 0,
    val availablePages: List<Int> = emptyList(),
)
