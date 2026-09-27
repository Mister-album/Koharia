package koharia.kavita

import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

@Serializable
enum class KavitaBookmarkKind { IMAGE, TOC }

@Serializable
data class KavitaBookmarkState(
    val ref: KavitaChapterRef,
    val kind: KavitaBookmarkKind,
    val page: Int,
    val imageOffset: Int = 0,
    val title: String = "",
    val anchor: String = "",
    val selectedText: String = "",
    val desired: Boolean = true,
    val needsConfirmation: Boolean = false,
) {
    val key get() = "bookmark/${kind.name}/${ref.chapterId}/$page/" +
        if (kind == KavitaBookmarkKind.IMAGE) imageOffset else title.encodeUtf8().sha256().hex()
    val path get() = if (kind == KavitaBookmarkKind.IMAGE) {
        "Reader/chapter-bookmarks?chapterId=${ref.chapterId}"
    } else {
        "Reader/ptoc?chapterId=${ref.chapterId}"
    }

    fun matches(value: JsonObject): Boolean = if (kind == KavitaBookmarkKind.IMAGE) {
        value.longValue("page") == page.toLong() && value.longValue("imageOffset") == imageOffset.toLong()
    } else {
        value.longValue("pageNumber") == page.toLong() && value.textValue("title") == title
    }

    fun body(): JsonObject = buildJsonObject {
        put("chapterId", ref.chapterId)
        put("seriesId", ref.seriesId)
        put("volumeId", ref.volumeId)
        put("libraryId", ref.libraryId)
        if (kind == KavitaBookmarkKind.IMAGE) {
            put("page", page)
            put("imageOffset", imageOffset)
            put("xPath", anchor)
        } else {
            put("pageNumber", page)
            put("title", title)
            put("bookScrollId", anchor)
            put("selectedText", selectedText)
        }
    }
}

/** Bookmark identities are unique on the server; every retry first checks that identity. */
class KavitaBookmarks(
    private val connectionId: Long,
    private val account: String,
    private val repository: KavitaRepository,
    private val catalog: KavitaCatalog,
    private val scope: CoroutineScope,
    private val checkSession: () -> Unit,
    private val allowLocalWrite: () -> Boolean,
) {
    private val api get() = catalog.api
    private val stateMutex = Mutex()
    private val flushMutex = Mutex()
    private var job: Job? = null
    private val scheduled = java.util.concurrent.atomic.AtomicBoolean()
    val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    suspend fun entries(ref: KavitaChapterRef, kind: KavitaBookmarkKind, refresh: Boolean = false): List<JsonObject> {
        checkSession()
        val template = KavitaBookmarkState(ref, kind, 0)
        val remote = try {
            catalog.resource(template.path, refresh).jsonArray.map { it.jsonObject }
        } catch (failure: java.io.IOException) {
            if (failure is KavitaException && failure.reason != KavitaException.Reason.NETWORK) throw failure
            val pending = repository.operations(connectionId, account).any {
                it.pending && it.key.startsWith("bookmark/${kind.name}/${ref.chapterId}/")
            }
            if (!pending) throw failure
            emptyList()
        }
        val result = remote.toMutableList()
        for (operation in repository.operations(connectionId, account).filter {
            it.pending && it.key.startsWith("bookmark/${kind.name}/${ref.chapterId}/")
        }) {
            val state = api.decode<KavitaBookmarkState>(operation.payload)
            result.removeAll(state::matches)
            result.add(
                JsonObject(
                    state.body() + mapOf(
                        "pending" to JsonPrimitive(true),
                        "deleted" to JsonPrimitive(!state.desired),
                        "confirmation" to JsonPrimitive(state.needsConfirmation),
                        "revision" to JsonPrimitive(operation.revision),
                    ),
                ),
            )
        }
        return result
    }

    suspend fun set(value: KavitaBookmarkState) {
        checkSession()
        if (!allowLocalWrite()) throw KavitaException(KavitaException.Reason.PERMISSION)
        require(value.page >= 0 && value.imageOffset >= 0)
        require(value.kind != KavitaBookmarkKind.TOC || value.title.isNotBlank())
        stateMutex.withLock {
            val old = repository.operations(connectionId, account).firstOrNull { it.key == value.key }
            repository.putOperation(
                connectionId,
                account,
                KavitaOperation(value.key, api.json.encodeToString(value), (old?.revision ?: 0) + 1, true),
            )
            changes.tryEmit(Unit)
        }
        retryPending()
    }

    suspend fun discard(key: String, revision: Long) = flushMutex.withLock {
        stateMutex.withLock state@{
            checkSession()
            require(key.startsWith("bookmark/"))
            val current = repository.operations(connectionId, account).firstOrNull { it.key == key }
                ?: return@state
            if (current.revision != revision) throw KavitaException(KavitaException.Reason.CONFLICT)
            repository.acknowledge(connectionId, account, key, revision)
            changes.tryEmit(Unit)
        }
    }

    suspend fun flush() = flushMutex.withLock {
        for (operation in repository.operations(connectionId, account).filter {
            it.pending && it.key.startsWith("bookmark/")
        }) {
            checkSession()
            val state = api.decode<KavitaBookmarkState>(operation.payload)
            if (state.needsConfirmation) continue
            val capabilities = api.capabilities(api.getAccount(true))
            if (!capabilities.writable || (
                    state.kind == KavitaBookmarkKind.IMAGE &&
                        capabilities.roles.none { it.equals("Admin", true) || it.equals("Bookmark", true) }
                    )
            ) {
                throw KavitaException(KavitaException.Reason.PERMISSION)
            }
            val current = catalog.resource(state.path, true).jsonArray.map { it.jsonObject }
            val existing = current.firstOrNull(state::matches)
            if (existing != null && state.kind == KavitaBookmarkKind.TOC &&
                existing.textValue("bookScrollId") != state.anchor
            ) {
                throw KavitaException(KavitaException.Reason.CONFLICT)
            }
            if ((existing != null) != state.desired) {
                if (state.kind == KavitaBookmarkKind.TOC && !state.desired) {
                    val path = api.url(
                        "Reader/ptoc",
                        "chapterId" to state.ref.chapterId,
                        "pageNum" to state.page,
                        "title" to state.title,
                    )
                    api.mutate("Reader/ptoc?" + path.encodedQuery, method = "DELETE")
                } else {
                    api.mutate(
                        if (state.kind == KavitaBookmarkKind.TOC) {
                            "Reader/create-ptoc"
                        } else if (state.desired) {
                            "Reader/bookmark"
                        } else {
                            "Reader/unbookmark"
                        },
                        state.body(),
                    )
                }
            }
            // Persist the confirmed server list before acknowledging an operation, including empty lists.
            catalog.resource(state.path, true)
            checkSession()
            repository.acknowledge(connectionId, account, operation.key, operation.revision)
            changes.tryEmit(Unit)
        }
    }

    @Synchronized fun retryPending() {
        if (!scope.isActive) return
        scheduled.set(true)
        if (job?.isActive == true) return
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                while (scheduled.getAndSet(false)) {
                    delay(500)
                    for (attempt in 0..3) {
                        try {
                            flush()
                            break
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            if (failure is KavitaException && failure.reason != KavitaException.Reason.NETWORK) break
                            if (attempt == 3) break
                            delay(2000L shl attempt)
                        }
                    }
                }
            } finally {
                synchronized(this@KavitaBookmarks) {
                    job = null
                    if (scheduled.get() && scope.isActive) retryPending()
                }
            }
        }
        job?.start()
    }
}
