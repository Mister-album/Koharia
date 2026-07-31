package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.eschao.android.widget.pageflip.OnPageFlipListener
import com.eschao.android.widget.pageflip.PageFlip
import com.eschao.android.widget.pageflip.PageFlipException
import com.eschao.android.widget.pageflip.PageFlipState
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL page-flip surface used only by the paged comic reader.
 *
 * The real pager is already positioned on the destination page below this surface. This view owns
 * immutable snapshots of the source and destination until the flip finishes, so ViewPager layout,
 * SSIV tile loading, and progress callbacks cannot replace a texture midway through the animation.
 */
internal class ComicPageFlipView(
    context: Context,
    private val source: Bitmap,
    private val destination: Bitmap,
    private val forward: Boolean,
    private val origin: PageTurnOrigin,
    private val onFirstFrame: () -> Unit,
    private val onFinished: (Boolean) -> Unit,
) : GLSurfaceView(context), GLSurfaceView.Renderer, OnPageFlipListener {

    private enum class DrawState {
        STATIC,
        ANIMATING,
        FINISHED,
    }

    private val pageFlip = PageFlip(context).apply {
        enableAutoPage(false)
        enableClickToFlip(true)
            .setSemiPerimeterRatio(0.8f)
            .setShadowWidthOfFoldEdges(5f, 52f, 0.28f)
            .setShadowWidthOfFoldBase(4f, 64f, 0.34f)
            .setPixelsOfMesh(MESH_PIXELS)
            .setListener(this@ComicPageFlipView)
    }

    @Volatile
    private var drawState = DrawState.STATIC
    private var surfaceReady = false
    private var texturesReady = false
    private var firstFrameReported = false
    private var completionReported = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            pageFlip.onSurfaceCreated()
            // The upstream renderer clears to opaque black. A newly attached SurfaceView can
            // expose that empty buffer for one compositor frame before the first page texture is
            // swapped. Keep the surface transparent so the protected source page remains visible.
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            logcat(LogPriority.DEBUG) { "Comic page flip translucent GL surface created" }
        } catch (error: PageFlipException) {
            reportFailure("create GL surface", error)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        try {
            pageFlip.onSurfaceChanged(width, height)
            pageFlip.getFirstPage().deleteAllTextures()
            texturesReady = false
            surfaceReady = true
            requestRender()
        } catch (error: PageFlipException) {
            reportFailure("resize GL surface to ${width}x$height", error)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!surfaceReady || drawState == DrawState.FINISHED) return
        try {
            pageFlip.deleteUnusedTextures()
            prepareTextures()
            if (drawState == DrawState.STATIC) {
                pageFlip.drawPageFrame()
                if (!firstFrameReported) {
                    firstFrameReported = true
                    post { onFirstFrame() }
                }
                return
            }

            prepareAnimatedTextures()
            // PageFlip.animating() advances the scroller and builds the curl vertexes for the
            // current frame. Drawing first clears the surface but has no vertexes on the initial
            // animation frame, which produces a single fully black buffer before the curl appears.
            // Do not draw after animating() returns false either, because the previous completed
            // frame should remain visible until the destination pager takes over.
            if (!pageFlip.animating()) {
                val completed = pageFlip.flipState ==
                    if (forward) PageFlipState.END_WITH_FORWARD else PageFlipState.END_WITH_BACKWARD
                finish(completed)
                return
            }
            pageFlip.drawFlipFrame()
            requestRender()
        } catch (error: Throwable) {
            reportFailure("draw page flip", error)
        }
    }

    fun startFlip() {
        queueEvent {
            if (!surfaceReady || drawState != DrawState.STATIC) return@queueEvent
            val x = if (forward) width - EDGE_INSET_PX else EDGE_INSET_PX
            val y = (origin.yFraction * height).coerceIn(EDGE_INSET_PX, height - EDGE_INSET_PX)
            pageFlip.onFingerDown(x, y)
            val started = pageFlip.onFingerUp(x, y, DURATION_MS)
            if (!started || !pageFlip.isAnimating) {
                reportFailure("start ${if (forward) "forward" else "backward"} page flip")
                return@queueEvent
            }
            drawState = DrawState.ANIMATING
            logcat(LogPriority.DEBUG) {
                "Comic page flip GL animation started direction=${if (forward) "forward" else "backward"} " +
                    "origin=${origin.xFraction},${origin.yFraction} texture=${source.width}x${source.height}"
            }
            requestRender()
        }
    }

    fun release() {
        if (surfaceReady) {
            queueEvent {
                pageFlip.abortAnimating()
                pageFlip.getFirstPage().deleteAllTextures()
                pageFlip.deleteUnusedTextures()
                pageFlip.setListener(null)
            }
        }
        onPause()
    }

    override fun canFlipForward(): Boolean = forward

    override fun canFlipBackward(): Boolean {
        if (forward) return false
        // PageFlip's backward path expects the current page in SECOND_TEXTURE and the page being
        // revealed in FIRST_TEXTURE. This is intentionally not a mirrored forward animation.
        pageFlip.getFirstPage().setSecondTextureWithFirst()
        return true
    }

    private fun prepareTextures() {
        if (texturesReady) return
        val page = pageFlip.getFirstPage()
        page.setFirstTexture(source)
        if (forward) {
            page.setSecondTexture(destination)
            page.setBackTexture(destination)
        }
        texturesReady = true
        logcat(LogPriority.DEBUG) {
            "Comic page flip textures ready source=${source.width}x${source.height} " +
                "destination=${destination.width}x${destination.height} forward=$forward"
        }
    }

    private fun prepareAnimatedTextures() {
        val page = pageFlip.getFirstPage()
        if (forward) {
            if (!page.isSecondTextureSet) page.setSecondTexture(destination)
        } else if (!page.isFirstTextureSet) {
            page.setFirstTexture(destination)
        }
        if (!page.isBackTextureSet) page.setBackTexture(destination)
    }

    private fun finish(completed: Boolean) {
        if (completionReported) return
        completionReported = true
        drawState = DrawState.FINISHED
        logcat(if (completed) LogPriority.DEBUG else LogPriority.WARN) {
            "Comic page flip GL animation finished completed=$completed state=${pageFlip.flipState}"
        }
        post { onFinished(completed) }
    }

    private fun reportFailure(stage: String, error: Throwable? = null) {
        if (completionReported) return
        logcat(LogPriority.ERROR, error) { "Comic page flip failed to $stage" }
        finish(false)
    }

    private companion object {
        const val DURATION_MS = 560
        const val MESH_PIXELS = 14
        const val EDGE_INSET_PX = 1f
    }
}
