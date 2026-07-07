package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.source.komga.KomgaSource
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SetMangaViewerFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetReadingMode(id: Long, flag: Long) {
        val manga = mangaRepository.getMangaById(id)
        val newFlags = manga.viewerFlags.setFlag(flag, ReadingMode.MASK.toLong())
        mangaRepository.update(
            MangaUpdate(
                id = id,
                viewerFlags = newFlags,
            ),
        )
        syncToKomga(manga.source, manga.url, newFlags)
    }

    suspend fun awaitSetOrientation(id: Long, flag: Long) {
        val manga = mangaRepository.getMangaById(id)
        val newFlags = manga.viewerFlags.setFlag(flag, ReaderOrientation.MASK.toLong())
        mangaRepository.update(
            MangaUpdate(
                id = id,
                viewerFlags = newFlags,
            ),
        )
        syncToKomga(manga.source, manga.url, newFlags)
    }

    private suspend fun syncToKomga(sourceId: Long, mangaUrl: String, flags: Long) {
        try {
            val sourceManager: SourceManager = Injekt.get()
            val komgaSource = sourceManager.get(sourceId) as? KomgaSource ?: return
            val seriesId = mangaUrl.substringAfterLast("/")
            if (seriesId.isNotBlank()) {
                komgaSource.updateMangaViewerFlags(seriesId, flags)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
