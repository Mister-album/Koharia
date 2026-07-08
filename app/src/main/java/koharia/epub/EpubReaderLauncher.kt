package koharia.epub

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.epub.settings.EpubReaderPreferences
import koharia.komga.api.dto.isEpub
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EpubReaderLauncher @JvmOverloads constructor(
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val epubReaderPreferences: EpubReaderPreferences = Injekt.get(),
) {

    fun launch(
        scope: CoroutineScope,
        context: Context,
        mangaId: Long,
        chapterId: Long,
    ) {
        scope.launch {
            context.startActivity(resolveIntent(context, mangaId, chapterId))
        }
    }

    suspend fun resolveIntent(
        context: Context,
        mangaId: Long,
        chapterId: Long,
    ): Intent {
        if (!epubReaderPreferences.enableNativeReader.get()) {
            return ReaderActivity.newIntent(context, mangaId, chapterId)
        }

        return withIOContext {
            val manga = getManga.await(mangaId)
            val chapter = getChapter.await(chapterId)
            val source = manga?.let { sourceManager.get(it.source) }

            if (manga == null || chapter == null || source !is KomgaSource) {
                return@withIOContext ReaderActivity.newIntent(context, mangaId, chapterId)
            }

            val localFile = downloadProvider.findChapterDir(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = manga.title,
                source = source,
            )

            val shouldOpenLocalEpub = localFile?.extension.equals("epub", ignoreCase = true)
            if (shouldOpenLocalEpub) {
                return@withIOContext EpubReaderActivity.newIntent(context, mangaId, chapterId)
            }

            try {
                val book = source.getBookDetails(chapter.url)
                if (book?.isEpub == true) {
                    EpubReaderActivity.newIntent(context, mangaId, chapterId)
                } else {
                    ReaderActivity.newIntent(context, mangaId, chapterId)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "EpubReaderLauncher failed to resolve reader type for chapterId=$chapterId"
                }
                ReaderActivity.newIntent(context, mangaId, chapterId)
            }
        }
    }
}
