package tachiyomi.macrobenchmark

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Exercises exported production routes from a separate UID, including the actual rendered pages. */
internal class ReaderProfileJourney : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val root = File(context.cacheDir, "reader-profile/${UUID.randomUUID()}")
    private val colors = listOf(Color.rgb(224, 32, 160), Color.rgb(24, 192, 128), Color.rgb(48, 96, 224))
    private val uris = mutableListOf<android.net.Uri>()

    init {
        BenchmarkTarget.verify(context)
        check(root.mkdirs())
        writeComic(File(root, "Koharia-profile-comic.cbz"))
        writePdf(File(root, "Koharia-profile-document.pdf"))
        writeEpub(File(root, "Koharia-profile-book.epub"))
    }

    fun readAll(onFirstReadable: ((String, Long) -> Unit)? = null) {
        for ((name, mime) in listOf(
            "comic.cbz" to "application/vnd.comicbook+zip",
            "document.pdf" to "application/pdf",
            "book.epub" to "application/epub+zip",
        )) {
            val file = File(root, "Koharia-profile-$name")
            val uri = FileProvider.getUriForFile(context, "tachiyomi.macrobenchmark.readerfiles", file)
            uris += uri
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName(BenchmarkTarget.PACKAGE, "koharia.importing.ExternalMediaOpenActivity")
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri("Reader profile fixture", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val opened = SystemClock.elapsedRealtimeNanos()
            context.startActivity(intent)
            if (name.endsWith("epub")) {
                requireText("Koharia profile section 1")
                onFirstReadable?.invoke(name, SystemClock.elapsedRealtimeNanos() - opened)
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON)
                requireText("Koharia profile section 2")
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON)
                requireText("Koharia profile section 1")
            } else {
                val firstPages = requirePageColors(name) { colors[0] in it }
                onFirstReadable?.invoke(name, SystemClock.elapsedRealtimeNanos() - opened)
                // A double-page layout starts with pages 1–2, so its next slot contains page 3.
                val nextColor = if (colors[1] in firstPages) colors[2] else colors[1]
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON)
                requirePageColors(name) { nextColor in it && colors[0] !in it }
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON)
                requirePageColors(name) { it == firstPages }
            }
            device.pressBack()
            check(device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("History|历史"))), 15_000)) {
                "Reader did not return to the main navigation: $name"
            }
        }
    }

    private fun requireText(text: String) {
        check(device.wait(Until.hasObject(By.textContains(text)), 25_000)) { "EPUB text was not rendered: $text" }
    }

    private fun requirePageColors(name: String, matches: (Set<Int>) -> Boolean): Set<Int> {
        val deadline = SystemClock.uptimeMillis() + 25_000
        var visibleColors = emptySet<Int>()
        do {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                try {
                    visibleColors = (1..3).mapNotNull { position ->
                        val actual = bitmap.getPixel(bitmap.width * position / 4, bitmap.height / 2)
                        colors.firstOrNull { expected ->
                            abs(Color.red(actual) - Color.red(expected)) <= 8 &&
                                abs(Color.green(actual) - Color.green(expected)) <= 8 &&
                                abs(Color.blue(actual) - Color.blue(expected)) <= 8
                        }
                    }.toSet()
                    if (matches(visibleColors)) return visibleColors
                } finally {
                    bitmap.recycle()
                }
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Expected decoded pages were not rendered: $name / $visibleColors")
    }

    private fun writeComic(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            colors.forEachIndexed { index, color ->
                val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    zip.putNextEntry(ZipEntry("$index.png").apply { time = 1577836800000L })
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun writePdf(file: File) {
        val document = PdfDocument()
        try {
            colors.forEachIndexed { index, color ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(color)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun writeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = 1577836800000L })
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    time = 1577836800000L
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            val manifest = (1..3).joinToString("") {
                """<item id="s$it" href="s$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = (1..3).joinToString("") { """<itemref idref="s$it"/>""" }
            entry(
                "book.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">koharia-reader-profile</dc:identifier><dc:title>Koharia profile book</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-12T00:00:00Z</meta></metadata><manifest>$manifest<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>$spine</spine></package>""",
            )
            val links = (1..3).joinToString("") { """<li><a href="s$it.xhtml">Section $it</a></li>""" }
            entry(
                "nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$links</ol></nav></body></html>""",
            )
            for (section in 1..3) {
                entry(
                    "s$section.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Section $section</title></head><body><p>Koharia profile section $section</p></body></html>""",
                )
            }
        }
    }

    override fun close() {
        uris.distinct().forEach { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        check(root.canonicalFile.parentFile == File(context.cacheDir, "reader-profile").canonicalFile)
        check(root.deleteRecursively())
    }

    fun verifyRepeatableInputs() {
        // ZIP timestamps have two-second precision; cross that boundary before regenerating.
        SystemClock.sleep(2200)
        val repeated = listOf(
            "comic.cbz" to File(root, "repeat.cbz").also(::writeComic),
            "document.pdf" to File(root, "repeat.pdf").also(::writePdf),
            "book.epub" to File(root, "repeat.epub").also(::writeEpub),
        )
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val mismatches = mutableListOf<String>()
        for ((format, second) in repeated) {
            val first = File(root, "Koharia-profile-$format").readBytes()
            val next = second.readBytes()
            val equal = first.contentEquals(next)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "media_reproduction",
                        "format=$format;bytes=${first.size};first_sha256=${hash(first)};" +
                            "second_sha256=${hash(next)};identical=$equal",
                    )
                },
            )
            if (!equal) mismatches += format
        }
        check(mismatches.isEmpty()) { "Non-repeatable generated media: $mismatches" }
    }
}

class ReaderProfileJourneyTest {
    @Test
    fun generatedMediaAreByteRepeatable() {
        ReaderProfileJourney().use { it.verifyRepeatableInputs() }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("media_reproduction_cleanup", "owned-root-removed") },
        )
    }

    @Test
    fun grantedExternalFilesRenderAndNavigate() {
        ReaderProfileJourney().use { it.readAll() }
    }

    @Test
    fun grantedExternalFilesMeasureFirstReadiness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(3) { round ->
            ReaderProfileJourney().use { journey ->
                journey.readAll { format, nanos ->
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("reader_first_ready", "round=$round;format=$format;elapsed_ns=$nanos")
                        },
                    )
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putInt("reader_timing_cleanup_round", round) })
        }
    }
}
