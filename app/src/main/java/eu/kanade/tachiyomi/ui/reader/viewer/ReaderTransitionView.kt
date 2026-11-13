package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import eu.kanade.presentation.reader.ChapterTransition
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import koharia.connection.ConnectionReadingQueueController
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class ReaderTransitionView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AbstractComposeView(context, attrs) {

    private var data: Data? by mutableStateOf(null)

    init {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    fun bind(
        transition: ChapterTransition,
        downloadManager: DownloadManager,
        manga: Manga?,
        queue: ConnectionReadingQueueController,
    ) {
        data = if (manga != null) {
            Data(
                transition = transition,
                queue = queue,
                currChapterDownloaded = transition.from.pageLoader?.isLocal == true,
                goingToChapterDownloaded = transition.to?.chapter?.let { goingToChapter ->
                    downloadManager.isChapterDownloaded(
                        chapterName = goingToChapter.name,
                        chapterScanlator = goingToChapter.scanlator,
                        chapterUrl = goingToChapter.url,
                        mangaTitle = manga.title,
                        sourceId = manga.source,
                        skipCache = true,
                    )
                } ?: false,
            )
        } else {
            null
        }
    }

    @Composable
    override fun Content() {
        data?.let {
            TachiyomiTheme {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodySmall,
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                    ChapterTransition(
                        transition = it.transition,
                        currChapterDownloaded = it.currChapterDownloaded,
                        goingToChapterDownloaded = it.goingToChapterDownloaded,
                        queueDestination = if (it.queue.active) {
                            val position = it.queue.position
                            val forward = it.transition is ChapterTransition.Next
                            when {
                                position == null -> stringResource(MR.strings.transition_pages_loading)
                                (if (forward) position.next else position.previous) != null -> {
                                    val index = position.index + if (forward) 2 else 0
                                    "${position.title} · $index/${position.size}"
                                }
                                else -> null
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    private data class Data(
        val transition: ChapterTransition,
        val queue: ConnectionReadingQueueController,
        val currChapterDownloaded: Boolean,
        val goingToChapterDownloaded: Boolean,
    )
}
