package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionScopedPreferenceStoreFactory
import koharia.connection.LibraryConnectionProfile
import koharia.domain.manga.model.toDomainManga
import koharia.importing.IncomingMediaSessionLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
@RunWith(AndroidJUnit4::class)
class DoublePageActionsDeviceTest {
    @Test fun leftToRight() = verify(ReadingMode.LEFT_TO_RIGHT, false)

    @Test fun rightToLeft() = verify(ReadingMode.RIGHT_TO_LEFT, false)

    @Test fun invertedLeftToRight() = verify(ReadingMode.LEFT_TO_RIGHT, true)

    @Test fun invertedRightToLeft() = verify(ReadingMode.RIGHT_TO_LEFT, true)

    @Test
    fun mergedExportNormalizesHeightAndKeepsPixelsAtJoin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val left = File.createTempFile("merge-left-", ".png", context.cacheDir)
        val right = File.createTempFile("merge-right-", ".png", context.cacheDir)
        val output = File.createTempFile("merge-output-", ".png", context.cacheDir)
        try {
            for ((file, size, color) in listOf(Triple(left, 100, Color.RED), Triple(right, 200, Color.GREEN))) {
                val bitmap = Bitmap.createBitmap(size, size * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                try {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }
            MergedPageImage.write({ left.inputStream() }, { right.inputStream() }, output)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(output.path))
            try {
                assertEquals(400, bitmap.width)
                assertEquals(400, bitmap.height)
                assertEquals(Color.RED, bitmap.getPixel(199, 200))
                assertEquals(Color.GREEN, bitmap.getPixel(200, 200))
            } finally {
                bitmap.recycle()
            }
            left.writeText("invalid image")
            var rejected = false
            try {
                MergedPageImage.write({ left.inputStream() }, { right.inputStream() }, output)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Invalid input must be rejected", rejected)
        } finally {
            listOf(left, right, output).forEach { it.delete() }
        }
    }

    private fun verify(mode: ReadingMode, inverted: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connections = Injekt.get<ConnectionPreferences>()
        val previousProfiles = connections.getProfiles()
        val previousActiveConnection = connections.activeConnectionId.get()
        val sourceId = connections.allocateConnectionId()
        val preferences = Injekt.get<ConnectionScopedPreferenceStoreFactory>().readerPreferences(sourceId)
        val restores = mutableListOf<() -> Unit>()
        fun <T> override(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }

        val repository = Injekt.get<MangaRepository>()
        val sessionId = UUID.randomUUID().toString()
        val directory = File(IncomingMediaSessionLocator.cacheRoot(context), sessionId).apply { mkdirs() }
        var mangaId: Long? = null
        try {
            connections.setProfiles(previousProfiles + LibraryConnectionProfile(sourceId, "local-folder", "Split test"))
            val sourceDeadline = SystemClock.uptimeMillis() + 5_000
            while (Injekt.get<SourceManager>().get(sourceId) == null && SystemClock.uptimeMillis() < sourceDeadline) {
                SystemClock.sleep(25)
            }
            check(Injekt.get<SourceManager>().get(sourceId) != null)
            override(preferences.persistReaderSettingsChanges, false)
            override(preferences.dualPageSplitPaged, false)
            override(preferences.dualPageInvertPaged, false)
            override(preferences.dualPageRotateToFit, false)
            override(
                preferences.pageLayout,
                PageLayout.DOUBLE_PAGES.value,
            )
            override(preferences.pagerPageTransitionEffect, 0)
            override(preferences.navigateToPan, true)
            override(preferences.landscapeZoom, false)
            override(preferences.invertDoublePages, inverted)
            override(preferences.cropBorders, false)
            val preferredScale = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
            override(preferences.imageScaleType, preferredScale)
            val comic = File(directory, "split-test.cbz")
            ZipOutputStream(comic.outputStream()).use { zip ->
                repeat(6) { index ->
                    val width = if (index % 2 == 0) 600 else 601
                    val image = Bitmap.createBitmap(width, 900, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    canvas.drawColor(if (index % 2 == 0) Color.RED else Color.GREEN)
                    zip.putNextEntry(ZipEntry("$index.png"))
                    image.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    image.recycle()
                }
            }
            val manga = repository.insertNetworkManga(
                listOf(
                    SManga.create().apply {
                        url = IncomingMediaSessionLocator.seriesUrl(sourceId, sessionId)
                        title = "Issue 82 split regression $sessionId"
                        initialized = true
                    }.toDomainManga(sourceId).copy(
                        viewerFlags = (mode.flagValue or ReaderOrientation.LOCKED_LANDSCAPE.flagValue).toLong(),
                    ),
                ),
            ).single()
            mangaId = manga.id
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = IncomingMediaSessionLocator.chapterUrl(sourceId, sessionId, comic.name),
                        name = "Split test",
                        chapterNumber = 1.0,
                    ),
                ),
            ).single()

            val intent = ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId, pageIndex = 1)
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                lateinit var holder: PagerPageHolder
                awaitCondition {
                    var ready = false
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@onActivity
                        val pages = viewer.pager.children.filterIsInstance<PagerPageHolder>()
                        val field = PagerViewer::class.java.getDeclaredField("currentSlot").apply {
                            isAccessible = true
                        }
                        val slot = field.get(viewer) as? PagerSlot.Pages ?: return@onActivity
                        holder = pages.firstOrNull { it.slot == slot } ?: return@onActivity
                        ready = slot.second != null && holder.isTransitionTargetReady() &&
                            descendants(holder).filterIsInstance<SubsamplingScaleImageView>().count { it.isReady } == 2
                    }
                    ready
                }
                SystemClock.sleep(300)
                lateinit var images: List<SubsamplingScaleImageView>
                var joinX = 0
                var centerY = 0
                scenario.onActivity { activity ->
                    images =
                        descendants(holder).filterIsInstance<SubsamplingScaleImageView>().filter { it.isReady }.toList()
                    val ordered = images.sortedBy { image -> IntArray(2).also(image::getLocationOnScreen)[0] }
                    assertTrue("This regression must run in landscape", holder.width > holder.height)
                    images = ordered
                    val leftLocation = IntArray(2).also(images[0]::getLocationOnScreen)
                    val rightLocation = IntArray(2).also(images[1]::getLocationOnScreen)
                    val leftEdge =
                        checkNotNull(images[0].sourceToViewCoord(images[0].sWidth.toFloat(), 0f)).x + leftLocation[0]
                    val rightEdge = checkNotNull(images[1].sourceToViewCoord(0f, 0f)).x + rightLocation[0]
                    assertTrue(
                        "Rendered page gap: ${rightEdge - leftEdge}",
                        kotlin.math.abs(rightEdge - leftEdge) <= 1.1f,
                    )
                    joinX = rightEdge.toInt()
                    centerY = rightLocation[1] + images[1].height / 2
                    activity.hideMenu()
                }
                SystemClock.sleep(250)
                val shot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    for (x in joinX - 2..joinX + 2) {
                        val pixel = shot.getPixel(x, centerY)
                        assertTrue("Black seam pixel at $x", Color.red(pixel) > 200 || Color.green(pixel) > 200)
                    }
                    File(
                        context.getExternalFilesDir(null),
                        "double-page-${mode.name}-$inverted.png",
                    ).outputStream().use {
                        shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    shot.recycle()
                }
                scenario.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as PagerViewer
                    assertEquals(
                        null,
                        viewer.mergedPagesFor(
                            checkNotNull(activity.viewModel.state.value.viewerChapters?.currChapter?.pages).last(),
                        ),
                    )
                    activity.onPageLongTap(holder.slot.first)
                    assertEquals(
                        2,
                        (activity.viewModel.state.value.dialog as ReaderViewModel.Dialog.PageActions).mergedPages?.size,
                    )
                }
                awaitCondition {
                    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    var node = findText(root, context.stringResource(MR.strings.action_save_merged_page))
                    while (node != null && !node.isClickable) node = node.parent
                    node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                var saved: android.net.Uri? = null
                try {
                    awaitCondition {
                        context.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Images.Media._ID),
                            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                            arrayOf("%$sessionId%merged%"),
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                saved = ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(0),
                                )
                            }
                        }
                        saved?.let { uri ->
                            try {
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                context.contentResolver.openInputStream(uri).use {
                                    BitmapFactory.decodeStream(it, null, options)
                                }
                                options.outWidth == 1201 && options.outHeight == 900 &&
                                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0 } ==
                                    true
                            } catch (_: java.io.IOException) {
                                false
                            }
                        } ?: false
                    }
                    // MediaStore rows may exist before the background writer has closed the file.
                    SystemClock.sleep(200)
                    val bitmap =
                        checkNotNull(
                            context.contentResolver.openInputStream(checkNotNull(saved)).use {
                                BitmapFactory.decodeStream(it)
                            },
                        )
                    try {
                        assertEquals(1201, bitmap.width)
                        assertEquals(900, bitmap.height)
                        val firstLeft = DoublePagePlacement.firstPageOnLeft(mode == ReadingMode.RIGHT_TO_LEFT, inverted)
                        val leftPage = if (firstLeft) holder.slot.first else checkNotNull(holder.slot.second)
                        val expectedLeft = if (leftPage.index % 2 == 0) Color.RED else Color.GREEN
                        assertEquals(expectedLeft, bitmap.getPixel(0, 450))
                        assertEquals(
                            if (expectedLeft ==
                                Color.RED
                            ) {
                                Color.GREEN
                            } else {
                                Color.RED
                            },
                            bitmap.getPixel(1200, 450),
                        )
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    saved?.let { context.contentResolver.delete(it, null, null) }
                }
                var initialLeft = 0f
                var initialRight = 0f
                scenario.onActivity {
                    initialLeft = images[0].scale
                    initialRight = images[1].scale
                    val now = SystemClock.uptimeMillis()
                    fun tap(time: Long) {
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(
                                time,
                                time + if (action == MotionEvent.ACTION_UP) 20 else 0,
                                action,
                                images[0].width / 2f,
                                images[0].height / 2f,
                                0,
                            )
                            (holder.parent as Pager).dispatchTouchEvent(event)
                            event.recycle()
                        }
                    }
                    tap(now)
                    tap(now + 100)
                }
                SystemClock.sleep(600)
                scenario.onActivity {
                    assertTrue("Double tap must zoom the touched page", images[0].scale > initialLeft * 1.2f)
                    // Both tiled decoders follow the shared spread viewport.
                    assertEquals(
                        "Both pages must have the same relative zoom",
                        images[0].scale / initialLeft,
                        images[1].scale / initialRight,
                        0.01f,
                    )
                }
                scenario.onActivity {
                    descendants(holder).filterIsInstance<DoublePageLayout>().single().zoomAt(1f, 0f, 0f)
                }
                SystemClock.sleep(150)
                scenario.onActivity { pinch(holder.parent as Pager) }
                SystemClock.sleep(300)
                scenario.onActivity {
                    assertTrue("Pinch must zoom both pages through the pager", images[0].scale > initialLeft * 1.2f)
                    assertEquals(
                        "Pinch must zoom both pages together",
                        images[0].scale / initialLeft,
                        images[1].scale / initialRight,
                        0.01f,
                    )
                    val leftEdge = checkNotNull(images[0].sourceToViewCoord(images[0].sWidth.toFloat(), 0f)).x
                    val rightEdge = checkNotNull(images[1].sourceToViewCoord(0f, 0f)).x
                    assertTrue("Zoom must preserve the join", kotlin.math.abs(leftEdge - rightEdge) < 2f)
                    val container = descendants(holder).filterIsInstance<DoublePageLayout>().single()
                    val firstOnLeft = DoublePagePlacement.firstPageOnLeft(mode == ReadingMode.RIGHT_TO_LEFT, inverted)
                    assertEquals(
                        if (firstOnLeft) holder.slot.first else holder.slot.second,
                        holder.pageAt((leftEdge - 20f).coerceAtLeast(0f), holder.height / 2f),
                    )
                    container.panLeft()
                    assertTrue("Pan left reaches boundary", !container.canPanLeft())
                    container.panRight()
                    assertTrue("Pan right reaches boundary", !container.canPanRight())
                    container.zoomAt(1f, 0f, 0f)
                    assertTrue(!container.canPanLeft() && !container.canPanRight())
                }
                val originalSlot = holder.slot
                scenario.onActivity { activity ->
                    (activity.viewModel.state.value.viewer as PagerViewer).moveToNext()
                }
                awaitCondition {
                    var changed = false
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as PagerViewer
                        val field = PagerViewer::class.java.getDeclaredField("currentSlot").apply {
                            isAccessible = true
                        }
                        changed = field.get(viewer) != originalSlot
                    }
                    changed
                }
            }
        } finally {
            mangaId?.let { repository.deleteMangaById(it) }
            restores.asReversed().forEach { it() }
            connections.setProfiles(previousProfiles)
            connections.activeConnectionId.set(previousActiveConnection)
            check(directory.canonicalFile.parentFile == IncomingMediaSessionLocator.cacheRoot(context).canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun pinch(view: View) {
        val downTime = SystemClock.uptimeMillis()
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, count: Int, distance: Float, step: Int) {
            val coordinates = Array(count) { index ->
                MotionEvent.PointerCoords().apply {
                    x = view.width / 2f + if (index == 0) -distance else distance
                    y = view.height / 2f
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime, downTime + step * 16L, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1, 100f, 0)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 100f, 1)
        for (step in 2..12) send(MotionEvent.ACTION_MOVE, 2, 100f + step * 25, step)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400f, 13)
        send(MotionEvent.ACTION_UP, 1, 400f, 14)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        if (view is ViewGroup) {
            for (child in view.children) {
                yield(child)
                yieldAll(descendants(child))
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for reader or saved image", false)
    }
}
