package koharia.epub

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.ui.EInkPreferences
import koharia.epub.model.EpubOpenRequest
import koharia.epub.service.LocalEpubPublicationService
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.readium.r2.shared.util.getOrElse
import org.xml.sax.InputSource
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Opt-in sample validation; input and generated cache remain inside the isolated test package. */
class EpubExternalSampleDeviceTest {
    @Test
    fun originalAndLegacyNamespaceCacheRenderAfterReopening(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val directory = args.getString("epubSampleDirectory")
        assumeNotNull(directory)
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val folder = File(root, checkNotNull(directory)).canonicalFile
        require(folder.toPath().startsWith(root.toPath()) && folder != root)
        val input = File(folder, "sample.epub")
        fun hash() = MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") {
            "%02x".format(it)
        }
        assertEquals(args.getString("epubSampleSha256"), hash())
        val legacy = File(folder, "legacy-cache.epub")
        var stripped = 0
        ZipFile(input).use { source ->
            ZipOutputStream(legacy.outputStream()).use { output ->
                source.entries().asSequence().forEach { entry ->
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    val changed = if (entry.name.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm")) {
                        val html = bytes.decodeToString()
                        val declaration = Regex("\\sxmlns:epub=[\"'][^\"']*[\"']")
                        if (html.contains("epub:type") && declaration.containsMatchIn(html)) {
                            stripped++
                            declaration.replace(html, "").encodeToByteArray()
                        } else {
                            bytes
                        }
                    } else {
                        bytes
                    }
                    output.putNextEntry(
                        ZipEntry(entry.name).apply {
                            if (entry.method == ZipEntry.STORED) {
                                method = ZipEntry.STORED
                                size = changed.size.toLong()
                                compressedSize = size
                                crc = java.util.zip.CRC32().apply { update(changed) }.value
                            }
                        },
                    )
                    output.write(changed)
                    output.closeEntry()
                }
            }
        }
        assertTrue("Sample must exercise EPUB namespaced attributes", stripped > 0)
        val preferences = Injekt.get<EpubLayoutPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val present = preference.isSet()
            val old = preference.get()
            restores += { if (present) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        replace(preferences.readingMode, EpubLayoutPreferences.ReadingMode.PAGINATED)
        val eink = Injekt.get<EInkPreferences>().enabled
        val sessions = Injekt.get<EpubReaderSessionRepository>()
        val report = StringBuilder("inputBytes=${input.length()} legacyDocuments=$stripped\n")
        try {
            for ((variant, file) in listOf("original" to input, "legacy-cache" to legacy)) {
                for (reopen in 0..1) {
                    replace(eink, reopen == 1)
                    val chapterId = -SystemClock.elapsedRealtimeNanos()
                    val session = LocalEpubPublicationService().open(
                        EpubOpenRequest(
                            0,
                            chapterId,
                            -1,
                            "External sample",
                            null,
                            file.toURI().toString(),
                            EpubOpenRequest.OpenSource.LOCAL,
                        ),
                        null,
                    )
                    sessions.put(session)
                    try {
                        val order = session.publication.readingOrder
                        assertTrue(order.isNotEmpty())
                        val selected = mutableSetOf(0, order.size / 2, order.lastIndex)
                        val factory = DocumentBuilderFactory.newInstance().apply {
                            isNamespaceAware = true
                        }
                        for ((index, link) in order.withIndex()) {
                            val resource = checkNotNull(session.publication.get(link))
                            val bytes = try {
                                resource.read().getOrElse { error(it.message) }
                            } finally {
                                resource.close()
                            }
                            factory.newDocumentBuilder().apply {
                                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                            }.parse(bytes.inputStream())
                            if (bytes.decodeToString().contains("epub:type")) selected += index
                        }
                        FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                            lateinit var fragment: EpubReaderFragment
                            scenario.onActivity { activity ->
                                fragment = EpubReaderFragment.newInstance(chapterId, -1, 0)
                                activity.supportFragmentManager.beginTransaction().replace(
                                    android.R.id.content,
                                    fragment,
                                ).commitNow()
                            }
                            fun evaluate(expectedSuffix: String): String? {
                                val finished = CountDownLatch(1)
                                var value: String? = null
                                scenario.onActivity { activity ->
                                    val view = descendants(
                                        activity.window.decorView,
                                    ).filterIsInstance<WebView>().firstOrNull {
                                        val rect = Rect()
                                        it.isShown && it.getGlobalVisibleRect(rect) && rect.width() > it.width / 2 &&
                                            rect.height() > it.height / 2 &&
                                            it.url?.substringBefore('#')?.endsWith(expectedSuffix) == true
                                    }
                                    if (view == null) {
                                        finished.countDown()
                                    } else {
                                        view.evaluateJavascript(
                                            """
                                        (function() {
                                            if (document.readyState !== 'complete') return false;
                                            if (!window.__kohariaFixturePaint) {
                                                window.__kohariaFixturePaint = 1;
                                                requestAnimationFrame(function() {
                                                    requestAnimationFrame(function() {
                                                        window.__kohariaFixturePaint = 2;
                                                    });
                                                });
                                            }
                                            return window.__kohariaFixturePaint === 2 && !!window.readium &&
                                                !document.querySelector('parsererror') &&
                                                !document.documentElement.hasAttribute('data-koharia-load-error') &&
                                                Array.from(document.images).every(function(img) {
                                                    return img.complete && img.naturalWidth > 0;
                                                }) &&
                                                (document.body.innerText.trim().length > 0 ||
                                                    !!document.querySelector('img,svg'));
                                        })()
                                            """.trimIndent(),
                                        ) {
                                            value = it
                                            finished.countDown()
                                        }
                                    }
                                }
                                assertTrue(finished.await(5, TimeUnit.SECONDS))
                                return value
                            }
                            fun awaitPage(index: Int) {
                                val suffix = order[index].href.toString().substringAfterLast('/')
                                val deadline = SystemClock.uptimeMillis() + 30_000
                                while (evaluate(suffix) != "true") {
                                    assertTrue(
                                        "Sample page did not render: $variant reopen=$reopen index=$index",
                                        SystemClock.uptimeMillis() < deadline,
                                    )
                                    SystemClock.sleep(100)
                                }
                            }
                            awaitPage(0)
                            for (index in selected.sorted()) {
                                if (index != 0) scenario.onActivity { assertTrue(fragment.goTo(order[index])) }
                                awaitPage(index)
                                report.appendLine(
                                    "variant=$variant reopen=$reopen eink=${reopen == 1} renderedIndex=$index",
                                )
                            }
                            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                                try {
                                    File(folder, "$variant-reopen-$reopen.png").outputStream().use {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                        report.appendLine("variant=$variant reopen=$reopen validatedReadingOrder=${order.size}")
                        File(folder, "result.txt").writeText(report.toString())
                    } finally {
                        sessions.remove(chapterId)
                    }
                }
            }
            assertEquals(args.getString("epubSampleSha256"), hash())
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", report.toString()) })
        } finally {
            restores.asReversed().forEach { it() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
