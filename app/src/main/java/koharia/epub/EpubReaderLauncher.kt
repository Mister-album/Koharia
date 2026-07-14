package koharia.epub

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.epub.service.EpubReaderSupportResolver
import koharia.epub.settings.EpubReaderPreferences
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
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
    private val epubReaderPreferences: EpubReaderPreferences = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get(),
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
        val sourceId = withIOContext { getManga.await(mangaId)?.source }
        val scopedPreferences = sourceId
            ?.takeIf { it > 0L }
            ?.let(scopedPreferenceStoreFactory::epubReaderPreferences)
            ?: epubReaderPreferences

        if (forcePages) {
            return ReaderActivity.newIntent(context, mangaId, chapterId, sourceId)
        }

        return withIOContext {
            runCatching {
                if (supportResolver.resolve(
                        mangaId = mangaId,
                        chapterId = chapterId,
                        preferLocalFile = scopedPreferences.preferLocalFile.get(),
                    ).isNativeSupported
                ) {
                    EpubReaderActivity.newIntent(context, mangaId, chapterId, sourceId)
                } else {
                    ReaderActivity.newIntent(context, mangaId, chapterId, sourceId)
                }
            }.getOrElse { error ->
                logcat(LogPriority.WARN, error) {
                    "EpubReaderLauncher failed to resolve reader type for chapterId=$chapterId"
                }
                ReaderActivity.newIntent(context, mangaId, chapterId, sourceId)
            }
        }
    }
}
