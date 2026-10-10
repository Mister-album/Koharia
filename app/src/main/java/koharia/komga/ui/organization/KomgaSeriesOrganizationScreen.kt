package koharia.komga.ui.organization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaOrganizationQuery
import koharia.komga.api.dto.BookDto
import koharia.komga.domain.repository.KomgaOrganizationRepository
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class KomgaSeriesOrganizationState(
    val loaded: Boolean = false,
    val error: Throwable? = null,
    val books: List<BookDto> = emptyList(),
    val seriesIds: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
)

class KomgaSeriesOrganizationModel(
    repository: KomgaOrganizationRepository,
    resources: List<String>,
) : StateScreenModel<KomgaSeriesOrganizationState>(KomgaSeriesOrganizationState()) {
    init {
        screenModelScope.launchIO {
            try {
                repository.authorize()
                val series = linkedSetOf<String>()
                val books = mutableListOf<BookDto>()
                for (url in resources) {
                    val id = url.substringBefore('?').substringAfterLast('/')
                    when {
                        url.contains("/api/v1/books/") ->
                            repository.api.book(id).also {
                                books += it
                                if (it.oneshot) series += it.seriesId
                            }
                        url.contains("/api/v1/readlists/") ->
                            repository.api
                                .books(id, KomgaOrganizationQuery(unpaged = true))
                                .content
                                .also { result ->
                                    books += result
                                    if (result.all { it.oneshot }) series += result.map { it.seriesId }
                                }
                        url.contains("/api/v1/series/") -> {
                            series += id
                            books += repository.api.seriesBooks(id)
                        }
                    }
                }
                repository.checkActive()
                mutableState.update {
                    it.copy(
                        loaded = true,
                        books = books.distinctBy { it.id },
                        seriesIds = series.toList(),
                        selected = books.map { it.id }.toSet(),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error) }
            }
        }
    }

    fun select(id: String) {
        mutableState.update {
            it.copy(
                selected =
                if (id in it.selected) {
                    it.selected - id
                } else {
                    it.selected + id
                },
            )
        }
    }
}

data class KomgaSeriesOrganizationScreen(val sourceId: Long, val resourceUrls: List<String>) :
    Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? KomgaSource ?: return
        val repository =
            remember(source, source.shelfCacheNamespace()) { source.organizationRepository() }
        val model =
            rememberScreenModel(tag = repository.namespace) {
                KomgaSeriesOrganizationModel(repository, resourceUrls)
            }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        Scaffold(
            topBar = {
                AppBar(
                    title = source.name,
                    subtitle = stringResource(MR.strings.komga_organization_actions),
                    navigateUp = { navigator.pop() },
                )
            },
        ) { padding ->
            if (!state.loaded && state.error == null) {
                LoadingScreen(Modifier.padding(padding))
            } else {
                LazyColumn(contentPadding = padding) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            state.error?.let {
                                Text(
                                    organizationError(context, it),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(
                                enabled = state.seriesIds.isNotEmpty(),
                                onClick = {
                                    navigator.push(
                                        KomgaAddToOrganizationScreen(
                                            sourceId,
                                            KomgaOrganizationKind.COLLECTION,
                                            state.seriesIds,
                                        ),
                                    )
                                },
                            ) {
                                Text(stringResource(MR.strings.komga_add_collection))
                            }
                            TextButton(
                                enabled = state.selected.isNotEmpty(),
                                onClick = {
                                    navigator.push(
                                        KomgaAddToOrganizationScreen(
                                            sourceId,
                                            KomgaOrganizationKind.READ_LIST,
                                            state.books
                                                .filter { it.id in state.selected }
                                                .map { it.id },
                                        ),
                                    )
                                },
                            ) {
                                Text(stringResource(MR.strings.komga_add_readlist))
                            }
                            Text(
                                stringResource(MR.strings.komga_manage_members),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    items(state.books, key = { it.id }) { book ->
                        Row(Modifier.padding(horizontal = 16.dp)) {
                            Checkbox(book.id in state.selected, { model.select(book.id) })
                            Text(
                                "${book.seriesTitle} · ${book.metadata.title}",
                                Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

data class KomgaAddToOrganizationScreen(
    val sourceId: Long,
    val kind: KomgaOrganizationKind,
    val members: List<String>,
) : Screen() {
    @Composable
    override fun Content() {
        KomgaOrganizationScreen(sourceId, kind, addingMembers = members).Content()
    }
}
