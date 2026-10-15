package koharia.pdf

import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader
import koharia.kavita.KavitaPdfCache
import koharia.smanga.SmangaPdfCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfCacheReaderLeaseDeviceTest {
    @Test fun smangaClearWaitsForAllPdfReaders() = exercise("smanga-pdf")

    @Test fun kavitaClearWaitsForAllPdfReaders() = exercise("kavita-pdf")

    @Test fun smangaFailedOpenDoesNotRetainCache() = exercise("smanga-pdf", unreadable = true)

    @Test fun kavitaFailedOpenDoesNotRetainCache() = exercise("kavita-pdf", unreadable = true)

    private fun exercise(provider: String, unreadable: Boolean = false): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, "pdf-cache-reader-${System.nanoTime()}").canonicalFile
        check(root.parentFile == parent && !root.exists())
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir() = root
        }
        val account = "a".repeat(64)
        val key = "b".repeat(64)
        val file = File(root, "$provider/41/$account/$key.pdf")
        check(file.parentFile!!.mkdirs())
        try {
            val document = PdfDocument()
            try {
                for (color in listOf(Color.RED, Color.BLUE)) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(80, 120, document.pages.size).create())
                    page.canvas.drawColor(color)
                    document.finishPage(page)
                }
                file.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val bytes = file.readBytes()
            val manual = File(root, "downloads/manual.pdf").apply {
                check(parentFile!!.mkdirs())
                writeBytes(bytes)
            }
            val idle = File(file.parentFile, "idle.pdf").apply { writeBytes(bytes) }
            val cached = checkNotNull(
                if (provider == "smanga-pdf") {
                    SmangaPdfCache(isolated, 41, account).find(key)
                } else {
                    KavitaPdfCache(isolated, 41, account).find(key)
                },
            )
            fun clear() = if (provider ==
                "smanga-pdf"
            ) {
                SmangaPdfCache.clear(isolated)
            } else {
                KavitaPdfCache.clear(isolated)
            }
            if (unreadable) {
                file.writeText("Not a PDF")
                assertTrue(runCatching { PdfPageLoader(isolated, cached).recycle() }.isFailure)
                assertEquals(2, clear())
                assertFalse(file.exists())
                assertArrayEquals(bytes, manual.readBytes())
                return@runBlocking
            }
            val first = PdfPageLoader(isolated, cached)
            try {
                val second = PdfPageLoader(isolated, cached)
                try {
                    assertEquals(2, first.getPages().size)
                    val pages = second.getPages()
                    assertEquals(2, pages.size)
                    assertEquals(1, clear())
                    assertFalse(idle.exists())
                    assertTrue("An opened PDF must remain in its cache", file.isFile)
                    first.recycle()
                    first.recycle()
                    assertEquals(0, clear())
                    assertTrue("The other PDF reader must retain its file", file.isFile)
                    second.setActivePage(pages[1])
                    second.loadPage(pages[1])
                    val bitmap = checkNotNull(pages[1].bitmap).invoke()
                    try {
                        assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                    } finally {
                        bitmap.recycle()
                    }
                    assertArrayEquals(bytes, manual.readBytes())
                } finally {
                    second.recycle()
                }
            } finally {
                first.recycle()
            }
            assertEquals(1, clear())
            assertFalse(file.exists())
            assertArrayEquals(bytes, manual.readBytes())
        } finally {
            check(root.parentFile == parent)
            check(!root.exists() || root.deleteRecursively())
        }
    }
}
