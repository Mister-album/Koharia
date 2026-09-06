@file:Suppress("ktlint:standard:max-line-length")

package koharia.pdf.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.core.unlocked.PdfiumCoreU
import koharia.pdf.reflow.PdfAnnotation
import koharia.pdf.reflow.PdfBlock
import koharia.pdf.reflow.PdfBox
import koharia.pdf.reflow.PdfExtractionIndex
import koharia.pdf.reflow.PdfGlyph
import koharia.pdf.reflow.PdfGraphic
import koharia.pdf.reflow.PdfInlineLayout
import koharia.pdf.reflow.PdfOutline
import koharia.pdf.reflow.PdfPageFacts
import koharia.pdf.reflow.PdfReflowBuilder
import koharia.pdf.reflow.PdfTextStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal interface PdfExtractionEngine {
    suspend fun extract(file: File, directory: File, onProgress: (Int, Int) -> Unit): PdfExtractionIndex
}

/** Version-pinned core access, isolated here and serialized on PDFium's own global lock. */
internal class PdfiumExtractionEngine(private val context: Context) : PdfExtractionEngine {
    override suspend fun extract(file: File, directory: File, onProgress: (Int, Int) -> Unit): PdfExtractionIndex =
        extractPages(file, directory, null, null, onProgress)

    suspend fun extractPages(
        file: File,
        directory: File,
        pages: List<Int>?,
        metadata: PdfExtractionIndex? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): PdfExtractionIndex {
        val coroutine = currentCoroutineContext()
        val core = PdfiumCoreU(context)
        return synchronized(PdfiumCoreU.lock) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                core.newDocument(fd).use { document ->
                    val count = document.getPageCount()
                    require(count in 1..5000) { "Unsupported PDF page count" }
                    val fontSizes = mutableListOf<Float>()
                    val outlines = mutableListOf<PdfOutline>()
                    if (metadata == null) {
                        val sampled = listOf(0, minOf(9, count - 1), count / 2, count - 1).distinct()
                        val textPages = sampled.count { index ->
                            coroutine.ensureActive()
                            checkNotNull(document.openPage(index)).use { page ->
                                page.openTextPage().use { text ->
                                    val length = minOf(text.textPageCountChars(), 512)
                                    val data = PdfiumStyleBridge.glyphs(text.pagePtr, length)
                                    repeat(length) { n ->
                                        if (Character.isLetterOrDigit(data[n * 10].toInt())) {
                                            data[n * 10 + 1].toFloat().takeIf {
                                                it.isFinite() && it > 1f
                                            }?.let(fontSizes::add)
                                        }
                                    }
                                    text.textPageGetText(0, length).orEmpty().count(Char::isLetterOrDigit) >= 40
                                }
                            }
                        }
                        require(textPages >= if (count >= 10) 2 else 1) { "This PDF has no reliable text layer" }
                        fun addOutline(bookmarks: List<Bookmark>, level: Int) {
                            if (level > 16 || outlines.size > 5000) return
                            bookmarks.forEach {
                                if (!it.title.isNullOrBlank() && it.pageIdx in 0 until count.toLong()) {
                                    outlines += PdfOutline(it.title.orEmpty(), it.pageIdx.toInt(), level)
                                }
                                addOutline(it.children, level + 1)
                            }
                        }
                        addOutline(document.getTableOfContents(), 0)
                    } else {
                        check(metadata.pageCount == count)
                        outlines += metadata.outlines
                    }
                    val bodySize = metadata?.bodyFontSize ?: fontSizes.sorted().let { it.getOrNull(it.size / 2) }
                    var characters = 0
                    val selected = pages?.distinct()?.sorted() ?: (0 until count).toList()
                    require(selected.all { it in 0 until count })
                    selected.forEach { index ->
                        coroutine.ensureActive()
                        checkNotNull(document.openPage(index)).use { page ->
                            val width = page.getPageWidthPoint().coerceAtLeast(1)
                            val height = page.getPageHeightPoint().coerceAtLeast(1)
                            fun map(left: Float, bottom: Float, right: Float, top: Float): PdfBox {
                                val rect = page.mapRectToDevice(
                                    0,
                                    0,
                                    width * 10,
                                    height * 10,
                                    0,
                                    RectF(left, top, right, bottom),
                                )
                                return PdfBox(
                                    min(rect.left, rect.right) / 10f,
                                    min(rect.top, rect.bottom) / 10f,
                                    max(rect.left, rect.right) / 10f,
                                    max(rect.top, rect.bottom) / 10f,
                                )
                            }
                            val styles = mutableListOf<PdfTextStyle>()
                            val styleIndexes = mutableMapOf<PdfTextStyle, Int>()
                            var unmappedText = false
                            val glyphs = page.openTextPage().use { text ->
                                val total = text.textPageCountChars()
                                require(total in 0..100000) { "PDF text page is too large" }
                                val data = PdfiumStyleBridge.glyphs(text.pagePtr, total)
                                buildList {
                                    repeat(total) { i ->
                                        if (i % 256 == 0) coroutine.ensureActive()
                                        val o = i * 10
                                        val codePoint = data[o].toInt()
                                        if (codePoint == 0) unmappedText = true
                                        if (codePoint == 0 || !Character.isValidCodePoint(codePoint) ||
                                            codePoint in 0xd800..0xdfff ||
                                            (codePoint < 32 && codePoint !in listOf(9, 10, 13))
                                        ) {
                                            return@repeat
                                        }
                                        val size = data[o + 1].toFloat().takeIf { it.isFinite() && it > 0 } ?: 12f
                                        val style = PdfTextStyle(
                                            font = PdfiumStyleBridge.fontNameBytes(text.pagePtr, i)?.decodeToString(),
                                            size = size,
                                            weight = data[o + 6].toInt().takeIf { it > 0 },
                                            italic = data[o + 8].toInt() and 64 != 0,
                                            color = data[o + 7].takeIf { it >= 0 }?.toLong()?.toInt(),
                                        )
                                        val styleIndex = styleIndexes.getOrPut(style) {
                                            styles.add(style)
                                            styles.lastIndex
                                        }
                                        val l = data[o + 2].toFloat()
                                        val b = data[o + 3].toFloat()
                                        val r = data[o + 4].toFloat()
                                        val t = data[o + 5].toFloat()
                                        if (!listOf(l, b, r, t).all(Float::isFinite)) return@repeat
                                        add(
                                            PdfGlyph(
                                                i,
                                                String(Character.toChars(codePoint)),
                                                map(l, b, r, t),
                                                PdfBox(l, b, r, t),
                                                styleIndex,
                                                data[o + 9].toFloat(),
                                            ),
                                        )
                                    }
                                }
                            }
                            characters += glyphs.sumOf { it.text.length }
                            require(characters <= 2_000_000) { "PDF text exceeds the conversion limit" }
                            val rawGraphics = PdfiumStyleBridge.graphics(page.pagePtr)
                            val graphics = rawGraphics.toList().chunked(5).map {
                                PdfGraphic(it[0].toInt(), map(it[1], it[2], it[3], it[4]))
                            }.filter { it.box.width > 2 || it.box.height > 2 }
                            val crop = page.getPageCropBox()
                            val annotations = PdfiumStyleBridge.annotationBoxes(page.pagePtr).toList().chunked(5)
                                .mapNotNull { values ->
                                    val n = values[0].toInt()
                                    val value = PdfiumStyleBridge.annotationText(page.pagePtr, n)
                                        ?.toString(Charsets.UTF_16LE)?.trimEnd('\u0000')?.trim()
                                        ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                                    PdfAnnotation(n, map(values[1], values[2], values[3], values[4]), value)
                                }
                            var facts = PdfPageFacts(
                                index,
                                width,
                                height,
                                page.getPageRotation(),
                                PdfBox(crop.left, crop.top, crop.right, crop.bottom),
                                styles,
                                glyphs,
                                graphics,
                                hasUnmappedText = unmappedText,
                                annotations = annotations,
                            )
                            val complex = PdfReflowBuilder.needsOriginalPage(facts)
                            val illustrationRegions = graphics.filter {
                                it.kind in listOf(3, 4, 5) &&
                                    it.box.width * it.box.height > width * height * .005f
                            } + PdfInlineLayout.analyze(facts).originalRegions.map { PdfGraphic(-1, it) }
                            if (complex || illustrationRegions.isNotEmpty()) {
                                val scale =
                                    minOf(2f, 1400f / width, sqrt(4_000_000.0 / (width.toDouble() * height)).toFloat())
                                val bitmap = Bitmap.createBitmap(
                                    (width * scale).toInt().coerceAtLeast(1),
                                    (
                                        height *
                                            scale
                                        ).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888,
                                )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.renderPageBitmap(
                                        bitmap,
                                        0,
                                        0,
                                        bitmap.width,
                                        bitmap.height,
                                        canvasColor = Color.WHITE,
                                        pageBackgroundColor = Color.WHITE,
                                    )
                                    if (complex) {
                                        val name = "page-$index.png"
                                        File(directory, name).outputStream().use {
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                        }
                                        facts = facts.copy(fallbackAsset = name)
                                    } else {
                                        facts = facts.copy(
                                            graphics = illustrationRegions.mapIndexed { n, graphic ->
                                                val x = (graphic.box.left * scale).toInt().coerceIn(0, bitmap.width - 1)
                                                val y = (graphic.box.top * scale).toInt().coerceIn(0, bitmap.height - 1)
                                                val w = (graphic.box.width * scale).toInt().coerceIn(
                                                    1,
                                                    bitmap.width - x,
                                                )
                                                val h = (graphic.box.height * scale).toInt().coerceIn(
                                                    1,
                                                    bitmap.height - y,
                                                )
                                                val image = Bitmap.createBitmap(bitmap, x, y, w, h)
                                                val name = "page-$index-image-$n.png"
                                                try {
                                                    File(directory, name).outputStream().use {
                                                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                                                    }
                                                } finally {
                                                    if (image !== bitmap) image.recycle()
                                                }
                                                graphic.copy(asset = name)
                                            },
                                        )
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            File(directory, "page-$index.json").writeText(Json.encodeToString(facts))
                            if (index % 8 == 0) {
                                val bytes = directory.listFiles().orEmpty().filter {
                                    it.name != "source.pdf"
                                }.sumOf(File::length)
                                require(bytes <= 256L * 1024 * 1024) { "PDF conversion exceeds the cache budget" }
                            }
                        }
                        onProgress(index + 1, count)
                    }
                    if (pages == null) require(characters >= 80) { "This PDF has no usable text layer" }
                    PdfExtractionIndex(count, characters, outlines, pages?.let { selected }, bodySize).also {
                        File(directory, "index.json").writeText(Json.encodeToString(it))
                    }
                }
            }
        }
    }
}
