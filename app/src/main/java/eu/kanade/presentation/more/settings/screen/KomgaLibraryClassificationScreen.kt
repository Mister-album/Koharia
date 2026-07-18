package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaLibraryKind
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaLibraryClassificationScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val serverPreferences = remember { Injekt.get<KomgaServerPreferences>() }
        val classificationManager = remember { Injekt.get<KomgaLibraryClassificationManager>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val activeServerId by serverPreferences.activeServerId.collectAsState()
        val libraries by remember(activeServerId) {
            classificationManager.classificationsChanges(activeServerId)
        }.collectAsState(initial = classificationManager.getLibraries(activeServerId))
        val scope = rememberCoroutineScope()
        var isLoading by remember(activeServerId) { mutableStateOf(false) }
        var loadFailed by remember(activeServerId) { mutableStateOf(false) }

        fun refresh() {
            if (activeServerId == KomgaServerPreferences.NO_ACTIVE_SERVER || isLoading) return
            scope.launch {
                isLoading = true
                loadFailed = false
                val source = sourceManager.get(activeServerId) as? KomgaSource
                val result = runCatching {
                    val validSource = requireNotNull(source)
                    check(validSource.hasValidBaseUrl())
                    validSource.getBrowseLibraries(forceRefresh = true)
                }
                result.onSuccess { classificationManager.updateLibraries(activeServerId, it) }
                loadFailed = result.isFailure
                isLoading = false
            }
        }

        LaunchedEffect(activeServerId) {
            refresh()
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.komga_library_classification_configure),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(
                            enabled = activeServerId != KomgaServerPreferences.NO_ACTIVE_SERVER && !isLoading,
                            onClick = ::refresh,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(MR.strings.action_webview_refresh),
                            )
                        }
                    },
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                if (isLoading) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (activeServerId == KomgaServerPreferences.NO_ACTIVE_SERVER) {
                    item {
                        Text(
                            text = stringResource(MR.strings.komga_library_classification_no_server),
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    if (loadFailed) {
                        item {
                            Text(
                                text = stringResource(MR.strings.komga_library_classification_load_failed),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (!isLoading && libraries.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(MR.strings.komga_library_classification_no_libraries),
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(
                        items = libraries,
                        key = { it.id },
                    ) { library ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(text = library.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = library.kind == KomgaLibraryKind.COMIC,
                                    onClick = {
                                        classificationManager.setKind(
                                            activeServerId,
                                            library.id,
                                            KomgaLibraryKind.COMIC,
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.label_comics)) },
                                )
                                FilterChip(
                                    selected = library.kind == KomgaLibraryKind.BOOK,
                                    onClick = {
                                        classificationManager.setKind(
                                            activeServerId,
                                            library.id,
                                            KomgaLibraryKind.BOOK,
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.label_books)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
