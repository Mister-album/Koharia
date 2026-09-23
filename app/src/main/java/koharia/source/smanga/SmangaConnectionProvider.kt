package koharia.source.smanga

import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionProvider
import koharia.connection.LibraryConnectionProfile
import koharia.domain.smanga.SmangaRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class SmangaConnectionProvider(private val context: Context) : ConnectionProvider {
    override val id = ID
    override val displayName = "smanga"
    override val iconRes = R.drawable.brand_smanga
    override val configuresConnectionNameInSettings = true
    override fun createSource(profile: LibraryConnectionProfile) = SmangaSource(context, profile)
    override fun createSettingsScreen(
        profile: LibraryConnectionProfile,
        titleOverride: String?,
        isNew: Boolean,
        completeOnboardingOnSave: Boolean,
    ) = SmangaSettingsScreen(profile.id, isNew, completeOnboardingOnSave, titleOverride)
    override suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> = runCatching {
        (Injekt.get<SourceManager>().get(profile.id) as? SmangaSource)?.close()
        val downloads = Injekt.get<DownloadManager>()
        downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
        val mangas = Injekt.get<MangaRepository>().getMangaBySourceId(profile.id)
        val chapterCache = Injekt.get<ChapterCache>()
        Injekt.get<ChapterRepository>().getChaptersByMangaIds(
            mangas.map {
                it.id
            },
        ).forEach(chapterCache::removePageListFromCache)
        val coverCache = Injekt.get<CoverCache>()
        mangas.forEach { coverCache.deleteFromCache(it) }
        Injekt.get<SmangaRepository>().removeConnection(profile.id)
        File(context.cacheDir, "smanga-pdf/${profile.id}").deleteRecursively()
        false
    }
    companion object {
        const val ID = "smanga"
    }
}
