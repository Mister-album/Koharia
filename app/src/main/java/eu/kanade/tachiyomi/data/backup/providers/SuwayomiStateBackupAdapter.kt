package eu.kanade.tachiyomi.data.backup.providers

import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.suwayomi.SuwayomiIdentity
import koharia.suwayomi.SuwayomiReadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Serializable
data class BackupSuwayomiState(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val payload: String,
    @ProtoNumber(3) val revision: Long,
    @ProtoNumber(4) val pending: Boolean,
)

class SuwayomiStateBackupAdapter(
    private val repository: SuwayomiRepository = Injekt.get(),
    private val mangas: MangaRepository = Injekt.get(),
    private val chapters: ChapterRepository = Injekt.get(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun capture(mangaId: Long, chapterUrlById: Map<Long, String>): List<BackupSuwayomiState> {
        val manga = mangas.getMangaById(mangaId)
        val identity = SuwayomiIdentity.fromMangaUrl(manga.url)?.takeIf { it.connectionId == manga.source }
            ?: return emptyList()
        val stored = chapters.getChapterByMangaId(mangaId).associate { it.id to it.url }
        val operations = repository.operations(identity.connectionId, identity.account).associateBy { it.key }
        return chapterUrlById.mapNotNull { (id, url) ->
            if (stored[id] != url) return@mapNotNull null
            val (remoteManga, remoteChapter) = runCatching { identity.chapter(url) }.getOrNull()
                ?: return@mapNotNull null
            if (remoteManga != identity.mangaId(manga.url)) return@mapNotNull null
            operations[remoteChapter.toString()]?.let { BackupSuwayomiState(url, it.payload, it.revision, it.pending) }
        }
    }

    suspend fun restore(mangaId: Long, chapterIdByUrl: Map<String, Long>, entries: List<BackupSuwayomiState>) {
        if (entries.isEmpty()) return
        val manga = mangas.getMangaById(mangaId)
        val identity = SuwayomiIdentity.fromMangaUrl(manga.url)?.takeIf { it.connectionId == manga.source } ?: return
        val stored = chapters.getChapterByMangaId(mangaId).associate { it.url to it.id }
        val previous = repository.operations(identity.connectionId, identity.account).associateBy { it.key }
        for (entry in entries) {
            if (stored[entry.chapterUrl] == null ||
                stored[entry.chapterUrl] != chapterIdByUrl[entry.chapterUrl]
            ) {
                continue
            }
            val (remoteManga, remoteChapter) = runCatching { identity.chapter(entry.chapterUrl) }.getOrNull()
                ?: continue
            val state = runCatching { json.decodeFromString<SuwayomiReadState>(entry.payload) }.getOrNull() ?: continue
            if (remoteManga != identity.mangaId(manga.url) || state.mangaId != remoteManga ||
                state.chapterId != remoteChapter ||
                state.page < 0 || state.count < 0 || (state.count > 0 && state.page >= state.count)
            ) {
                continue
            }
            val old = previous[remoteChapter.toString()]
            val selected = old?.takeIf { it.pending || it.revision > entry.revision }
            val restored =
                selected?.let { runCatching { json.decodeFromString<SuwayomiReadState>(it.payload) }.getOrNull() }
                    ?: state
            repository.putOperation(
                identity.connectionId,
                identity.account,
                SuwayomiOperation(
                    remoteChapter.toString(),
                    json.encodeToString(restored.copy(conflict = true, initial = false)),
                    maxOf(System.currentTimeMillis(), entry.revision, old?.revision ?: 0) + 1,
                    selected?.pending ?: entry.pending,
                ),
            )
        }
    }
}
