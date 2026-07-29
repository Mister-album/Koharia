package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

sealed interface PagerSlot {

    data class Pages(
        val first: ReaderPage,
        val second: ReaderPage? = null,
    ) : PagerSlot {
        val pages: List<ReaderPage>
            get() = listOfNotNull(first, second)

        val progressPage: ReaderPage
            get() = second ?: first

        fun contains(page: ReaderPage): Boolean = first == page || second == page
    }

    data class Transition(val transition: ChapterTransition) : PagerSlot
}
