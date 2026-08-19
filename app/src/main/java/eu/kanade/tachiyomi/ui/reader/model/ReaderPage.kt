package eu.kanade.tachiyomi.ui.reader.model

import android.graphics.Bitmap
import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    var bitmap: (() -> Bitmap)? = null

    open lateinit var chapter: ReaderChapter

    var spreadInfo: SpreadInfo = SpreadInfo.UNKNOWN

    val spreadKind: SpreadKind
        get() = spreadInfo.kind

    data class SpreadInfo(
        val kind: SpreadKind,
        val width: Int? = null,
        val height: Int? = null,
    ) {
        companion object {
            val UNKNOWN = SpreadInfo(SpreadKind.UNKNOWN)
        }
    }

    enum class SpreadKind {
        UNKNOWN,
        PAIRABLE,
        WIDE,
        ANIMATED,
        ;

        val occupiesFullSlot: Boolean
            get() = this == WIDE || this == ANIMATED
    }
}
