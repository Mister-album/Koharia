package koharia.kavita

import koharia.domain.kavita.KavitaAnnotationEntry
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

@Serializable
data class KavitaAnnotationState(
    val annotation: KavitaAnnotation,
    val baseline: KavitaAnnotation? = null,
    val locatorJson: String? = null,
    val deleted: Boolean = false,
    val conflict: Boolean = false,
    val remoteConflict: KavitaAnnotation? = null,
    val submittedCreate: KavitaAnnotation? = null,
    val anchorStale: Boolean = false,
)

data class KavitaAnnotationRecord(val entry: KavitaAnnotationEntry, val state: KavitaAnnotationState)
enum class KavitaAnnotationResolution { LOCAL, REMOTE, BOTH }

/** Durable edits and immutable remote anchors are kept together, including unresolved conflicts. */
class KavitaAnnotationCoordinator(
    private val connectionId: Long,
    private val account: String,
    private val userId: Long,
    private val repository: KavitaRepository,
    private val catalog: KavitaCatalog,
    private val scope: CoroutineScope,
    private val checkSession: () -> Unit,
    private val allowLocalWrite: () -> Boolean,
) {
    private val api get() = catalog.api
    private val mutex = Mutex()
    private val flushMutex = Mutex()
    private var job: Job? = null
    private val scheduled = java.util.concurrent.atomic.AtomicBoolean()
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private fun decode(entry: KavitaAnnotationEntry) =
        KavitaAnnotationRecord(entry, api.decode<KavitaAnnotationState>(entry.payload))
    private suspend fun find(key: String): KavitaAnnotationRecord? =
        repository.annotations(connectionId, account).firstOrNull { it.key == key }?.let(::decode)

    private suspend fun put(
        entry: KavitaAnnotationEntry,
        state: KavitaAnnotationState,
        pending: Boolean = entry.pending,
    ) {
        checkSession()
        repository.putAnnotation(
            connectionId,
            account,
            entry.copy(
                remoteId = state.annotation.id.takeIf { it > 0 },
                payload = api.json.encodeToString(state),
                pending = pending,
            ),
        )
        changes.tryEmit(Unit)
    }

    suspend fun cached(chapterId: Long): List<KavitaAnnotationRecord> {
        checkSession()
        if (repository.cache(connectionId, account, "annotations/$chapterId", "loaded")?.payload == "blocked") {
            return emptyList()
        }
        return repository.annotations(connectionId, account, chapterId).map(::decode)
            .filter { !it.state.deleted || it.state.conflict }
    }

    suspend fun contentChanged(chapterId: Long) = mutex.withLock {
        checkSession()
        for (record in repository.annotations(connectionId, account, chapterId).map(::decode)) {
            put(
                record.entry.copy(revision = record.entry.revision + 1),
                record.state.copy(anchorStale = true, conflict = record.entry.pending || record.state.conflict),
            )
        }
    }

    suspend fun reanchor(
        key: String,
        revision: Long,
        page: Int,
        start: String,
        end: String,
        text: String,
        locatorJson: String,
    ) = mutex.withLock {
        val current = requireNotNull(find(key))
        requireEditable(current, revision)
        require(current.state.anchorStale && page >= 0 && start.isNotBlank() && text.isNotBlank())
        val annotation = current.state.annotation.copy(
            id = 0,
            pageNumber = page,
            xPath = start,
            endingXPath = end,
            selectedText = text,
            highlightCount = text.length,
        )
        put(
            current.entry.copy(key = UUID.randomUUID().toString(), revision = 1, remoteId = null),
            KavitaAnnotationState(annotation, locatorJson = locatorJson),
            true,
        )
        // Retain the original record for export; the user only requested a new anchored copy.
        put(current.entry.copy(revision = revision + 1), current.state.copy(conflict = false), false)
        retryPending()
    }

    suspend fun load(chapterId: Long, refresh: Boolean = false): List<KavitaAnnotationRecord> {
        checkSession()
        val marker = repository.cache(connectionId, account, "annotations/$chapterId", "loaded")
        if (!refresh && marker?.payload == "blocked") throw KavitaException(KavitaException.Reason.PERMISSION)
        if (refresh || marker == null || marker.stale) {
            val remote: List<KavitaAnnotation> = try {
                api.get("Annotation/all?chapterId=$chapterId")
            } catch (failure: KavitaException) {
                if (failure.reason == KavitaException.Reason.PERMISSION) {
                    repository.putCache(
                        connectionId,
                        account,
                        "annotations/$chapterId",
                        KavitaCacheEntry("loaded", "blocked", System.currentTimeMillis()),
                    )
                    changes.tryEmit(Unit)
                }
                throw failure
            }
            mutex.withLock {
                checkSession()
                val local = repository.annotations(connectionId, account, chapterId).map(::decode)
                for (value in remote) {
                    val submitted = local.filter {
                        it.entry.remoteId == null && it.state.submittedCreate != null &&
                            sameAnnotation(value.copy(id = 0), it.state.submittedCreate)
                    }
                    val previous = local.firstOrNull { it.entry.remoteId == value.id } ?: submitted.singleOrNull()
                    if (previous == null) {
                        if (submitted.isNotEmpty()) {
                            submitted.forEach { put(it.entry, it.state.copy(conflict = true, remoteConflict = value)) }
                            continue
                        }
                        put(
                            KavitaAnnotationEntry("remote-${value.id}", chapterId, value.id, "", 0, false),
                            KavitaAnnotationState(value, value),
                        )
                    } else if (previous.entry.remoteId == null) {
                        val annotation = previous.state.annotation.copy(id = value.id)
                        put(
                            previous.entry,
                            previous.state.copy(
                                annotation = annotation,
                                baseline = value,
                                submittedCreate = null,
                            ),
                            previous.state.deleted || !sameAnnotation(annotation, value),
                        )
                    } else if (previous.state.anchorStale) {
                        if (!sameAnnotation(previous.state.annotation, value)) {
                            put(previous.entry, previous.state.copy(conflict = true, remoteConflict = value))
                        }
                    } else if (!previous.entry.pending) {
                        put(previous.entry, previous.state.copy(annotation = value, baseline = value, deleted = false))
                    } else if (previous.state.conflict || !sameAnnotation(previous.state.baseline, value)) {
                        // A successful update whose response was lost may already equal our local edit.
                        if (sameAnnotation(previous.state.annotation, value) && !previous.state.deleted &&
                            !previous.state.anchorStale
                        ) {
                            put(
                                previous.entry,
                                previous.state.copy(baseline = value, conflict = false, remoteConflict = null),
                                false,
                            )
                        } else {
                            put(previous.entry, previous.state.copy(conflict = true, remoteConflict = value))
                        }
                    }
                }
                for (previous in local.filter {
                    it.entry.remoteId != null &&
                        remote.none { value -> value.id == it.entry.remoteId }
                }) {
                    if ((previous.entry.pending || previous.state.anchorStale) && !previous.state.deleted) {
                        put(previous.entry, previous.state.copy(conflict = true, remoteConflict = null))
                    } else {
                        put(previous.entry, previous.state.copy(deleted = true, baseline = null), false)
                    }
                }
                repository.putCache(
                    connectionId,
                    account,
                    "annotations/$chapterId",
                    KavitaCacheEntry("loaded", "[]", System.currentTimeMillis()),
                )
            }
        }
        retryPending()
        return cached(chapterId)
    }

    suspend fun create(annotation: KavitaAnnotation, locatorJson: String?): String {
        checkSession()
        require(allowLocalWrite() && annotation.ownerUserId == userId && userId > 0)
        require(annotation.id == 0L && annotation.selectedText.isNotBlank() && annotation.xPath.isNotBlank())
        require(annotation.pageNumber >= 0 && annotation.selectedSlotIndex in 0..3)
        val key = UUID.randomUUID().toString()
        mutex.withLock {
            put(
                KavitaAnnotationEntry(key, annotation.chapterId, null, "", 1, true),
                KavitaAnnotationState(
                    annotation.copy(highlightCount = annotation.selectedText.length),
                    locatorJson = locatorJson,
                ),
            )
        }
        retryPending()
        return key
    }

    suspend fun edit(key: String, revision: Long, comment: String, slot: Int, spoiler: Boolean) {
        require(slot in 0..3)
        mutex.withLock {
            val current = requireNotNull(find(key))
            requireEditable(current, revision)
            if (current.state.anchorStale) throw KavitaException(KavitaException.Reason.CONFLICT)
            put(
                current.entry.copy(revision = revision + 1),
                current.state.copy(
                    annotation =
                    current.state.annotation.withPlainComment(
                        comment,
                    ).copy(selectedSlotIndex = slot, containsSpoiler = spoiler),
                ),
                true,
            )
        }
        retryPending()
    }

    suspend fun delete(key: String, revision: Long) {
        mutex.withLock {
            val current = requireNotNull(find(key))
            requireEditable(current, revision)
            val needsRemote = current.entry.remoteId != null || current.state.submittedCreate != null
            put(
                current.entry.copy(revision = revision + 1),
                current.state.copy(deleted = true, conflict = false),
                needsRemote,
            )
        }
        retryPending()
    }

    private fun requireEditable(record: KavitaAnnotationRecord, revision: Long) {
        checkSession()
        if (!allowLocalWrite() || record.state.annotation.ownerUserId != userId) {
            throw KavitaException(KavitaException.Reason.PERMISSION)
        }
        if (record.entry.revision != revision) throw KavitaException(KavitaException.Reason.CONFLICT)
    }

    suspend fun resolve(key: String, revision: Long, choice: KavitaAnnotationResolution) {
        mutex.withLock {
            val current = requireNotNull(find(key))
            requireEditable(current, revision)
            require(current.state.conflict)
            if (current.state.anchorStale && choice != KavitaAnnotationResolution.REMOTE) {
                throw KavitaException(KavitaException.Reason.CONFLICT)
            }
            val remote = current.state.remoteConflict
            val boundElsewhere = remote != null && repository.annotations(connectionId, account).any {
                it.remoteId == remote.id && it.key != key
            }
            if (boundElsewhere && choice == KavitaAnnotationResolution.LOCAL) {
                throw KavitaException(KavitaException.Reason.CONFLICT)
            }
            if (choice == KavitaAnnotationResolution.BOTH && !current.state.deleted) {
                val duplicate = current.state.annotation.copy(id = 0)
                put(
                    current.entry.copy(key = UUID.randomUUID().toString(), revision = 1, remoteId = null),
                    KavitaAnnotationState(duplicate, locatorJson = current.state.locatorJson),
                    true,
                )
            }
            val next = if (boundElsewhere) {
                current.state.copy(
                    annotation = current.state.annotation.copy(id = 0),
                    baseline = null,
                    deleted = true,
                    conflict = false,
                    submittedCreate = null,
                    remoteConflict = null,
                )
            } else if (choice == KavitaAnnotationResolution.LOCAL) {
                current.state.copy(
                    annotation = current.state.annotation.copy(id = remote?.id ?: 0),
                    baseline = remote,
                    submittedCreate = null,
                    conflict = false,
                    remoteConflict = null,
                )
            } else {
                current.state.copy(
                    annotation = remote ?: current.state.annotation,
                    baseline = remote,
                    submittedCreate = null,
                    deleted = remote == null,
                    conflict = false,
                    remoteConflict = null,
                )
            }
            put(current.entry.copy(revision = revision + 1), next, choice == KavitaAnnotationResolution.LOCAL)
        }
        retryPending()
    }

    suspend fun flush() = flushMutex.withLock {
        val pending = repository.annotations(connectionId, account).filter { it.pending }.map(::decode)
        val uploadable = pending.filter { !it.state.conflict && (!it.state.anchorStale || it.state.deleted) }
        if (uploadable.isEmpty()) return@withLock
        checkSession()
        if (!api.capabilities(api.getAccount(true)).writable) throw KavitaException(KavitaException.Reason.PERMISSION)
        for (record in uploadable) {
            checkSession()
            val state = record.state
            if (state.submittedCreate != null) {
                val remote: List<KavitaAnnotation> = api.get("Annotation/all?chapterId=${record.entry.chapterId}")
                val matches = remote.filter { sameAnnotation(it.copy(id = 0), state.submittedCreate) }
                if (matches.size == 1) {
                    acknowledge(record, matches.single())
                } else {
                    conflict(record, null)
                }
                continue
            }
            if (record.entry.remoteId == null) {
                if (state.deleted) {
                    acknowledge(record, null)
                    continue
                }
                val send = mutex.withLock {
                    val current = find(record.entry.key)
                    if (current?.entry?.revision != record.entry.revision) {
                        false
                    } else {
                        put(record.entry, state.copy(submittedCreate = state.annotation))
                        true
                    }
                }
                if (!send) continue
                // Persist uncertainty before the non-idempotent request, including process interruption.
                val remote: KavitaAnnotation = api.post(
                    "Annotation/create",
                    api.json.parseToJsonElement(api.json.encodeToString(state.annotation)),
                )
                acknowledge(record, remote)
            } else {
                val remote = try {
                    api.get<KavitaAnnotation>("Annotation/${record.entry.remoteId}")
                } catch (
                    failure: KavitaException,
                ) {
                    if (failure.status == 404) null else throw failure
                }
                if (state.deleted && remote == null) {
                    acknowledge(record, null)
                    continue
                }
                if (!state.deleted &&
                    sameAnnotation(state.annotation, remote)
                ) {
                    acknowledge(record, remote)
                    continue
                }
                if (!sameAnnotation(state.baseline, remote)) {
                    conflict(record, remote)
                    continue
                }
                if (state.deleted) {
                    api.mutate("Annotation?annotationId=${record.entry.remoteId}", method = "DELETE")
                    acknowledge(record, null)
                } else {
                    val updated: KavitaAnnotation = api.post(
                        "Annotation/update",
                        api.json.parseToJsonElement(api.json.encodeToString(state.annotation)),
                    )
                    acknowledge(record, updated)
                }
            }
        }
    }

    private suspend fun conflict(record: KavitaAnnotationRecord, remote: KavitaAnnotation?) = mutex.withLock {
        val current = find(record.entry.key) ?: return@withLock
        put(current.entry, current.state.copy(conflict = true, remoteConflict = remote))
    }

    private suspend fun acknowledge(record: KavitaAnnotationRecord, remote: KavitaAnnotation?) = mutex.withLock {
        val current = find(record.entry.key) ?: return@withLock
        if (remote != null && repository.annotations(connectionId, account).any {
                it.remoteId == remote.id && it.key != current.entry.key
            }
        ) {
            put(current.entry, current.state.copy(conflict = true, remoteConflict = remote))
            return@withLock
        }
        val sameRevision = record.entry.revision == current.entry.revision
        val state = current.state.copy(
            annotation = if (sameRevision && remote != null) {
                remote
            } else {
                current.state.annotation.copy(id = remote?.id ?: current.state.annotation.id)
            },
            baseline = remote,
            submittedCreate = null,
            conflict = if (sameRevision) false else current.state.conflict,
            remoteConflict = if (sameRevision) null else current.state.remoteConflict,
        )
        put(current.entry, state, !sameRevision)
        if (!sameRevision) scheduled.set(true)
    }

    suspend fun like(id: Long, enabled: Boolean) {
        checkSession()
        if (!allowLocalWrite()) throw KavitaException(KavitaException.Reason.PERMISSION)
        if (!api.capabilities(api.getAccount(true)).writable) throw KavitaException(KavitaException.Reason.PERMISSION)
        api.mutate("Annotation/" + if (enabled) "like" else "unlike", JsonArray(listOf(JsonPrimitive(id))))
        catalog.invalidateGroups("annotations/", "annotation-browser/")
        changes.tryEmit(Unit)
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
                            if (failure is KavitaException && failure.reason in setOf(
                                    KavitaException.Reason.PERMISSION,
                                    KavitaException.Reason.AUTHENTICATION,
                                    KavitaException.Reason.ACCOUNT_CHANGED,
                                    KavitaException.Reason.UNSUPPORTED,
                                )
                            ) {
                                return@launch
                            }
                            if (attempt == 3) return@launch
                            delay(2000L shl attempt)
                        }
                    }
                }
            } finally {
                synchronized(this@KavitaAnnotationCoordinator) {
                    job = null
                    if (scheduled.get() && scope.isActive) retryPending()
                }
            }
        }
        job?.start()
    }
}

internal fun sameAnnotation(a: KavitaAnnotation?, b: KavitaAnnotation?): Boolean {
    if (a == null || b == null) return a == b
    return a.copy(
        lastModifiedUtc = "",
        createdUtc = "",
        likes = emptyList(),
        ownerUsername = "",
        commentPlainText = "",
        seriesName = "",
        chapterTitle = "",
    ) ==
        b.copy(
            lastModifiedUtc = "",
            createdUtc = "",
            likes = emptyList(),
            ownerUsername = "",
            commentPlainText = "",
            seriesName = "",
            chapterTitle = "",
        )
}

internal fun KavitaAnnotation.withPlainComment(text: String): KavitaAnnotation {
    if (text == commentPlainText) return this
    val delta = kotlinx.serialization.json.buildJsonObject {
        put(
            "ops",
            JsonArray(
                listOf(
                    kotlinx.serialization.json.buildJsonObject {
                        put("insert", JsonPrimitive(text.trimEnd('\n') + "\n"))
                    },
                ),
            ),
        )
    }
    val html = text.lines().joinToString("") {
        "<p>" + org.jsoup.nodes.Entities.escape(it).ifEmpty { "<br>" } + "</p>"
    }
    return copy(comment = delta.toString(), commentHtml = html, commentPlainText = text)
}
