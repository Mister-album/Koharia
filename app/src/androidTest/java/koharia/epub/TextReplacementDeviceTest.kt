package koharia.epub

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.screen.SettingsBookReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.connection.SharedAppPreferences
import koharia.domain.textreplacement.EffectiveReplacementRule
import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.ReplacementRule
import koharia.domain.textreplacement.ReplacementScope
import koharia.epub.model.EpubOpenRequest
import koharia.epub.replacement.TextReplacementScreen
import koharia.epub.service.LocalEpubPublicationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.motion.EInkDisplayPolicy
import tachiyomi.presentation.core.motion.ProvideEInkDisplayPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Owned generated book
 no connection credentials,
 progress writes,
 downloads or external services. */
@RunWith(AndroidJUnit4::class)
class TextReplacementDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun publicationMappingReloadUsesOriginalWithoutEditingBook() = verifyPublicationReplacement(renderSelection = false)

    @Test
    fun publicationSearchCopyAndReopenUseSameReplacementWithoutEditingBook() =
        verifyPublicationReplacement(renderSelection = true)

    @SuppressLint("SetJavaScriptEnabled")
    private fun verifyPublicationReplacement(renderSelection: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val file = File(context.cacheDir, "replacement-${UUID.randomUUID()}.epub").canonicalFile
        check(file.parentFile == context.cacheDir.canonicalFile && !file.exists())
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry(
                    "META-INF/container.xml",
                    """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="book.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>""",
                )
                entry(
                    "book.opf",
                    """<?xml version="1.0"?>
<package version="3.0" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">fixture</dc:identifier>
<dc:title>Replacement fixture</dc:title>
<dc:language>en</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest>
<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
</manifest>
<spine>
<itemref idref="chapter"/>
</spine>
</package>""",
                )
                entry(
                    "nav.xhtml",
                    """
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
<title>Contents</title>
</head>
<body>
<nav epub:type="toc">
<ol>
<li>
<a href="chapter.xhtml">Chapter</a>
</li>
</ol>
</nav>
</body>
</html>""",
                )
                entry(
                    "chapter.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<title>Chapter</title>
</head>
<body>
<p id="anchor">Before old<b>word</b> after.</p>
</body>
</html>""",
                )
            }
            val original = file.readBytes()
            val rules = EffectiveRuleSet(
                listOf(
                    EffectiveReplacementRule(
                        ReplacementRule(
                            pattern = "oldword",
                            replacement = "replacementword",
                        ),
                        ReplacementScope.BOOK,

                    ),
                ),
            )
            for (enabled in listOf(true, false, true)) {
                val request = EpubOpenRequest(
                    mangaId = 0, chapterId = 0, sourceId = 0, title = "Fixture",
                    remotePublication = null,
                    localUri = file.toURI().toString(),
                    openSource = EpubOpenRequest.OpenSource.LOCAL,

                    persistCache = false,
                    replacements = if (enabled) rules else EffectiveRuleSet.EMPTY,

                )
                val session = LocalEpubPublicationService().open(request, null)
                try {
                    val resource = checkNotNull(session.publication.get(session.publication.readingOrder.single()))
                    val bytes = try {
                        resource.read().getOrElse {
                            error(it.message)
                        }
                    } finally {
                        resource.close()
                    }
                    val expected = if (enabled) "replacementword" else "oldword"
                    val spoken = koharia.tts.reader.ChapterTextExtractor.plainTextFrom(bytes, "chapter.xhtml")
                    assertTrue("TTS extraction must use displayed text", spoken?.contains(expected) == true)
                    if (enabled) assertFalse(checkNotNull(spoken).contains("oldword"))
                    val search = checkNotNull(session.publication.search(expected))
                    try {
                        val results = search.next().getOrElse { error(it.message) }
                        assertTrue(
                            "Search must use displayed text",
                            results?.locators?.isNotEmpty() == true,
                        )
                    } finally {
                        search.close()
                    }
                    if (enabled) {
                        val replacement = checkNotNull(session.replacements)
                        val href = session.publication.readingOrder.single().url()
                        assertTrue(
                            "Repeated builder callbacks must not overwrite the original mapping",
                            replacement.sample(href.toString()).contains("oldword"),
                        )
                        replacement.clear()
                        replacement.ensureMapped(session.publication, href, active = true)
                        assertTrue(
                            "Evicted mapping must be rebuilt from the original EPUB resource",
                            replacement.sample(href.toString()).contains("oldword"),
                        )
                        assertFalse(replacement.sample(href.toString()).contains("replacementword"))
                        val preview = checkNotNull(replacement.loadPreview(session.publication, href.toString()))
                        assertTrue(preview.original.contains("oldword"))
                    }
                    if (renderSelection) {
                        // The runner finishes existing activities before each test. Bring the
                        // isolated package forward before MIUI checks the fixture activity launch.
                        val launch = instrumentation.uiAutomation.executeShellCommand(
                            "am start -W -n ${context.packageName}/eu.kanade.tachiyomi.ui.main.MainActivity",
                        )
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
                        ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                            lateinit var webView: WebView
                            val loaded = CountDownLatch(1)
                            scenario.onActivity { activity ->
                                webView = WebView(activity).apply {
                                    settings.javaScriptEnabled = true
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(
                                            view: WebView,
                                            url: String,
                                        ) {
                                            loaded.countDown()
                                        }
                                    }
                                }
                                activity.setContentView(webView)
                                webView.loadDataWithBaseURL(
                                    "https://fixture.invalid/chapter.xhtml",
                                    bytes.toString(Charsets.UTF_8),
                                    "application/xhtml+xml",
                                    "UTF-8",
                                    null,
                                )
                            }
                            try {
                                assertTrue(loaded.await(10, TimeUnit.SECONDS))
                                val selected = CountDownLatch(1)
                                var text = ""
                                scenario.onActivity {
                                    webView.evaluateJavascript(
                                        """
                                    (function(){
                                    var r=document.createRange();
                                    r.selectNodeContents(document.getElementById('anchor'));
                                    var s=getSelection();
                                    s.removeAllRanges();s.addRange(r);return s.toString();
                                    })()
                                        """.trimIndent(),
                                    ) {
                                        text = it
                                        selected.countDown()
                                    }
                                }
                                assertTrue(selected.await(5, TimeUnit.SECONDS))
                                assertTrue(text.contains(expected))
                                if (enabled) assertFalse(text.contains("oldword"))
                            } finally {
                                scenario.onActivity { webView.destroy() }
                            }
                        }
                    }
                } finally {
                    session.close()
                }
                assertArrayEquals(original, file.readBytes())
            }
        } finally {
            check(file.parentFile == context.cacheDir.canonicalFile)
            check(!file.exists() || file.delete())
        }
    }

    @Test
    fun managerSettlesAfterOrdinaryAndEInkPolicyChanges() {
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val policy = mutableStateOf(false)
        val draws = AtomicInteger()
        val preference = Injekt.get<SharedAppPreferences>().epubReaderPreferences().textReplacementEnabled
        val original = preference.get()
        val masterLabel = instrumentation.targetContext.stringResource(MR.strings.replacement_master)
        try {
            ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            ProvideEInkDisplayPolicy(EInkDisplayPolicy(policy.value)) {
                                Navigator(TextReplacementScreen()) { CurrentScreen() }
                            }
                        }
                    }
                    activity.window.decorView.viewTreeObserver.addOnDrawListener(
                        ViewTreeObserver.OnDrawListener {
                            draws.incrementAndGet()
                        },
                    )
                }
                for (enabled in listOf(false, true, false)) {
                    scenario.onActivity { policy.value = enabled }
                    assertTrue(hasSwitchRole(node(masterLabel)))
                    click(masterLabel)
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(700)
                    val before = draws.get()
                    SystemClock.sleep(400)
                    assertTrue(
                        "Manager must not keep redrawing at rest (E-Ink=$enabled)",
                        draws.get() - before <= 2,
                    )
                }
            }
        } finally {
            preference.set(original)
        }
    }

    @Test
    fun bookSettingsSwitchGatesManagerAndUsesActionButtons() {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val preference = Injekt.get<SharedAppPreferences>().epubReaderPreferences().textReplacementEnabled
        val original = preference.get()
        val switchLabel = context.stringResource(MR.strings.replacement_title)
        val manageLabel = context.stringResource(MR.strings.replacement_manage)
        val masterLabel = context.stringResource(MR.strings.replacement_master)
        try {
            for (eInk in listOf(false, true)) {
                preference.set(false)
                ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                    lateinit var navigator: Navigator
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme {
                                ProvideEInkDisplayPolicy(EInkDisplayPolicy(eInk)) {
                                    Navigator(SettingsBookReaderScreen) {
                                        navigator = it
                                        CurrentScreen()
                                    }
                                }
                            }
                        }
                    }
                    assertSwitch(switchLabel, false)
                    assertTrue(find { it.text?.toString() == manageLabel } == null)
                    captureSettings("parent-off-$eInk")
                    click(switchLabel)
                    assertSwitch(switchLabel, true)
                    captureSettings("parent-on-$eInk")
                    click(manageLabel)
                    assertSwitch(masterLabel, true)
                    val globalLabel = context.stringResource(MR.strings.replacement_global)
                    val rulesLabel = context.stringResource(MR.strings.replacement_rules, globalLabel)
                    assertTrue(
                        "Settings entry must identify global rules",
                        find { it.text?.toString() == rulesLabel } != null,
                    )
                    captureSettings("manager-$eInk")
                    for (label in listOf(MR.strings.replacement_import, MR.strings.replacement_export)) {
                        assertTrue(hasButtonRole(node(context.stringResource(label))))
                    }
                    val addLabel = context.stringResource(MR.strings.replacement_add)
                    val add = node(addLabel, scroll = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    assertTrue(hasButtonRole(add))
                    assertTrue(add.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    assertSwitch(context.stringResource(MR.strings.replacement_regex), false)
                    val editorLabel = context.stringResource(MR.strings.replacement_edit_scoped, globalLabel)
                    assertTrue(
                        "Editor must identify the target scope",
                        find { it.text?.toString() == editorLabel } != null,
                    )
                    captureSettings("editor-$eInk")
                    click(context.stringResource(MR.strings.replacement_cancel))
                    val master = node(masterLabel, scroll = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    assertTrue(master.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    assertSwitch(masterLabel, false)
                    scenario.onActivity { navigator.pop() }
                    assertSwitch(switchLabel, false)
                    assertTrue(find { it.text?.toString() == manageLabel } == null)
                }
            }
        } finally {
            preference.set(original)
        }
    }

    private fun captureSettings(name: String) {
        val run = InstrumentationRegistry.getArguments().getString("replacementScreenshots") ?: return
        require(run.matches(Regex("replacement-ui-[a-z0-9-]+")))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), run)
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSwitch(label: String, checked: Boolean) {
        val switch = node(label, checked = checked)
        assertTrue("Control must expose the switch role", hasSwitchRole(switch))
        assertTrue(switch.isCheckable)
        assertEquals(checked, switch.isChecked)
    }

    private fun hasSwitchRole(node: AccessibilityNodeInfo): Boolean {
        // Compose exposes switches through the localized role description, not a legacy View class.
        val expected = instrumentation.targetContext.getString(androidx.compose.ui.R.string.switch_role)
        if (AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() == expected) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasSwitchRole) == true }
    }

    private fun hasButtonRole(node: AccessibilityNodeInfo): Boolean {
        if (node.className.toString() == "android.widget.Button") return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasButtonRole) == true }
    }

    private fun click(label: String) {
        assertTrue(node(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun node(
        label: String,
        checked: Boolean? = null,
        scroll: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
    ): AccessibilityNodeInfo {
        repeat(50) { attempt ->
            var target = find { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && (checked == null || target.isChecked == checked)) return target
            if (target == null && attempt % 5 == 4) find { it.isScrollable }?.performAction(scroll)
            SystemClock.sleep(100)
        }
        error("Missing control or state: $label (checked=$checked)")
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
}
