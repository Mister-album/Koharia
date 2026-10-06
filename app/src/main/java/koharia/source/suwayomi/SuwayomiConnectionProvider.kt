package koharia.source.suwayomi

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionProvider
import koharia.connection.LibraryConnectionProfile
import koharia.domain.suwayomi.SuwayomiRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiConnectionProvider(private val context: Context) : ConnectionProvider {
    override val id = ID
    override val displayName = "Suwayomi"
    override val iconRes = eu.kanade.tachiyomi.R.drawable.brand_suwayomi
    override val configuresConnectionNameInSettings = true
    override fun createSource(profile: LibraryConnectionProfile): SuwayomiSource {
        require(profile.providerId == id)
        return SuwayomiSource(context, profile)
    }
    override fun createSettingsScreen(
        profile: LibraryConnectionProfile,
        titleOverride: String?,
        isNew: Boolean,
        completeOnboardingOnSave: Boolean,
    ) = SuwayomiSettingsScreen(profile.id, isNew, completeOnboardingOnSave, titleOverride)

    override suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> = runCatching {
        (Injekt.get<SourceManager>().get(profile.id) as? SuwayomiSource)?.close()
        val downloads = Injekt.get<DownloadManager>()
        downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
        val mangas = Injekt.get<MangaRepository>().getMangaBySourceId(profile.id)
        val chapterCache = Injekt.get<ChapterCache>()
        Injekt.get<ChapterRepository>().getChaptersByMangaIds(mangas.map { it.id })
            .forEach(chapterCache::removePageListFromCache)
        val coverCache = Injekt.get<CoverCache>()
        mangas.forEach { coverCache.deleteFromCache(it) }
        Injekt.get<SuwayomiRepository>().removeConnection(profile.id)
        false
    }

    companion object {
        const val ID = "suwayomi"
    }
}
