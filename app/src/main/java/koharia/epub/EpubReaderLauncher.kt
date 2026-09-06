package koharia.epub

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionReaderRoutingAdapter
import koharia.connection.LibraryContentScope
import koharia.core.archive.epubReader
import koharia.epub.service.EpubReaderSupportResolver
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class EpubReaderLauncher @JvmOverloads constructor(
    private val supportResolver: EpubReaderSupportResolver = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
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

    fun launchAsPages(
        scope: CoroutineScope,
        context: Context,
        mangaId: Long,
        chapterId: Long,
    ) {
        scope.launch {
            context.startActivity(resolveIntent(context, mangaId, chapterId, forcePages = true))
        }
    }

    suspend fun resolveIntent(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        forcePages: Boolean = false,
    ): Intent {
        val useEpubSettings = !forcePages && withIOContext {
            val chapterUrl = getChapter.await(chapterId)?.url
            val extension = chapterUrl
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
            extension in LocalMediaFormats.reflowableBookExtensions
        }
        if (forcePages) {
            val sourceId = withIOContext { getManga.await(mangaId)?.source }
            return ReaderActivity.newIntent(context, mangaId, chapterId, sourceId)
        }

        return withIOContext {
            runCatching {
                val resolution = supportResolver.resolve(
                    mangaId = mangaId,
                    chapterId = chapterId,
                )
                val manga = getManga.await(mangaId)
                val chapter = getChapter.await(chapterId)
                val source = manga?.let { Injekt.get<SourceManager>().get(it.source) }
                val libraryScope = if (manga != null && chapter != null) {
                    (source as? ConnectionReaderRoutingAdapter)?.readerContentScope(manga, chapter)
                } else {
                    null
                }
                val route = libraryReaderRoute(
                    libraryScope ?: LibraryContentScope.ALL,
                    "pdf".takeIf { resolution.bookMediaType.equals("application/pdf", true) }
                        ?: resolution.bookFileName?.substringAfterLast('.', "")
                        ?: chapter?.url?.substringBefore('?')?.substringAfterLast('.', ""),
                    resolution.isNativeSupported,
                    resolution.shouldOpenAsPages || (
                        libraryScope == LibraryContentScope.COMIC &&
                            resolution.isNativeSupported && (source as? ConnectionLocalFileAdapter)
                                ?.localChapterFile(checkNotNull(chapter).url)?.let { file ->
                                    file.epubReader(context).use { it.isImageOnlyPublication() }
                                } == true
                        ),
                )
                when (route) {
                    LibraryReaderRoute.PDF_REFLOW -> EpubReaderActivity.newPdfReflowIntent(
                        context,
                        mangaId,
                        chapterId,
                        resolution.sourceId,
                        chapter?.lastPageRead?.toInt(),
                        resolution,
                    )
                    LibraryReaderRoute.NATIVE_TEXT ->
                        EpubReaderActivity.newIntent(context, mangaId, chapterId, resolution.sourceId, resolution)
                    else ->
                        ReaderActivity.newIntent(
                            context = context,
                            mangaId = mangaId,
                            chapterId = chapterId,
                            sourceId = resolution.sourceId,
                            useEpubSettings = useEpubSettings && libraryScope != LibraryContentScope.COMIC,

                        )
                }
            }.getOrElse { error ->
                logcat(LogPriority.WARN, error) {
                    "EpubReaderLauncher failed to resolve reader type for chapterId=$chapterId"
                }
                val sourceId = getManga.await(mangaId)?.source
                ReaderActivity.newIntent(
                    context = context,
                    mangaId = mangaId,
                    chapterId = chapterId,
                    sourceId = sourceId,
                    useEpubSettings = useEpubSettings,
                )
            }
        }
    }
}
