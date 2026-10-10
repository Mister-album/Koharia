package koharia.komga.domain.repository

import android.app.Application
import android.util.AtomicFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionReadingQueueChapter
import koharia.connection.ConnectionReadingQueuePosition
import koharia.connection.ConnectionShelfStateStore
import koharia.domain.manga.model.toDomainManga
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.KomgaOrganizationApi
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaOrganizationQuery
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.SeriesDto
import koharia.komga.api.dto.SeriesMetadataDto
import koharia.komga.api.dto.toSManga
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class KomgaOrganizationFailure {
    ACCOUNT_CHANGED,
    PERMISSION,
    CONFLICT,
    FILTERED,
    EMPTY,
    UNSUPPORTED,
    UNKNOWN_WRITE,
}

class KomgaOrganizationException(val reason: KomgaOrganizationFailure) : Exception(reason.name)

/** A captured connection session prevents late reads or writes from following an edited account. */
class KomgaOrganizationRepository(val source: KomgaSource, val api: KomgaOrganizationApi) {
    val namespace = source.shelfCacheNamespace()
    private val app: Application = Injekt.get()
    private val json: Json = Injekt.get()
    private val mutations = Mutex()

    fun checkActive() {
        if (namespace != source.shelfCacheNamespace()) {
            throw KomgaOrganizationException(KomgaOrganizationFailure.ACCOUNT_CHANGED)
        }
    }

    fun cachedRoles(): List<String>? =
        ConnectionShelfStateStore(app, namespace).read("organizationRoles")?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        }

    suspend fun account() =
        api.account().also {
            checkActive()
            ConnectionShelfStateStore(app, namespace)
                .write("organizationRoles", json.encodeToString(it.roles))
        }

    suspend fun authorize(role: String = "ADMIN") {
        checkActive()
        if (!account().roles.any { it.removePrefix("ROLE_") == role }) {
            throw KomgaOrganizationException(KomgaOrganizationFailure.PERMISSION)
        }
    }

    suspend fun save(
        kind: KomgaOrganizationKind,
        baseline: KomgaOrganization?,
        name: String,
        summary: String,
        ordered: Boolean,
        members: List<String>,
    ): KomgaOrganization =
        mutations.withLock {
            authorize()
            require(name.isNotBlank())
            if (members.isEmpty() && baseline?.filtered != true) {
                throw KomgaOrganizationException(KomgaOrganizationFailure.EMPTY)
            }
            if (baseline != null) {
                val fresh = api.detail(kind, baseline.id, strict = true)
                checkActive()
                if (fresh.lastModifiedDate != baseline.lastModifiedDate) {
                    throw KomgaOrganizationException(KomgaOrganizationFailure.CONFLICT)
                }
                if (
                    fresh.filtered &&
                    (members != baseline.members(kind) || ordered != baseline.ordered)
                ) {
                    throw KomgaOrganizationException(KomgaOrganizationFailure.FILTERED)
                }
                try {
                    api.update(
                        kind,
                        baseline.id,
                        api.payload(
                            kind,
                            name,
                            summary,
                            ordered.takeUnless { fresh.filtered },
                            members.takeUnless { fresh.filtered },
                        ),
                    )
                } catch (error: java.io.IOException) {
                    throw KomgaOrganizationException(KomgaOrganizationFailure.UNKNOWN_WRITE)
                }
                checkActive()
                source.organizationChanged()
                api.detail(kind, baseline.id, refresh = true).also { checkActive() }
            } else {
                try {
                    api.create(kind, api.payload(kind, name, summary, ordered, members)).also {
                        checkActive()
                        source.organizationChanged()
                    }
                } catch (error: java.io.IOException) {
                    throw KomgaOrganizationException(KomgaOrganizationFailure.UNKNOWN_WRITE)
                }
            }
        }

    suspend fun add(kind: KomgaOrganizationKind, id: String, members: List<String>) {
        val current = api.detail(kind, id, strict = true)
        if (current.filtered) throw KomgaOrganizationException(KomgaOrganizationFailure.FILTERED)
        save(
            kind,
            current,
            current.name,
            current.summary,
            current.ordered,
            (current.members(kind) + members).distinct(),
        )
    }

    suspend fun delete(
        kind: KomgaOrganizationKind,
        ids: List<String>,
    ): List<Pair<String, Throwable>> {
        authorize()
        val failures = mutableListOf<Pair<String, Throwable>>()
        for (id in ids) {
            checkActive()
            try {
                api.delete(kind, id)
                checkActive()
                source.organizationChanged()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += id to error
            }
        }
        return failures
    }

    suspend fun materialize(series: SeriesDto): Manga {
        checkActive()
        return Injekt.get<NetworkToLocalManga>()(
            series.toSManga(source.baseUrl).toDomainManga(source.id),
        )
            .also { checkActive() }
    }

    suspend fun resolveBook(book: BookDto): ConnectionReadingQueueChapter {
        checkActive()
        require(book.seriesId.isNotBlank())
        registerDownloadAliases(listOf(book))
        val local = Injekt.get<MangaRepository>().getMangaBySourceId(source.id)
        val existing =
            local.firstOrNull {
                it.url.substringBefore('?').endsWith("/api/v1/series/${book.seriesId}")
            }
        val manga =
            existing
                ?: materialize(
                    try {
                        api.series(book.seriesId)
                    } catch (_: java.io.IOException) {
                        SeriesDto(
                            book.seriesId,
                            book.libraryId,
                            book.seriesTitle,
                            fileLastModified = book.fileLastModified,
                            metadata = SeriesMetadataDto(title = book.seriesTitle),
                        )
                    },
                )
        val chapters: ChapterRepository = Injekt.get()
        var chapter =
            chapters.getChapterByMangaId(manga.id).firstOrNull {
                it.url.substringBefore('?').substringAfterLast('/') == book.id
            }
        if (chapter == null) {
            checkActive()
            try {
                val remoteChapters =
                    api.seriesBooks(book.seriesId).map(source::organizationBookChapter)
                checkActive()
                Injekt.get<SyncChaptersWithSource>().await(remoteChapters, manga, source)
            } catch (_: java.io.IOException) {
                val mapped = source.organizationBookChapter(book)
                checkActive()
                chapters.addAll(
                    listOf(
                        Chapter.create()
                            .copy(
                                mangaId = manga.id,
                                url = mapped.url,
                                name = mapped.name,
                                chapterNumber = mapped.chapter_number.toDouble(),
                                dateUpload = mapped.date_upload,
                                scanlator = mapped.scanlator,
                                memo = mapped.memo,
                                read = book.readProgress?.completed == true,
                            ),
                    ),
                )
            }
            chapter =
                chapters.getChapterByMangaId(manga.id).first {
                    it.url.substringBefore('?').substringAfterLast('/') == book.id
                }
        }
        checkActive()
        return ConnectionReadingQueueChapter(manga.id, chapter.id, chapter.url)
    }

    suspend fun registerDownloadAliases(books: List<BookDto>) {
        val mangas =
            Injekt.get<MangaRepository>().getMangaBySourceId(source.id).associateBy { it.id }
        val chapters: ChapterRepository = Injekt.get()
        val localChapters =
            chapters.getChaptersByMangaIds(mangas.keys).groupBy {
                it.url.substringBefore('?').substringAfterLast('/')
            }
        val aliases =
            books.associate { book ->
                book.id to
                    localChapters[book.id].orEmpty().mapNotNull { chapter ->
                        val manga = mangas[chapter.mangaId] ?: return@mapNotNull null
                        koharia.connection.ConnectionDownloadAlias(
                            manga.title,
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                        )
                    }
            }
        checkActive()
        source.registerOrganizationAliases(namespace, aliases)
    }

    fun observeBooks(
        books: List<BookDto>,
    ): Flow<Map<String, koharia.connection.ConnectionShelfEntryState>> {
        if (books.isEmpty()) return flowOf(emptyMap())
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val downloads: DownloadManager = Injekt.get()
        val seriesIds = books.map { it.seriesId }.toSet()
        val bookIds = books.map { it.id }.toSet()
        val store = ConnectionShelfStateStore(app, namespace)
        val progress = KomgaOrganizationBookProgress(store::read, store::write)
        return mangas.getMangaBySourceIdAsFlow(source.id).flatMapLatest { local ->
            val relevant =
                local.filter { manga ->
                    manga.url.contains("/api/v1/readlists/") ||
                        (
                            manga.url.contains("/api/v1/series/") &&
                                manga.url.substringAfterLast('/') in seriesIds
                            ) ||
                        (
                            manga.url.contains("/api/v1/books/") &&
                                manga.url.substringAfterLast('/') in bookIds
                            )
                }
            if (relevant.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val observations =
                    relevant.map { manga ->
                        chapters.getChapterByMangaIdAsFlow(manga.id).let { units ->
                            combine(units, downloads.cacheChanges.onStart { emit(Unit) }) { list, _ ->
                                manga to list
                            }
                        }
                    }
                combine(observations) { entries ->
                    if (namespace != source.shelfCacheNamespace()) return@combine emptyMap()
                    books.associate { book ->
                        val matches =
                            entries.flatMap {
                                    (
                                        manga,
                                        units,
                                    ),
                                ->
                                units
                                    .filter {
                                        it.url.substringBefore('?').substringAfterLast('/') ==
                                            book.id
                                    }
                                    .map { manga to it }
                            }
                        val downloaded =
                            matches.any {
                                    (
                                        manga,
                                        chapter,
                                    ),
                                ->
                                downloads.isChapterDownloaded(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    manga.title,
                                    source.id,
                                )
                            }
                        val read =
                            progress.observe(
                                book.id,
                                book.readProgress,
                                matches.associate { it.second.id to it.second.read },
                            )
                        book.id to
                            koharia.connection.ConnectionShelfEntryState(
                                if (downloaded) {
                                    1
                                } else {
                                    0
                                },
                                1,
                                if (read) {
                                    1
                                } else {
                                    0
                                },
                            )
                    }
                }
            }
        }
    }

    suspend fun download(books: List<BookDto>) {
        authorize("FILE_DOWNLOAD")
        for (book in books.distinctBy { it.id }) {
            val resolved = resolveBook(book)
            checkActive()
            val manga = Injekt.get<MangaRepository>().getMangaById(resolved.mangaId)
            val chapter =
                Injekt.get<ChapterRepository>().getChapterById(resolved.chapterId) ?: continue
            Injekt.get<DownloadManager>().downloadChapters(manga, listOf(chapter))
        }
    }

    suspend fun markRead(books: List<BookDto>, read: Boolean) {
        for (book in books.distinctBy { it.id }) {
            checkActive()
            source.setChapterReadStatus("${source.baseUrl}/api/v1/books/${book.id}", read)
            checkActive()
            val chapters: ChapterRepository = Injekt.get()
            val aliases =
                chapters
                    .getChaptersByMangaIds(
                        Injekt.get<MangaRepository>().getMangaBySourceId(source.id).map { it.id },
                    )
                    .filter { it.url.substringBefore('?').substringAfterLast('/') == book.id }
            chapters.updateAll(
                aliases.map {
                    tachiyomi.domain.chapter.model.ChapterUpdate(
                        id = it.id,
                        read = read,
                        lastPageRead =
                        if (read) {
                            it.lastPageRead
                        } else {
                            0
                        },
                    )
                },
            )
        }
        koharia.komga.api.KomgaOrganizationUpdates.notify(
            koharia.komga.api.KomgaOrganizationUpdate(source.id, progressOnly = true),
        )
    }

    suspend fun createQueue(list: KomgaOrganization, books: List<BookDto>): String {
        checkActive()
        val key = UUID.randomUUID().toString()
        val snapshot = KomgaReadingListSnapshot(list.id, list.name, books.distinctBy { it.id })
        val file = AtomicFile(queueFile(key))
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(snapshot).toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return key
    }

    private fun queueFile(key: String): File {
        require(runCatching { UUID.fromString(key) }.isSuccess)
        return File(app.filesDir, "komga-organization/${source.id}/$namespace/$key.json").also {
            it.parentFile!!.mkdirs()
        }
    }

    private fun queue(key: String): KomgaReadingListSnapshot {
        checkActive()
        return json.decodeFromString(AtomicFile(queueFile(key)).readFully().decodeToString())
    }

    suspend fun queuePosition(key: String, chapterUrl: String): ConnectionReadingQueuePosition {
        val saved = queue(key)
        val bookId = chapterUrl.substringBefore('?').substringAfterLast('/')
        val index = saved.books.indexOfFirst { it.id == bookId }
        require(index >= 0)
        fun url(id: String?) = id?.let { "${source.baseUrl}/api/v1/books/$it" }
        suspend fun sibling(forward: Boolean): String? =
            try {
                api.sibling(saved.id, bookId, forward).id
            } catch (error: eu.kanade.tachiyomi.network.HttpException) {
                if (error.code == 404) null else throw error
            } catch (_: java.io.IOException) {
                saved.books.getOrNull(index + if (forward) 1 else -1)?.id
            }
        val result =
            ConnectionReadingQueuePosition(
                saved.name,
                index,
                saved.books.size,
                url(sibling(false)),
                url(sibling(true)),
            )
        checkActive()
        return result
    }

    suspend fun queueChapter(key: String, chapterUrl: String): ConnectionReadingQueueChapter {
        val saved = queue(key)
        val id = chapterUrl.substringBefore('?').substringAfterLast('/')
        val book =
            saved.books.firstOrNull { it.id == id }
                ?: run {
                    val fresh = api.books(saved.id, KomgaOrganizationQuery(unpaged = true)).content
                    val file = AtomicFile(queueFile(key))
                    val stream = file.startWrite()
                    try {
                        stream.write(json.encodeToString(saved.copy(books = fresh)).toByteArray())
                        file.finishWrite(stream)
                    } catch (error: Exception) {
                        file.failWrite(stream)
                        throw error
                    }
                    fresh.first { it.id == id }
                }
        rememberReading(saved.id, id)
        return resolveBook(book)
    }

    fun rememberReading(listId: String, bookId: String) {
        checkActive()
        ConnectionShelfStateStore(app, namespace).write("readlist-last-$listId", bookId)
    }

    fun lastReading(listId: String) =
        ConnectionShelfStateStore(app, namespace).read("readlist-last-$listId")

    companion object {
        fun presentationId(id: String): Long =
            MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray())
                .take(7)
                .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
                .let { -it - 1 }
    }
}

@Serializable
private data class KomgaReadingListSnapshot(
    val id: String,
    val name: String,
    val books: List<BookDto>,
    val version: Int = 1,
)
