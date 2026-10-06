@file:Suppress("ktlint:standard:max-line-length")

package koharia.suwayomi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.connection.ConnectionShelfUpdates
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiCategoriesScreen(private val sourceId: Long, private val mangaUrl: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null) {
            EmptyScreen(MR.strings.connection_unavailable)
            return
        }
        val navigator = LocalNavigator.currentOrThrow
        val epoch by source.epoch.collectAsState()
        val model =
            rememberScreenModel(tag = "${source.instanceKey}:$epoch") {
                SuwayomiCategoriesScreenModel(source, mangaUrl)
            }
        val state by model.state.collectAsState()
        var selected by remember(state.selected) { mutableStateOf(state.selected) }
        var edit by remember { mutableStateOf<SuwayomiCategory?>(null) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<SuwayomiCategory?>(null) }
        Scaffold(topBar = {
            AppBar(title = stringResource(MR.strings.suwayomi_browse_categories), navigateUp = navigator::pop)
        }) { padding ->
            Column(Modifier.padding(padding)) {
                SuwayomiOperationStatus(state.loading, state.error, model::refresh)
                if (mangaUrl != null) {
                    Row {
                        TextButton(onClick = { model.save(selected) }, enabled = !state.loading && state.loaded) {
                            Text(stringResource(MR.strings.action_save))
                        }
                        TextButton(onClick = { navigator.push(SuwayomiCategoriesScreen(sourceId)) }) {
                            Text(stringResource(MR.strings.action_edit_categories))
                        }
                    }
                    TextButton(
                        onClick = { model.membership(!state.inLibrary) },
                        enabled =
                        !state.loading && state.loaded,
                    ) {
                        Text(
                            stringResource(
                                if (state.inLibrary) MR.strings.suwayomi_remove_library else MR.strings.suwayomi_add_library,
                            ),
                        )
                    }
                } else {
                    TextButton(onClick = { creating = true }, enabled = !state.loading) {
                        Text(stringResource(MR.strings.suwayomi_category_add))
                    }
                }
                LazyColumn {
                    items(state.categories.filter { it.id != 0 }, key = { it.id }) { category ->
                        ListItem(
                            headlineContent = { Text(category.name) },
                            leadingContent = if (mangaUrl != null) {
                                {
                                    Checkbox(category.id in selected, enabled = !state.loading, onCheckedChange = {
                                        selected = if (it) selected + category.id else selected - category.id
                                    })
                                }
                            } else {
                                null
                            },
                            trailingContent = if (mangaUrl == null) {
                                {
                                    Row {
                                        TextButton(onClick = { edit = category }, enabled = !state.loading) {
                                            Text(stringResource(MR.strings.suwayomi_category_rename))
                                        }
                                        IconButton(onClick = { deleting = category }, enabled = !state.loading) {
                                            Icon(Icons.Outlined.Delete, stringResource(MR.strings.action_delete))
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (creating || edit != null) {
            var name by remember(edit, creating) { mutableStateOf(edit?.name.orEmpty()) }
            AlertDialog(
                onDismissRequest = {
                    creating = false
                    edit = null
                },
                title = {
                    Text(
                        stringResource(
                            if (creating) MR.strings.suwayomi_category_add else MR.strings.suwayomi_category_rename,
                        ),
                    )
                },
                text = { OutlinedTextField(name, { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        model.edit(edit?.id, name.trim())
                        creating = false
                        edit = null
                    }, enabled = name.isNotBlank()) { Text(stringResource(MR.strings.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        creating = false
                        edit = null
                    }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
        deleting?.let { category ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(MR.strings.action_delete)) },
                text = { Text(category.name) },
                confirmButton = {
                    TextButton(onClick = {
                        model.delete(category.id)
                        deleting = null
                    }) { Text(stringResource(MR.strings.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

internal class SuwayomiCategoriesScreenModel(source: SuwayomiSource, mangaUrl: String?) :
    StateScreenModel<SuwayomiCategoriesScreenModel.State>(
        State(
            categories = source.session().catalog.categoryInventory.state.value.value.orEmpty(),
            loaded = mangaUrl == null && source.session().catalog.categoryInventory.state.value.loaded,
        ),
    ) {
    private val session = source.session()
    init {
        bindSession(session)
    }
    private val mangaId = mangaUrl?.let(session.identity::mangaId)
    private var job: Job? = null
    init {
        load(false)
    }
    fun refresh() = load(true)
    fun edit(id: Int?, name: String) = run(true) {
        if (id == null) session.api.createCategory(name) else session.api.updateCategory(id, name)
    }
    fun delete(id: Int) = run(true) { session.api.deleteCategory(id) }
    fun save(selected: Set<Int>) = run(true) {
        val id = mangaId ?: return@run
        val before = state.value.selected
        session.api.updateMangaCategories(id, selected - before, before - selected)
    }
    fun membership(inLibrary: Boolean) = run(true) {
        session.api.setLibraryMembership(checkNotNull(mangaId), inLibrary)
    }
    private fun load(refresh: Boolean) {
        if (job?.isActive == true) return
        job = screenModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                session.checkActive()
                val categories = session.catalog.categories(refresh)
                val manga = mangaId?.let { session.catalog.manga(it, refresh = refresh) }
                session.checkActive()
                mutableState.value = State(
                    categories = categories,
                    selected = manga?.categories?.nodes.orEmpty().map { it.id }.toSet(),
                    inLibrary = manga?.inLibrary ?: false,
                    loaded = true,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error) }
            }
        }
    }
    private fun run(changed: Boolean = false, action: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = screenModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                session.checkActive()
                action()
                session.catalog.categoryInventory.invalidate()
                session.catalog.invalidateShelf()
                session.catalog.invalidateListings()
                val categories = session.catalog.categories()
                val manga = mangaId?.let { session.catalog.manga(it, refresh = true) }
                if (changed) {
                    session.catalog.shelf(refresh = true)
                    session.checkActive()
                    ConnectionShelfUpdates.notify(session.identity.connectionId)
                }
                session.checkActive()
                mutableState.value =
                    State(
                        categories,
                        manga?.categories?.nodes.orEmpty().map { it.id }.toSet(),
                        manga?.inLibrary ?: false,
                        loaded = true,
                    )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error) }
            }
        }
    }
    data class State(
        val categories: List<SuwayomiCategory> = emptyList(),
        val selected: Set<Int> = emptySet(),
        val inLibrary: Boolean = false,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: Throwable? = null,
    )
}
