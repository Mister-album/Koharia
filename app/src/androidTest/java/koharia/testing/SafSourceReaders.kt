package koharia.testing

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import com.hippo.unifile.UniFile
import koharia.core.archive.ArchiveReader
import koharia.epub.model.EpubOpenRequest
import koharia.epub.service.LocalEpubPublicationService
import koharia.pdf.cache.PdfReflowCacheManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.readium.r2.shared.util.getOrElse
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object SafSourceReaders {
    suspend fun whileOpen(
        context: Context,
        tree: UniFile,
        reflow: PdfReflowCacheManager,
        action: suspend () -> Unit,
    ) {
        val resolver = context.contentResolver
        val originals = checkNotNull(tree.createDirectory("source"))
        val content = linkedMapOf(
            "source.cbz" to zip(mapOf("001.txt" to "SAF archive survives clear".encodeToByteArray())),
            "source.pdf" to pdf(),
            "source.epub" to epub(),
        )
        val files = content.mapValues { (name, bytes) ->
            checkNotNull(originals.createFile(name)).also { file ->
                checkNotNull(resolver.openOutputStream(file.uri, "wt")).use { it.write(bytes) }
            }
        }
        val cbz = files.getValue("source.cbz")
        val pdf = files.getValue("source.pdf")
        val epub = files.getValue("source.epub")
        assertTrue(files.values.all { it.uri.authority == "com.android.externalstorage.documents" })
        resolver.openFileDescriptor(cbz.uri, "r")!!.use { descriptor ->
            ArchiveReader(descriptor).use { archive ->
                resolver.openFileDescriptor(pdf.uri, "r")!!.use { pdfDescriptor ->
                    PdfRenderer(pdfDescriptor).use { renderer ->
                        val session = LocalEpubPublicationService(
                            context.applicationContext as Application,
                            reflow,
                        ).open(
                            EpubOpenRequest(
                                mangaId = Long.MIN_VALUE + 106,
                                chapterId = Long.MIN_VALUE + 106,
                                sourceId = Long.MIN_VALUE + 106,
                                title = "SAF fixture",
                                remotePublication = null,
                                localUri = epub.uri.toString(),
                                openSource = EpubOpenRequest.OpenSource.LOCAL,
                            ),
                            null,
                        )
                        try {
                            action()
                            assertEquals(
                                "SAF archive survives clear",
                                archive.getInputStream("001.txt")!!.use { it.readBytes().decodeToString() },
                            )
                            renderer.openPage(0).use { page ->
                                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                                try {
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    assertEquals(Color.BLUE, bitmap.getPixel(32, 32))
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            val resource =
                                checkNotNull(session.publication.get(session.publication.readingOrder.single()))
                            try {
                                assertTrue(
                                    resource.read().getOrElse { error(it.message) }.decodeToString()
                                        .contains("SAF Readium survives clear"),
                                )
                            } finally {
                                resource.close()
                            }
                        } finally {
                            session.close()
                        }
                    }
                }
            }
        }
        content.forEach { (name, bytes) ->
            assertArrayEquals(bytes, resolver.openInputStream(files.getValue(name).uri)!!.use { it.readBytes() })
        }
    }

    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun pdf() = ByteArrayOutputStream().use { output ->
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(64, 64, 1).create())
            page.canvas.drawColor(Color.BLUE)
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
        output.toByteArray()
    }

    private fun epub() = zip(
        mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent(),
            "book.opf" to """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">saf-fixture</dc:identifier>
            <dc:title>SAF fixture</dc:title><dc:language>en</dc:language></metadata>
            <manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="page"/></spine></package>
            """.trimIndent(),
            "page.xhtml" to """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head>
            <body><p>SAF Readium survives clear</p></body></html>
            """.trimIndent(),
        ).mapValues { it.value.encodeToByteArray() },
    )
}
