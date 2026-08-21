package koharia.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import com.hippo.unifile.UniFile
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.max

/** Rendering values shared by document engines and the existing EPUB settings sheet. */
data class DocumentRenderSettings(
    val backgroundColor: Int = Color.WHITE,
    val textColor: Int = Color.BLACK,
    val fontSizeScale: Float = 1f,
    /** Document pages use a readable bitmap-rendering base independent of EPUB's CSS default. */
    val baseFontSizeSp: Float = DEFAULT_BASE_FONT_SIZE_SP,
    val lineHeight: Float = 1.7f,
    val paragraphSpacing: Float = 0.05f,
    val paragraphIndent: Float = 2f,
    val pageMargins: Float = 1f,
    val verticalMargins: Float = 1f,
    val textAlignment: TextAlignment = TextAlignment.START,
    val publisherStyles: Boolean = true,
    val typeface: Typeface = Typeface.DEFAULT,
) {
    enum class TextAlignment {
        START,
        LEFT,
        RIGHT,
        JUSTIFY,
    }

    companion object {
        const val DEFAULT_BASE_FONT_SIZE_SP = 24f
        val DEFAULT: DocumentRenderSettings by lazy { DocumentRenderSettings() }
    }
}

interface DocumentEngine {
    val id: String
    val extensions: Set<String>

    fun open(
        context: Context,
        file: UniFile,
        settings: DocumentRenderSettings = DocumentRenderSettings.DEFAULT,
    ): DocumentSession
}

interface DocumentSession : Closeable {
    val metadata: DocumentMetadata
    val pageCount: Int

    fun page(index: Int): DocumentPage
}

interface ReflowableDocumentSession : DocumentSession {
    suspend fun reflow(settings: DocumentRenderSettings): DocumentSession
}

interface DocumentPage {
    val index: Int
    val locator: DocumentLocator
        get() = DocumentLocator(index)

    fun render(): Bitmap
}

data class DocumentLocator(
    val pageIndex: Int,
    val anchor: String? = null,
)

data class DocumentProgress(
    val locator: DocumentLocator,
    val pageCount: Int,
) {
    val fraction: Float
        get() = if (pageCount <= 1) 1f else (locator.pageIndex + 1f) / pageCount
}

data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
)

class DocumentEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

object DocumentEngines {
    private val engines = mutableListOf<DocumentEngine>(
        TextDocumentEngine,
        MobiDocumentEngine,
        DjvuDocumentEngine,
    )

    @Synchronized
    fun register(engine: DocumentEngine) {
        engines.removeAll { it.id == engine.id }
        engines += engine
    }

    @Synchronized
    fun forExtension(extension: String?): DocumentEngine? {
        val normalized = extension?.lowercase() ?: return null
        return engines.firstOrNull { normalized in it.extensions }
    }

    fun open(
        context: Context,
        file: UniFile,
        settings: DocumentRenderSettings = DocumentRenderSettings.DEFAULT,
    ): DocumentSession {
        val extension = file.name.orEmpty().substringAfterLast('.', "")
        return forExtension(extension)?.open(context, file, settings)
            ?: throw DocumentEngineException("No document engine for .$extension")
    }
}

object TextDocumentEngine : DocumentEngine {
    override val id: String = "text"
    override val extensions: Set<String> = LocalMediaFormats.text.extensions

    override fun open(
        context: Context,
        file: UniFile,
        settings: DocumentRenderSettings,
    ): DocumentSession {
        val bytes = file.openInputStream().use { input ->
            input.readAtMost(MAX_TEXT_BYTES + 1)
        }
        if (bytes.size > MAX_TEXT_BYTES) {
            throw DocumentEngineException("Text file is larger than the supported 64 MiB limit")
        }
        return TextDocumentContent(
            context = context,
            text = decodeText(bytes),
            metadata = DocumentMetadata(title = file.name?.substringBeforeLast('.')),
        ).open(settings)
    }
}

object MobiDocumentEngine : DocumentEngine {
    override val id: String = "mobi"
    override val extensions: Set<String> = LocalMediaFormats.mobi.extensions

    override fun open(
        context: Context,
        file: UniFile,
        settings: DocumentRenderSettings,
    ): DocumentSession {
        val document = parse(file)
        return TextDocumentContent(
            context = context,
            text = document.text,
            metadata = document.toMetadata(file),
        ).open(settings)
    }

    fun readMetadata(file: UniFile): DocumentMetadata = parse(file).toMetadata(file)

    private fun parse(file: UniFile): ParsedMobi {
        val bytes = file.openInputStream().use { it.readAtMost(MAX_MOBI_BYTES + 1) }
        if (bytes.size > MAX_MOBI_BYTES) {
            throw DocumentEngineException("MOBI file is larger than the supported 256 MiB limit")
        }
        val document = try {
            MobiParser.parse(bytes)
        } catch (error: DocumentEngineException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw DocumentEngineException("Invalid MOBI document", error)
        }
        return document
    }

    private fun ParsedMobi.toMetadata(file: UniFile): DocumentMetadata = DocumentMetadata(
        title = title ?: file.name?.substringBeforeLast('.'),
        author = author,
    )
}

object DjvuDocumentEngine : DocumentEngine {
    override val id: String = "djvu"
    override val extensions: Set<String> = LocalMediaFormats.djvu.extensions

    override fun open(
        context: Context,
        file: UniFile,
        settings: DocumentRenderSettings,
    ): DocumentSession {
        return DjvuWasmDocumentSession.open(context, file)
    }
}

private class TextDocumentContent(
    context: Context,
    text: CharSequence,
    val metadata: DocumentMetadata,
) {
    val density = context.resources.displayMetrics.density
    val pageWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(320)
    val pageHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(480)
    private val publisherText = normalizeDocumentText(text)
    private val plainText = publisherText.toString()

    private val paginationCache = object : LinkedHashMap<DocumentPaginationLayoutSnapshot, List<CharSequence>>(
        PAGINATION_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<DocumentPaginationLayoutSnapshot, List<CharSequence>>,
        ): Boolean = size > PAGINATION_CACHE_SIZE
    }

    fun open(
        settings: DocumentRenderSettings,
        cancellationCheck: () -> Unit = {},
    ): TextDocumentSession {
        val key = DocumentPaginationLayoutSnapshot(settings)
        val cachedPages = synchronized(paginationCache) { paginationCache[key] }
        if (cachedPages != null) {
            return TextDocumentSession(this, settings, cachedPages)
        }

        val text = if (settings.publisherStyles) publisherText else plainText
        val paginator = TextDocumentSession(this, settings, emptyList())
        val pages = paginator.paginate(text, cancellationCheck)
        cancellationCheck()
        synchronized(paginationCache) {
            paginationCache[key] = pages
        }
        return TextDocumentSession(this, settings, pages)
    }

    private companion object {
        const val PAGINATION_CACHE_SIZE = 3
    }
}

internal data class DocumentPaginationLayoutSnapshot(
    val baseFontSizeSp: Float,
    val fontSizeScale: Float,
    val lineHeight: Float,
    val paragraphSpacing: Float,
    val paragraphIndent: Float,
    val pageMargins: Float,
    val verticalMargins: Float,
    val textAlignment: DocumentRenderSettings.TextAlignment,
    val publisherStyles: Boolean,
    val typefaceIdentity: Int,
) {
    constructor(settings: DocumentRenderSettings) : this(
        baseFontSizeSp = settings.baseFontSizeSp,
        fontSizeScale = settings.fontSizeScale,
        lineHeight = settings.lineHeight,
        paragraphSpacing = settings.paragraphSpacing,
        paragraphIndent = settings.paragraphIndent,
        pageMargins = settings.pageMargins,
        verticalMargins = settings.verticalMargins,
        textAlignment = settings.textAlignment,
        publisherStyles = settings.publisherStyles,
        typefaceIdentity = System.identityHashCode(settings.typeface),
    )
}

private class TextDocumentSession(
    private val content: TextDocumentContent,
    private val settings: DocumentRenderSettings,
    private val pages: List<CharSequence>,
) : ReflowableDocumentSession {
    override val metadata: DocumentMetadata = content.metadata
    private val density = content.density
    private val pageWidth = content.pageWidth
    private val pageHeight = content.pageHeight
    private val horizontalPadding = dp(24f * settings.pageMargins.coerceIn(0f, 4f))
    private val verticalPadding = dp(28f * settings.verticalMargins.coerceIn(0f, 4f))
    private val textWidth = (pageWidth - horizontalPadding * 2).coerceAtLeast(1)
    private val textHeight = (pageHeight - verticalPadding * 2).coerceAtLeast(1)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settings.textColor
        textSize = settings.baseFontSizeSp.coerceAtLeast(1f) *
            settings.fontSizeScale.coerceIn(0.5f, 3f) * density
        typeface = settings.typeface
    }
    override val pageCount: Int = pages.size

    override fun page(index: Int): DocumentPage {
        return TextDocumentPage(index, pages.getOrNull(index) ?: error("Invalid document page"))
    }

    override fun close() = Unit

    override suspend fun reflow(settings: DocumentRenderSettings): DocumentSession {
        val coroutineContext = currentCoroutineContext()
        return content.open(settings) { coroutineContext.ensureActive() }
    }

    fun paginate(
        text: CharSequence,
        cancellationCheck: () -> Unit,
    ): List<CharSequence> {
        if (text.isBlank()) return listOf("")

        val lineHeight = (textPaint.textSize * settings.lineHeight.coerceIn(0.8f, 3f)).coerceAtLeast(1f)
        val linesPerPage = max(1, (textHeight / lineHeight).toInt())
        val charactersPerLine = max(16, (textWidth / (textPaint.textSize * 0.55f)).toInt())
        val estimatedPageCharacters = (linesPerPage * charactersPerLine).coerceAtLeast(256)
        val result = mutableListOf<CharSequence>()
        var offset = 0
        while (offset < text.length) {
            cancellationCheck()
            val pageEnd = findPageEnd(
                text,
                offset,
                estimatedPageCharacters,
                linesPerPage,
                cancellationCheck,
            )
            result += text.subSequence(offset, pageEnd)
            offset = pageEnd
        }
        return result
    }

    /** Finds the largest source slice that fits the bitmap using Android's actual line breaker. */
    private fun findPageEnd(
        text: CharSequence,
        start: Int,
        estimate: Int,
        maxLines: Int,
        cancellationCheck: () -> Unit,
    ): Int {
        val remaining = text.length - start
        var upper = minOf(text.length, start + estimate)
        while (upper < text.length && fits(text, start, upper, maxLines, cancellationCheck)) {
            cancellationCheck()
            val next = minOf(text.length, start + (upper - start) * 2)
            if (next == upper) break
            upper = next
        }

        var low = start + 1
        var high = upper
        var best = start + 1
        while (low <= high) {
            cancellationCheck()
            val middle = (low + high) ushr 1
            if (fits(text, start, middle, maxLines, cancellationCheck)) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best.coerceAtMost(start + remaining)
    }

    private fun fits(
        text: CharSequence,
        start: Int,
        end: Int,
        maxLines: Int,
        cancellationCheck: () -> Unit,
    ): Boolean {
        if (end <= start) return true
        cancellationCheck()
        val layout = buildLayout(styledText(text.subSequence(start, end)))
        return layout.lineCount <= maxLines && layout.height <= textHeight
    }

    private fun buildLayout(value: CharSequence): StaticLayout {
        return StaticLayout.Builder.obtain(value, 0, value.length, textPaint, textWidth)
            .setAlignment(settings.textAlignment.toLayoutAlignment())
            .setIncludePad(false)
            .setLineSpacing(
                textPaint.textSize * settings.paragraphSpacing.coerceIn(0f, 2f),
                settings.lineHeight.coerceIn(0.8f, 3f),
            )
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    settings.textAlignment == DocumentRenderSettings.TextAlignment.JUSTIFY
                ) {
                    setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                }
            }
            .build()
    }

    private inner class TextDocumentPage(
        override val index: Int,
        private val text: CharSequence,
    ) : DocumentPage {
        override fun render(): Bitmap {
            val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(settings.backgroundColor)
            val layout = buildLayout(styledText(text))
            canvas.save()
            canvas.translate(horizontalPadding.toFloat(), verticalPadding.toFloat())
            layout.draw(canvas)
            canvas.restore()
            return bitmap
        }
    }

    private fun styledText(value: CharSequence): CharSequence {
        val styled = SpannableString(value)
        val indent = (textPaint.textSize * settings.paragraphIndent.coerceIn(0f, 6f)).toInt()
        if (indent <= 0) return styled

        val plainText = value.toString()
        var start = 0
        while (start < value.length) {
            val end = plainText.indexOf('\n', start).let { if (it < 0) value.length else it }
            if (end > start) {
                styled.setSpan(
                    LeadingMarginSpan.Standard(indent, 0),
                    start,
                    end,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            start = end + 1
        }
        return styled
    }

    private fun DocumentRenderSettings.TextAlignment.toLayoutAlignment(): Layout.Alignment {
        return when (this) {
            DocumentRenderSettings.TextAlignment.START,
            DocumentRenderSettings.TextAlignment.LEFT,
            DocumentRenderSettings.TextAlignment.JUSTIFY,
            -> Layout.Alignment.ALIGN_NORMAL
            DocumentRenderSettings.TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt().coerceAtLeast(1)
}

private fun normalizeDocumentText(text: CharSequence): CharSequence {
    if (!text.contains('\r')) return text
    return text.toString().replace("\r\n", "\n").replace('\r', '\n')
}

private data class ParsedMobi(
    val text: CharSequence,
    val title: String?,
    val author: String?,
)

private object MobiParser {
    fun parse(bytes: ByteArray): ParsedMobi {
        require(bytes.size >= 78) { "Invalid Palm database" }
        val recordCount = u16(bytes, 76)
        require(recordCount > 1) { "MOBI contains no text records" }
        require(78 + recordCount * 8 <= bytes.size) { "Invalid MOBI record table" }
        val offsets = (0 until recordCount).map { u32(bytes, 78 + it * 8).toInt() }
        val firstRecord = offsets.first()
        require(firstRecord in 78 until bytes.size) { "Invalid MOBI record offset" }
        val compression = u16(bytes, firstRecord)
        val textLength = u32(bytes, firstRecord + 4).toInt().coerceAtMost(bytes.size)
        val textRecordCount = u16(bytes, firstRecord + 8).coerceAtMost(recordCount - 1)
        val encryption = u16(bytes, firstRecord + 12)
        require(encryption == 0) { "DRM-protected MOBI files are not supported" }

        val mobiOffset = firstRecord + 16
        val encoding = if (mobiOffset + 16 <= bytes.size && ascii(bytes, mobiOffset, 4) == "MOBI") {
            when (u32(bytes, mobiOffset + 12)) {
                65001L -> StandardCharsets.UTF_8
                else -> Charset.forName("windows-1252")
            }
        } else {
            Charset.forName("windows-1252")
        }

        val output = ByteArrayOutputStream(textLength.coerceAtLeast(1024))
        repeat(textRecordCount) { index ->
            val recordIndex = index + 1
            val start = offsets.getOrNull(recordIndex) ?: return@repeat
            val end = offsets.getOrNull(recordIndex + 1)?.coerceAtMost(bytes.size) ?: bytes.size
            if (start !in 0 until end || end > bytes.size) return@repeat
            val record = bytes.copyOfRange(start, end)
            val decoded = when (compression) {
                1 -> record
                2 -> decompressPalmDocRecord(record)
                else -> throw DocumentEngineException("Unsupported MOBI compression: $compression")
            }
            if (output.size() > MAX_MOBI_TEXT_BYTES - decoded.size) {
                throw DocumentEngineException("MOBI decoded text exceeds the supported size limit")
            }
            output.write(decoded)
        }

        val decodedBytes = output.toByteArray()
        val expectedTextLength = textLength.takeIf { it > 0 } ?: decodedBytes.size
        val rawText = decodedBytes.copyOf(expectedTextLength.coerceAtMost(decodedBytes.size))
        val decodedText = encoding.decode(ByteBuffer.wrap(rawText)).toString()
            .replace('\u0000', ' ')
            .trim()
        require(decodedText.isNotBlank()) { "MOBI contains no readable text" }
        val text = parseMarkup(decodedText)
        val metadata = exthMetadata(bytes, mobiOffset)
        return ParsedMobi(
            text = text,
            title = metadata.title ?: palmTitle(bytes),
            author = metadata.author,
        )
    }

    private fun parseMarkup(value: String): CharSequence {
        if (!value.contains('<') && !value.contains('&')) return value
        @Suppress("DEPRECATION")
        val spanned = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        return spanned.takeIf { it.isNotBlank() } ?: value
    }

    private fun palmTitle(bytes: ByteArray): String? {
        val title = bytes.copyOfRange(0, 32).toString(Charsets.ISO_8859_1)
            .substringBefore('\u0000')
            .trim()
        return title.takeIf(String::isNotBlank)
    }

    private fun exthMetadata(bytes: ByteArray, mobiOffset: Int): MobiMetadata {
        if (mobiOffset + 116 > bytes.size || ascii(bytes, mobiOffset, 4) != "MOBI") {
            return MobiMetadata()
        }
        val headerLength = u32(bytes, mobiOffset + 4).toInt()
        val flags = u32(bytes, mobiOffset + 112).toInt()
        if (headerLength <= 0 || flags and 0x40 == 0) return MobiMetadata()
        val exthOffset = mobiOffset + ((headerLength + 3) / 4 * 4)
        if (exthOffset + 12 > bytes.size || ascii(bytes, exthOffset, 4) != "EXTH") {
            return MobiMetadata()
        }
        val totalLength = u32(bytes, exthOffset + 4)
        if (totalLength < 12 || totalLength > bytes.size - exthOffset) return MobiMetadata()
        val count = u32(bytes, exthOffset + 8).coerceAtMost(1024).toInt()
        val end = exthOffset + totalLength.toInt()
        var offset = exthOffset + 12
        var title: String? = null
        var author: String? = null
        repeat(count) {
            if (offset + 8 > end) return@repeat
            val recordType = u32(bytes, offset).toInt()
            val recordLength = u32(bytes, offset + 4).toInt()
            if (recordLength < 8 || offset + recordLength > end) return@repeat
            val value = bytes.copyOfRange(offset + 8, offset + recordLength)
                .toString(StandardCharsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
                .takeIf(String::isNotBlank)
            when (recordType) {
                100 -> author = author ?: value
                503 -> title = title ?: value
            }
            offset += recordLength
        }
        return MobiMetadata(title, author)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size)
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size)
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        return bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
    }
}

internal fun decompressPalmDocRecord(record: ByteArray): ByteArray {
    val output = PalmDocOutputBuffer(record.size.coerceAtMost(8192))
    fun ensureCapacity(extra: Int) {
        if (extra < 0 || output.size > MAX_MOBI_TEXT_BYTES - extra) {
            throw DocumentEngineException("MOBI decoded text exceeds the supported size limit")
        }
    }
    var index = 0
    while (index < record.size) {
        val first = record[index].toInt() and 0xff
        when {
            first == 0 -> {
                ensureCapacity(1)
                output.append(0)
            }
            first in 1..8 -> {
                val count = first
                if (index + 1 + count > record.size) break
                ensureCapacity(count)
                output.append(record, index + 1, count)
                index += count
            }
            first in 0x09..0x7f -> {
                ensureCapacity(1)
                output.append(first)
            }
            first in 0x80..0xbf -> {
                if (index + 1 >= record.size) break
                val second = record[index + 1].toInt() and 0xff
                val distance = ((first and 0x3f) shl 5) or (second shr 3)
                val length = (second and 0x07) + 3
                ensureCapacity(length)
                output.copyFromDistance(distance, length)
                index++
            }
            else -> {
                ensureCapacity(2)
                output.append(' '.code)
                output.append(first and 0x7f)
            }
        }
        index++
    }
    return output.toByteArray()
}

private class PalmDocOutputBuffer(initialCapacity: Int) {
    private var data = ByteArray(initialCapacity.coerceAtLeast(32))
    var size: Int = 0
        private set

    fun append(value: Int) {
        ensureCapacity(1)
        data[size++] = value.toByte()
    }

    fun append(source: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        source.copyInto(data, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    fun copyFromDistance(distance: Int, length: Int) {
        if (distance <= 0 || distance > size) return
        ensureCapacity(length)
        repeat(length) {
            data[size] = data[size - distance]
            size++
        }
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensureCapacity(extra: Int) {
        val required = size + extra
        if (required <= data.size) return
        var capacity = data.size
        while (capacity < required) {
            capacity = (capacity * 2).coerceAtMost(MAX_MOBI_TEXT_BYTES)
            if (capacity < required && capacity == MAX_MOBI_TEXT_BYTES) {
                throw DocumentEngineException("MOBI decoded text exceeds the supported size limit")
            }
        }
        data = data.copyOf(capacity)
    }
}

private data class MobiMetadata(
    val title: String? = null,
    val author: String? = null,
)

private fun decodeText(bytes: ByteArray): String {
    if (bytes.startsWith(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))) {
        return bytes.copyOfRange(3, bytes.size).toString(StandardCharsets.UTF_8)
    }
    if (bytes.startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte()))) {
        return bytes.copyOfRange(2, bytes.size).toString(StandardCharsets.UTF_16LE)
    }
    if (bytes.startsWith(byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
        return bytes.copyOfRange(2, bytes.size).toString(StandardCharsets.UTF_16BE)
    }
    decodeStrict(bytes, StandardCharsets.UTF_8)?.let { return it }
    decodeStrict(bytes, Charset.forName("GB18030"))?.let { return it }
    return Charset.forName("windows-1252").decode(ByteBuffer.wrap(bytes)).toString()
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(limit.coerceAtMost(8192))
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private const val MAX_TEXT_BYTES = 64 * 1024 * 1024
private const val MAX_MOBI_BYTES = 256 * 1024 * 1024
private const val MAX_MOBI_TEXT_BYTES = 256 * 1024 * 1024
