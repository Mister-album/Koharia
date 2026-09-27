package koharia.source.kavita

import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionProvider
import koharia.connection.LibraryConnectionProfile
import koharia.domain.kavita.KavitaRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class KavitaConnectionProvider(private val context: Context) : ConnectionProvider {
    override val id = ID
    override val displayName = "Kavita"
    override val iconRes = R.drawable.ic_kavita
    override val configuresConnectionNameInSettings = true
    override fun createSource(profile: LibraryConnectionProfile) = KavitaSource(context, profile)
    override fun createSettingsScreen(
        profile: LibraryConnectionProfile,
        titleOverride: String?,
        isNew: Boolean,
        completeOnboardingOnSave: Boolean,
    ) = KavitaSettingsScreen(profile.id, isNew, completeOnboardingOnSave, titleOverride)
    override suspend fun removeConnection(profile: LibraryConnectionProfile): Result<Boolean> = runCatching {
        (Injekt.get<SourceManager>().get(profile.id) as? KavitaSource)?.close()
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
        Injekt.get<KavitaRepository>().removeConnection(profile.id)
        Injekt.get<koharia.epub.cache.EpubCacheManager>().clearServer(profile.id)
        File(context.cacheDir, "kavita-pdf/${profile.id}").deleteRecursively()
        File(context.cacheDir, "kavita-transfer/${profile.id}").deleteRecursively()
        false
    }
    companion object {
        const val ID = "kavita"
    }
}
