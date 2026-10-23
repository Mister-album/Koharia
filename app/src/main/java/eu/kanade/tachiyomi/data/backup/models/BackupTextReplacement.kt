package eu.kanade.tachiyomi.data.backup.models

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.textreplacement.ReplacementRuleFiles
import koharia.domain.textreplacement.ReplacementScope
import koharia.domain.textreplacement.StoredReplacementScope
import koharia.domain.textreplacement.TextReplacementRepository
import koharia.epub.replacement.TextReplacementService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Serializable
data class BackupTextReplacement(
    val entry: StoredReplacementScope,
    val seriesUrl: String? = null,
    val bookUrl: String? = null,
)

object TextReplacementBackup {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun capture(): String {
        val database = Injekt.get<Database>()
        val mangas = database.mangasQueries.getAllManga().awaitAsList()
        val records = Injekt.get<TextReplacementRepository>().all()
        return json.encodeToString(
            records.map { entry ->
                val manga = mangas.find {
                    it.source == entry.context.connectionId &&
                        TextReplacementService.identity(it.url) == entry.context.seriesKey
                }
                val chapter = manga?.let {
                    database.chaptersQueries.getChaptersByMangaId(it._id, 0).awaitAsList()
                        .find { chapter -> TextReplacementService.identity(chapter.url) == entry.context.bookKey }
                }
                BackupTextReplacement(entry, manga?.url, chapter?.url)
            },
        )
    }

    fun decode(value: String?) = value?.let { json.decodeFromString<List<BackupTextReplacement>>(it) }.orEmpty()

    /** URL matches alone cannot authorize binding rules to a different account. */
    suspend fun restore(entries: List<BackupTextReplacement>): Int {
        val service = TextReplacementService()
        val database = Injekt.get<Database>()
        val existing = service.repository.all().associateBy { it.key }.toMutableMap()
        var conflicts = 0
        val restored = entries.map { backup ->
            val old = backup.entry
            val context = if (old.scope == ReplacementScope.GLOBAL) {
                koharia.domain.textreplacement.TextReplacementContext()
            } else {
                val manga = backup.seriesUrl?.let {
                    database.mangasQueries.getMangaByUrlAndSource(
                        it,
                        old.context.connectionId,
                    ).awaitAsOneOrNull()
                }
                val chapter = if (old.scope == ReplacementScope.BOOK && manga != null) {
                    backup.bookUrl?.let { url ->
                        database.chaptersQueries.getChaptersByMangaId(
                            manga._id,
                            0,
                        ).awaitAsList().find {
                            it.url == url
                        }
                    }
                } else {
                    null
                }
                if (manga != null && (old.scope != ReplacementScope.BOOK || chapter != null)) {
                    try {
                        service.context(manga._id, chapter?._id).takeIf {
                            it.ownerKey == old.context.ownerKey
                        } ?: old.context.copy(connectionId = 0)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        old.context.copy(connectionId = 0)
                    }
                } else {
                    old.context.copy(connectionId = 0)
                }
            }
            val key = context.key(old.scope)
            val current = existing[key]
            val result = old.copy(
                key = key,
                context = context,
                state = if (current == null) {
                    old.state
                } else {
                    conflicts += old.state.rules.count { incoming ->
                        current.state.rules.any {
                            it.id == incoming.id || it.key == incoming.key
                        }
                    }
                    ReplacementRuleFiles.merge(current.state, old.state, emptySet())
                },
            )
            existing[key] = result
            result
        }
        service.repository.save(restored)
        return conflicts
    }
}
