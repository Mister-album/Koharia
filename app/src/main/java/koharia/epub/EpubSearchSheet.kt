package koharia.epub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import koharia.epub.model.EpubSearchResult
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun EpubSearchSheet(
    query: String,
    brightnessState: EpubBrightnessState,
    isLoading: Boolean,
    results: List<EpubSearchResult>,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onSelectResult: (EpubSearchResult) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        EpubBrightnessAwareDialogContent(brightnessState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.65f),
            ) {
                Text(
                    text = stringResource(MR.strings.epub_reader_search_results),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        EmptySearchMessage(errorMessage)
                    }
                    query.isBlank() -> {
                        EmptySearchMessage(stringResource(MR.strings.epub_reader_search_hint))
                    }
                    results.isEmpty() -> {
                        EmptySearchMessage(stringResource(MR.strings.epub_reader_search_no_results))
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(results) { result ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            text = result.title ?: result.locator.href.toString(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = result.snippet(),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.clickable { onSelectResult(result) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySearchMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
    }
}

private fun EpubSearchResult.snippet(): String {
    return listOfNotNull(before, highlight, after)
        .joinToString(" ")
        .ifBlank { title ?: locator.href.toString() }
}
