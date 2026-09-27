package koharia.kavita

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import koharia.connection.ConnectionReadingQueueChapter
import koharia.connection.ConnectionReadingQueuePosition
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import koharia.source.kavita.KavitaSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tachiyomi.domain.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class KavitaReadingQueue(private val source: KavitaSource, private val repository: KavitaRepository) {
    private val session = source.session()

    suspend fun create(title: String, items: List<KavitaListItem>): String {
        session.checkActive()
        val snapshot = KavitaQueueSnapshot.create(title, items)
        require(snapshot.items.isNotEmpty())
        val key = UUID.randomUUID().toString()
        repository.putCache(
            source.id,
            session.accountKey,
            "reading-queue",
            KavitaCacheEntry(key, session.api.json.encodeToString(snapshot), System.currentTimeMillis()),
        )
        return key
    }

    private suspend fun snapshot(context: String): KavitaQueueSnapshot {
        session.checkActive()
        val cached = repository.cache(source.id, session.accountKey, "reading-queue", context)
            ?: throw KavitaException(KavitaException.Reason.ACCOUNT_CHANGED)
        return session.api.decode(cached.payload)
    }

    suspend fun position(context: String, chapterUrl: String): ConnectionReadingQueuePosition {
        val list = snapshot(context)
        return list.position(session.identity, chapterUrl)
    }

    suspend fun resolve(context: String, chapterUrl: String): ConnectionReadingQueueChapter {
        val ref = session.identity.chapter(chapterUrl)
        require(snapshot(context).items.any { it.chapterId == ref.chapterId && it.seriesId == ref.seriesId })
        session.catalog.requireLibrary(ref.libraryId)
        val manga = source.materialize(source.toManga(session.catalog.series(ref.seriesId)))
        val chapters: ChapterRepository = Injekt.get()
        var chapter = chapters.getChapterByMangaId(manga.id).firstOrNull {
            session.identity.chapter(it.url).chapterId == ref.chapterId
        }
        if (chapter == null) {
            Injekt.get<SyncChaptersWithSource>().await(source.getChapterList(manga.toSManga()), manga, source)
            chapter =
                chapters.getChapterByMangaId(manga.id).first {
                    session.identity.chapter(it.url).chapterId ==
                        ref.chapterId
                }
        }
        session.checkActive()
        return ConnectionReadingQueueChapter(manga.id, chapter.id, chapter.url)
    }
}

@Serializable
internal data class KavitaQueueSnapshot(val title: String, val items: List<KavitaListItem>) {
    fun position(identity: KavitaIdentity, chapterUrl: String): ConnectionReadingQueuePosition {
        val ref = identity.chapter(chapterUrl)
        val index = items.indexOfFirst { it.chapterId == ref.chapterId && it.seriesId == ref.seriesId }
        require(index >= 0)
        fun url(index: Int): String? = items.getOrNull(index)?.let {
            identity.chapter(KavitaChapterRef(it.libraryId, it.seriesId, it.volumeId, it.chapterId, it.seriesFormat))
        }
        return ConnectionReadingQueuePosition(title, index, items.size, url(index - 1), url(index + 1))
    }

    companion object {
        fun create(title: String, items: List<KavitaListItem>): KavitaQueueSnapshot {
            require(
                items.isNotEmpty() && items.all {
                    it.chapterId > 0 && it.volumeId > 0 && it.seriesId > 0 && it.libraryId > 0
                },
            )
            return KavitaQueueSnapshot(title, items.sortedBy { it.order }.distinctBy { it.chapterId })
        }
    }
}
