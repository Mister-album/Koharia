package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.view.View
import android.view.ViewGroup

internal class DoublePageLayout(
    context: Context,
    private val firstPage: DoublePageCompositionPolicy.Image,
    private val secondPage: DoublePageCompositionPolicy.Image,
    private val onSplitFractionChanged: (Float) -> Unit,
) : ViewGroup(context) {

    private var spreadLayout: DoublePageCompositionPolicy.ViewportLayout? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        val layout = DoublePageCompositionPolicy.fitInViewport(firstPage, secondPage, width, height)
        spreadLayout = layout
        if (layout == null) {
            measureFallback(width, height)
            onSplitFractionChanged(0.5f)
            return
        }

        children().forEachIndexed { index, child ->
            val childWidth = if (index == 0) layout.firstWidth else layout.secondWidth
            child.measure(exactly(childWidth), exactly(layout.height))
        }
        onSplitFractionChanged(layout.splitFraction)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val layout = spreadLayout
        if (layout == null) {
            layoutFallback(right - left, bottom - top)
            return
        }

        var childLeft = layout.left
        children().forEach { child ->
            child.layout(childLeft, layout.top, childLeft + child.measuredWidth, layout.top + layout.height)
            childLeft += child.measuredWidth
        }
    }

    private fun measureFallback(width: Int, height: Int) {
        val firstWidth = width / 2
        children().forEachIndexed { index, child ->
            val childWidth = if (index == 0) firstWidth else width - firstWidth
            child.measure(exactly(childWidth), exactly(height))
        }
    }

    private fun layoutFallback(width: Int, height: Int) {
        var childLeft = 0
        children().forEach { child ->
            child.layout(childLeft, 0, childLeft + child.measuredWidth, height)
            childLeft += child.measuredWidth
        }
    }

    private fun children(): Sequence<View> = (0 until childCount).asSequence().map(::getChildAt)

    private fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
