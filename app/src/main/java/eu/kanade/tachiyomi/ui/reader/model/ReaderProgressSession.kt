package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.Source
import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionEpubProgressSessionAdapter
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionPageProgressSessionAdapter
import koharia.connection.ConnectionReaderPageProgressAdapter
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterProgressOwner
import tachiyomi.domain.source.service.SourceManager

internal class ReaderProgressSession(
    val owner: ChapterProgressOwner,
    val pages: ConnectionReaderPageProgressAdapter?,
    val epub: ConnectionEpubProgressAdapter?,
    val checkCurrent: suspend () -> Unit,
) {
    companion object {
        suspend fun capture(
            source: Source?,
            sourceId: Long,
            chapter: Chapter,
            manager: SourceManager,
        ): ReaderProgressSession {
            fun checkSource() {
                if (koharia.connection.ConnectionRestoreState.isRestoring ||
                    manager.get(sourceId) !== source
                ) {
                    throw CancellationException("Reader source replaced")
                }
            }
            checkSource()
            if (koharia.connection.DevReadOnlyValidation.enabled) {
                return ReaderProgressSession(
                    ChapterProgressOwner(sourceId, chapter.mangaId, chapter.id, chapter.url),
                    null,
                    null,
                    { checkSource() },
                )
            }
            check(source !is ConnectionPageProgressAdapter || source is ConnectionPageProgressSessionAdapter) {
                "Page progress provider requires an opening session"
            }
            val pages = (source as? ConnectionPageProgressSessionAdapter)?.capturePageProgressSession(chapter)
            val epub = (source as? ConnectionEpubProgressSessionAdapter)?.captureEpubProgressSession(chapter)
            val check: suspend () -> Unit = {
                checkSource()
                pages?.checkCurrent?.invoke()
                epub?.checkCurrent?.invoke()
            }
            check()
            return ReaderProgressSession(
                ChapterProgressOwner(sourceId, chapter.mangaId, chapter.id, chapter.url),
                pages?.adapter,
                epub?.adapter,
                check,
            )
        }
    }
}
