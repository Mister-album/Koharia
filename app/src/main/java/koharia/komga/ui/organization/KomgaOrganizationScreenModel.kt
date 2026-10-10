package koharia.komga.ui.organization

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.domain.manga.model.toDomainManga
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaOrganizationQuery
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.SeriesDto
import koharia.komga.api.dto.toSManga
import koharia.komga.domain.repository.KomgaOrganizationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.Manga

data class KomgaOrganizationState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: Throwable? = null,
    val organization: KomgaOrganization? = null,
    val objects: List<KomgaOrganization> = emptyList(),
    val series: List<SeriesDto> = emptyList(),
    val books: List<BookDto> = emptyList(),
    val libraries: List<LibraryDto> = emptyList(),
    val query: KomgaOrganizationQuery = KomgaOrganizationQuery(),
    val toolbarQuery: String? = null,
    val totalPages: Int = 0,
    val selected: Set<String> = emptySet(),
    val admin: Boolean = false,
    val downloadAllowed: Boolean = false,
)

class KomgaOrganizationScreenModel(
    val repository: KomgaOrganizationRepository,
    val kind: KomgaOrganizationKind,
    val organizationId: String?,
) : StateScreenModel<KomgaOrganizationState>(KomgaOrganizationState()) {
    private var request: Job? = null
    private var generation = 0

    private suspend fun loadRoles(refresh: Boolean) {
        try {
            val roles =
                (
                    if (refresh) {
                        repository.account().roles
                    } else {
                        repository.cachedRoles() ?: repository.account().roles
                    }
                    )
                    .map { it.removePrefix("ROLE_") }
            repository.checkActive()
            mutableState.update {
                it.copy(admin = "ADMIN" in roles, downloadAllowed = "FILE_DOWNLOAD" in roles)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    init {
        load()
        screenModelScope.launchIO { loadRoles(false) }
        screenModelScope.launch {
            koharia.komga.api.KomgaOrganizationUpdates.changes.collectLatest { update ->
                if (
                    update.sourceId != repository.source.id ||
                    (update.kind != null && update.kind != kind)
                ) {
                    return@collectLatest
                }
                if (update.progressOnly) {
                    if (organizationId != null && kind == KomgaOrganizationKind.READ_LIST) {
                        try {
                            val revision = generation
                            val query = state.value.query
                            val books = repository.api.books(organizationId, query, true).content
                            repository.checkActive()
                            if (revision == generation) {
                                mutableState.update { it.copy(books = books) }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                        }
                    }
                } else if (
                    organizationId == null || update.id == null || update.id == organizationId
                ) {
                    load(refresh = true)
                }
            }
        }
    }

    fun load(refresh: Boolean = false) {
        request?.cancel()
        val revision = ++generation
        val query = state.value.query
        request =
            screenModelScope.launchIO {
                mutableState.update { it.copy(loading = true, error = null) }
                try {
                    repository.checkActive()
                    if (refresh) loadRoles(true)
                    val libraries =
                        if (state.value.libraries.isEmpty()) {
                            repository.api.libraries()
                        } else {
                            state.value.libraries
                        }
                    val current = organizationId?.let { repository.api.detail(kind, it, refresh) }
                    val objects =
                        if (organizationId == null) {
                            repository.api.list(kind, query, refresh)
                        } else {
                            null
                        }
                    val series =
                        if (organizationId != null && kind == KomgaOrganizationKind.COLLECTION) {
                            repository.api.series(organizationId, query, refresh)
                        } else {
                            null
                        }
                    val books =
                        if (organizationId != null && kind == KomgaOrganizationKind.READ_LIST) {
                            repository.api.books(organizationId, query, refresh)
                        } else {
                            null
                        }
                    if (books != null) repository.registerDownloadAliases(books.content)
                    repository.checkActive()
                    if (revision == generation) {
                        mutableState.update {
                            it.copy(
                                loading = false,
                                libraries = libraries,
                                organization = current,
                                objects = objects?.content.orEmpty(),
                                series = series?.content.orEmpty(),
                                books = books?.content.orEmpty(),
                                totalPages =
                                (
                                    objects?.totalPages
                                        ?: series?.totalPages
                                        ?: books?.totalPages
                                        ?: 0
                                    )
                                    .toInt(),
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (revision == generation) {
                        mutableState.update { it.copy(loading = false, error = error) }
                    }
                }
            }
    }

    fun search(query: String) {
        mutableState.update {
            it.copy(query = it.query.copy(search = query, page = 0), selected = emptySet())
        }
        load()
    }

    fun toolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun page(page: Int) {
        mutableState.update { it.copy(query = it.query.copy(page = page), selected = emptySet()) }
        load()
    }

    fun filter(filters: Map<String, List<String>>, sort: String) {
        mutableState.update {
            it.copy(
                query = it.query.copy(filters = filters, sort = sort, page = 0),
                selected = emptySet(),
            )
        }
        load()
    }

    fun select(id: String) {
        mutableState.update {
            it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selected = emptySet()) }
    }

    fun action(refresh: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy) return
        screenModelScope.launchIO {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                repository.checkActive()
                block()
                repository.checkActive()
                clearSelection()
                if (refresh) load(refresh = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun mangas(): List<Manga> =
        if (kind == KomgaOrganizationKind.COLLECTION) {
            state.value.series.map {
                it.toSManga(repository.source.baseUrl)
                    .toDomainManga(repository.source.id)
                    .copy(
                        id = KomgaOrganizationRepository.presentationId(it.id),
                        thumbnailUrl =
                        "${repository.source.baseUrl}/api/v1/series/${it.id}/thumbnail?account=${repository.namespace}",
                    )
            }
        } else {
            state.value.books.map {
                it.toSManga(repository.source.baseUrl)
                    .toDomainManga(repository.source.id)
                    .copy(
                        id = KomgaOrganizationRepository.presentationId(it.id),
                        thumbnailUrl =
                        "${repository.source.baseUrl}/api/v1/books/${it.id}/thumbnail?account=${repository.namespace}",
                    )
            }
        }

    suspend fun selectedBooks(): List<BookDto> =
        if (kind == KomgaOrganizationKind.READ_LIST) {
            state.value.books.filter { it.id in state.value.selected }
        } else {
            state.value.series
                .filter { it.id in state.value.selected }
                .flatMap { repository.api.seriesBooks(it.id) }
        }

    suspend fun allBooks(): List<BookDto> =
        if (kind == KomgaOrganizationKind.READ_LIST) {
            repository.api
                .books(requireNotNull(organizationId), KomgaOrganizationQuery(unpaged = true))
                .content
        } else {
            repository.api
                .series(requireNotNull(organizationId), KomgaOrganizationQuery(unpaged = true))
                .content
                .flatMap { repository.api.seriesBooks(it.id) }
        }
}
