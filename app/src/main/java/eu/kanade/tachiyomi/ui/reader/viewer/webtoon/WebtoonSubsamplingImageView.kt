package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Implementation of subsampling scale image view that ignores all touch events, because the
 * webtoon viewer handles all the gestures.
 */
class WebtoonSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attrs) {

    override fun getExternalScale(): Float {
        var ancestor = parent
        while (ancestor != null) {
            if (ancestor is WebtoonRecyclerView) return ancestor.scaleX
            ancestor = ancestor.parent
        }
        return 1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }
}
