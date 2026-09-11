package koharia.source.lanraragi

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionProvider
import koharia.connection.LibraryConnectionProfile
import koharia.domain.lanraragi.LanraragiRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiConnectionProvider(private val context: Context) : ConnectionProvider {
    override val id = ID
    override val displayName = "LANraragi"
    override val configuresConnectionNameInSettings = true
    override val deletionMessage = tachiyomi.i18n.MR.strings.lanraragi_remove_connection
    override fun createSource(profile: LibraryConnectionProfile): LanraragiSource {
        require(profile.providerId == ID)
        return LanraragiSource(context, profile)
    }
    override fun createSettingsScreen(
        profile: LibraryConnectionProfile,
        titleOverride: String?,
        isNew: Boolean,
        completeOnboardingOnSave: Boolean,
    ) =
        LanraragiSettingsScreen(profile.id, isNew, completeOnboardingOnSave, titleOverride)

    override suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> = runCatching {
        val source = Injekt.get<SourceManager>().get(profile.id) as? LanraragiSource
        source?.removeLocalConnectionState()
        val downloads = Injekt.get<DownloadManager>()
        downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
        val mangas = Injekt.get<MangaRepository>().getMangaBySourceId(profile.id)
        val chapterCache = Injekt.get<ChapterCache>()
        Injekt.get<ChapterRepository>().getChaptersByMangaIds(
            mangas.map {
                it.id
            },
        ).forEach(chapterCache::removePageListFromCache)
        val covers = Injekt.get<CoverCache>()
        mangas.forEach { covers.deleteFromCache(it, deleteCustomCover = false) }
        if (source == null) Injekt.get<LanraragiRepository>().remove(profile.id)
        false
    }
    companion object {
        const val ID = "lanraragi"
    }
}
