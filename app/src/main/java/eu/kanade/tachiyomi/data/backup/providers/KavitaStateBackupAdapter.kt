package eu.kanade.tachiyomi.data.backup.providers

import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.KavitaBookmarkState
import koharia.kavita.KavitaIdentity
import koharia.kavita.KavitaReadingState
import koharia.kavita.kavitaTimestamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Serializable
data class BackupKavitaState(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val payload: String,
    @ProtoNumber(3) val revision: Long,
    @ProtoNumber(4) val pending: Boolean,
    @ProtoNumber(5) val operationKey: String = "",
)

@Serializable
data class BackupKavitaAnnotation(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val key: String,
    @ProtoNumber(3) val payload: String,
    @ProtoNumber(4) val revision: Long,
    @ProtoNumber(5) val pending: Boolean,
)

class KavitaStateBackupAdapter(
    private val repository: KavitaRepository = Injekt.get(),
    private val mangas: MangaRepository = Injekt.get(),
    private val chapters: ChapterRepository = Injekt.get(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private suspend fun identity(mangaId: Long): KavitaIdentity? {
        val manga = mangas.getMangaById(mangaId)
        val parts = manga.url.split('/')
        if (parts.size != 6 || parts[1] != "kavita" || parts[4] != "series") return null
        return runCatching { KavitaIdentity(manga.source, parts[3]).also { it.seriesId(manga.url) } }.getOrNull()
    }
    suspend fun capture(mangaId: Long, chapterUrls: Map<Long, String>): List<BackupKavitaState> {
        val identity = identity(mangaId) ?: return emptyList()
        val stored = chapters.getChapterByMangaId(mangaId).associate { it.id to it.url }
        val operations = repository.operations(identity.connectionId, identity.account).associateBy { it.key }
        return chapterUrls.flatMap { (id, url) ->
            if (stored[id] != url) return@flatMap emptyList()
            val ref = runCatching { identity.chapter(url) }.getOrNull() ?: return@flatMap emptyList()
            buildList {
                operations["progress/${ref.chapterId}"]?.let { operation ->
                    val state = runCatching { json.decodeFromString<KavitaReadingState>(operation.payload) }.getOrNull()
                    if (state?.ref == ref) {
                        add(
                            BackupKavitaState(
                                url,
                                operation.payload,
                                operation.revision,
                                operation.pending,
                            ),
                        )
                    }
                }
                for (operation in operations.values.filter { it.pending && it.key.startsWith("bookmark/") }) {
                    val state = runCatching {
                        json.decodeFromString<KavitaBookmarkState>(operation.payload)
                    }.getOrNull()
                    if (state?.ref == ref && state.key == operation.key) {
                        add(
                            BackupKavitaState(
                                url,
                                operation.payload,
                                operation.revision,
                                true,
                                operation.key,
                            ),
                        )
                    }
                }
            }
        }
    }
    suspend fun restore(mangaId: Long, chapterIds: Map<String, Long>, backup: List<BackupKavitaState>) {
        val identity = identity(mangaId) ?: return
        val stored = chapters.getChapterByMangaId(mangaId).associate { it.url to it.id }
        val existing = repository.operations(identity.connectionId, identity.account).associateBy { it.key }
        for (entry in backup) {
            if (chapterIds[entry.chapterUrl] == null ||
                chapterIds[entry.chapterUrl] != stored[entry.chapterUrl]
            ) {
                continue
            }
            val ref = runCatching { identity.chapter(entry.chapterUrl) }.getOrNull() ?: continue
            if (entry.operationKey.startsWith("bookmark/")) {
                val state = runCatching { json.decodeFromString<KavitaBookmarkState>(entry.payload) }.getOrNull()
                    ?: continue
                if (state.ref != ref || state.key != entry.operationKey || state.page < 0 || state.imageOffset < 0 ||
                    repository.operations(identity.connectionId, identity.account).any { it.key == state.key }
                ) {
                    continue
                }
                // Pending backup actions need an explicit decision before modifying the server.
                repository.putOperation(
                    identity.connectionId,
                    identity.account,
                    KavitaOperation(
                        state.key,
                        json.encodeToString(state.copy(needsConfirmation = true)),
                        entry.revision.coerceAtLeast(0) + 1,
                        true,
                    ),
                )
                continue
            }
            if (entry.operationKey.isNotEmpty()) continue
            val state = runCatching { json.decodeFromString<KavitaReadingState>(entry.payload) }.getOrNull() ?: continue
            if (state.ref != ref || state.totalPages <= 0 || state.progress.pageNum !in 0..state.totalPages) continue
            val key = "progress/${ref.chapterId}"
            val old = existing[key]
            val previous = old?.let {
                runCatching { json.decodeFromString<KavitaReadingState>(it.payload) }.getOrNull()
            }
            if (previous != null &&
                kavitaTimestamp(previous.progress.lastModifiedUtc) >= kavitaTimestamp(state.progress.lastModifiedUtc)
            ) {
                continue
            }
            // Restoring an old backup must not overwrite a newer server position automatically.
            repository.putOperation(
                identity.connectionId,
                identity.account,
                KavitaOperation(
                    key,
                    json.encodeToString(state.copy(conflict = entry.pending)),
                    maxOf(entry.revision, old?.revision ?: 0) + 1,
                    entry.pending,
                ),
            )
        }
    }

    suspend fun captureAnnotations(mangaId: Long, chapterUrls: Map<Long, String>): List<BackupKavitaAnnotation> {
        val identity = identity(mangaId) ?: return emptyList()
        val stored = chapters.getChapterByMangaId(mangaId).associate { it.id to it.url }
        return chapterUrls.filter { stored[it.key] == it.value }.values.flatMap { url ->
            val ref = runCatching { identity.chapter(url) }.getOrNull() ?: return@flatMap emptyList()
            repository.annotations(identity.connectionId, identity.account, ref.chapterId).map {
                BackupKavitaAnnotation(url, it.key, it.payload, it.revision, it.pending)
            }
        }
    }

    suspend fun restoreAnnotations(mangaId: Long, chapterIds: Map<String, Long>, backup: List<BackupKavitaAnnotation>) {
        val identity = identity(mangaId) ?: return
        val stored = chapters.getChapterByMangaId(mangaId).associate { it.url to it.id }
        for (entry in backup) {
            if (entry.key.isBlank() || chapterIds[entry.chapterUrl] == null ||
                chapterIds[entry.chapterUrl] != stored[entry.chapterUrl]
            ) {
                continue
            }
            val ref = runCatching { identity.chapter(entry.chapterUrl) }.getOrNull() ?: continue
            val state = runCatching {
                json.decodeFromString<koharia.kavita.KavitaAnnotationState>(entry.payload)
            }.getOrNull() ?: continue
            val annotation = state.annotation
            if (annotation.chapterId != ref.chapterId || annotation.seriesId != ref.seriesId ||
                annotation.volumeId != ref.volumeId || annotation.libraryId != ref.libraryId
            ) {
                continue
            }
            val existing = repository.annotations(identity.connectionId, identity.account)
            // Backup revisions belong to another timeline; never overwrite a currently stored edit.
            if (existing.any { it.key == entry.key }) continue
            val collision = annotation.id > 0 && existing.any { it.remoteId == annotation.id }
            val restored = state.copy(
                annotation = if (collision) annotation.copy(id = 0) else annotation,
                conflict = entry.pending || collision || state.conflict,
                remoteConflict = state.remoteConflict ?: state.baseline,
            )
            repository.putAnnotation(
                identity.connectionId,
                identity.account,
                koharia.domain.kavita.KavitaAnnotationEntry(
                    entry.key,
                    ref.chapterId,
                    restored.annotation.id.takeIf { it > 0 },
                    json.encodeToString(restored),
                    entry.revision.coerceAtLeast(0) + 1,
                    entry.pending || collision,
                ),
            )
        }
    }
}
