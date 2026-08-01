package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.transition.PageCoverShadowDrawable
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PagerPageTransformer(
    private val effect: PageTransitionEffect,
    private val horizontalPager: Boolean,
    private val rightToLeft: Boolean,
    private val readerBackgroundColor: () -> Int = { Color.BLACK },
) : ViewPager.PageTransformer {

    private val previousPositions = WeakHashMap<View, Float>()
    private val movingAwayStates = WeakHashMap<View, Boolean>()
    private val coverIncomingStates = WeakHashMap<View, Boolean>()
    private val coverShadows = WeakHashMap<View, PageCoverShadowDrawable>()
    private val coverFallbackBackgrounds = WeakHashMap<View, ColorDrawable>()

    override fun transformPage(page: View, position: Float) {
        val previousPosition = previousPositions.put(page, position)
        reset(page)
        if (position < -1f || position > 1f) {
            clearCoverShadow(page)
            page.alpha = 0f
            return
        }

        when (effect) {
            PageTransitionEffect.SLIDE,
            PageTransitionEffect.NONE,
            -> Unit

            PageTransitionEffect.COVER -> transformCover(page, position, previousPosition)
            PageTransitionEffect.CURL -> transformCurl(page, position)
            PageTransitionEffect.VERTICAL -> transformVertical(page, position)
            PageTransitionEffect.FADE -> transformFade(page, position, previousPosition)
            PageTransitionEffect.DEPTH -> transformDepth(page, position)
        }
    }

    private fun transformCover(page: View, position: Float, previousPosition: Float?) {
        ensureOpaqueCoverBackground(page)
        val distance = abs(position).coerceIn(0f, 1f)
        val previousDistance = previousPosition?.let(::abs)
        val incoming = when {
            previousDistance == null -> distance > 0.5f
            distance < previousDistance - POSITION_EPSILON -> true
            distance > previousDistance + POSITION_EPSILON -> false
            else -> coverIncomingStates[page] ?: (distance > 0.5f)
        }
        coverIncomingStates[page] = incoming

        if (!incoming) {
            // ViewPager normally moves both pages. Pin the old page in place so the target page
            // can slide over it without exposing the pager background.
            cancelPagerTranslation(page, position)
            page.translationZ = 0f
            clearCoverShadow(page)
            return
        }

        page.translationZ = page.resources.displayMetrics.density * COVER_PAGE_ELEVATION_DP
        updateCoverShadow(page, position, distance)
    }

    private fun updateCoverShadow(page: View, position: Float, distance: Float) {
        if (distance <= POSITION_EPSILON || page.width <= 0 || page.height <= 0) {
            clearCoverShadow(page)
            return
        }
        val shadow = coverShadows.getOrPut(page) {
            PageCoverShadowDrawable().also(page.overlay::add)
        }
        val thickness = (page.resources.displayMetrics.density * COVER_SHADOW_WIDTH_DP)
            .roundToInt()
            .coerceAtLeast(1)
        if (horizontalPager) {
            if (position > 0f) {
                shadow.edge = PageCoverShadowDrawable.Edge.LEFT
                shadow.setBounds(0, 0, thickness.coerceAtMost(page.width), page.height)
            } else {
                shadow.edge = PageCoverShadowDrawable.Edge.RIGHT
                shadow.setBounds((page.width - thickness).coerceAtLeast(0), 0, page.width, page.height)
            }
        } else if (position > 0f) {
            shadow.edge = PageCoverShadowDrawable.Edge.TOP
            shadow.setBounds(0, 0, page.width, thickness.coerceAtMost(page.height))
        } else {
            shadow.edge = PageCoverShadowDrawable.Edge.BOTTOM
            shadow.setBounds(0, (page.height - thickness).coerceAtLeast(0), page.width, page.height)
        }
        shadow.alpha = (distance * COVER_SHADOW_FADE_MULTIPLIER)
            .coerceIn(0f, 1f)
            .times(255f)
            .roundToInt()
    }

    private fun clearCoverShadow(page: View) {
        coverShadows.remove(page)?.let(page.overlay::remove)
    }

    private fun ensureOpaqueCoverBackground(page: View) {
        val fallback = coverFallbackBackgrounds[page]
        if (fallback != null && page.background === fallback) {
            fallback.color = readerBackgroundColor()
            return
        }
        if (page.background != null) {
            coverFallbackBackgrounds.remove(page)
            return
        }
        ColorDrawable(readerBackgroundColor()).also {
            coverFallbackBackgrounds[page] = it
            page.background = it
        }
    }

    private fun clearCoverBackground(page: View) {
        val fallback = coverFallbackBackgrounds.remove(page) ?: return
        if (page.background === fallback) {
            page.background = null
        }
    }

    fun clear(pages: Iterable<View>) {
        pages.forEach { page ->
            clearCoverShadow(page)
            clearCoverBackground(page)
            reset(page)
        }
        previousPositions.clear()
        movingAwayStates.clear()
        coverIncomingStates.clear()
    }

    private fun transformCurl(page: View, position: Float) {
        val logicalPosition = if (rightToLeft && horizontalPager) -position else position
        val amount = logicalPosition.coerceIn(-1f, 1f)
        page.cameraDistance = page.resources.displayMetrics.density * 12_000f
        page.alpha = (1f - abs(amount) * 0.18f).coerceIn(0f, 1f)
        if (horizontalPager) {
            page.pivotX = if (amount > 0f) 0f else page.width.toFloat()
            page.pivotY = page.height / 2f
            page.rotationY = -amount * 24f
        } else {
            page.pivotX = page.width / 2f
            page.pivotY = if (amount > 0f) 0f else page.height.toFloat()
            page.rotationX = amount * 18f
        }
        page.translationZ = (1f - abs(amount)) * page.resources.displayMetrics.density * 2f
    }

    private fun transformVertical(page: View, position: Float) {
        if (horizontalPager) {
            val logicalPosition = if (rightToLeft) -position else position
            page.translationX = -position * page.width
            page.translationY = logicalPosition * page.height
        }
    }

    private fun transformFade(page: View, position: Float, previousPosition: Float?) {
        cancelPagerTranslation(page, position)
        val distance = abs(position).coerceIn(0f, 1f)
        if (distance >= 1f) {
            page.alpha = 0f
            return
        }
        val previousDistance = previousPosition?.let(::abs)
        val movingAwayFromCenter = when {
            previousDistance == null -> false
            distance > previousDistance + POSITION_EPSILON -> true
            distance < previousDistance - POSITION_EPSILON -> false
            else -> movingAwayStates[page] ?: false
        }
        movingAwayStates[page] = movingAwayFromCenter
        if (movingAwayFromCenter) {
            page.alpha = 1f - distance
            page.translationZ = page.resources.displayMetrics.density
        } else {
            // Keep the target page opaque below the fading old page so the pager background
            // cannot flash through at the midpoint of the transition.
            page.alpha = 1f
            page.translationZ = 0f
        }
    }

    private fun transformDepth(page: View, position: Float) {
        val progress = abs(position).coerceIn(0f, 1f)
        val scale = 1f - progress * 0.06f
        page.scaleX = scale
        page.scaleY = scale
        page.alpha = 1f - progress * 0.08f
        if (horizontalPager) {
            page.pivotX = if (position < 0f) page.width.toFloat() else 0f
            page.pivotY = page.height / 2f
        } else {
            page.pivotX = page.width / 2f
            page.pivotY = if (position < 0f) page.height.toFloat() else 0f
        }
    }

    private fun cancelPagerTranslation(page: View, position: Float) {
        if (horizontalPager) {
            page.translationX = -position * page.width
        } else {
            page.translationY = -position * page.height
        }
    }

    private fun reset(page: View) {
        page.alpha = 1f
        page.translationX = 0f
        page.translationY = 0f
        page.translationZ = 0f
        page.rotationX = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.cameraDistance = page.resources.displayMetrics.density * DEFAULT_CAMERA_DISTANCE_DP
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
    }

    private companion object {
        const val DEFAULT_CAMERA_DISTANCE_DP = 1_280f
        const val POSITION_EPSILON = 0.001f
        const val COVER_PAGE_ELEVATION_DP = 2f
        const val COVER_SHADOW_WIDTH_DP = 18f
        const val COVER_SHADOW_FADE_MULTIPLIER = 4f
    }
}
