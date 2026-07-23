package koharia.epub

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.epub.service.EpubReaderSupportResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EpubReaderLauncher @JvmOverloads constructor(
    private val supportResolver: EpubReaderSupportResolver = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
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
                when {
                    resolution.shouldOpenAsPages ->
                        ReaderActivity.newIntent(context, mangaId, chapterId, resolution.sourceId)
                    resolution.isNativeSupported ->
                        EpubReaderActivity.newIntent(context, mangaId, chapterId, resolution.sourceId, resolution)
                    else ->
                        ReaderActivity.newIntent(context, mangaId, chapterId, resolution.sourceId)
                }
            }.getOrElse { error ->
                logcat(LogPriority.WARN, error) {
                    "EpubReaderLauncher failed to resolve reader type for chapterId=$chapterId"
                }
                val sourceId = getManga.await(mangaId)?.source
                ReaderActivity.newIntent(context, mangaId, chapterId, sourceId)
            }
        }
    }
}
