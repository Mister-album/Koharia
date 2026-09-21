package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import koharia.document.DocumentHeading
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Bottom sheet that lists document headings (level + title) for a text-format document
 * (currently only Markdown). Tap a row to navigate the reader to that heading's page.
 *
 * Uses [AdaptiveSheet] so it follows the app-wide sheet conventions (tablet centred layout,
 * E-Ink animation policy, back handling) like the sibling EPUB navigation sheet.
 */
@Composable
internal fun HeadingListSheet(
    headings: List<DocumentHeading>,
    currentPageIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (DocumentHeading) -> Unit,
) {
    val listState = rememberLazyListState()
    // Most recent heading at or before the current page. Memoised: the sheet recomposes on
    // every scroll/animation frame, so an O(H) scan per frame is avoidable.
    val activeHeadingIndex = remember(headings, currentPageIndex) {
        headings.indexOfLast { it.pageIndex <= currentPageIndex }
    }

    // Bring the current heading into view when the sheet opens, mirroring EpubNavigationSheet's
    // TocTab — otherwise the highlighted row is off-screen on long documents.
    LaunchedEffect(activeHeadingIndex) {
        if (activeHeadingIndex >= 0) {
            listState.scrollToItem(activeHeadingIndex)
        }
    }

    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(MR.strings.epub_reader_toc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // itemsIndexed (not items) so we can use the iteration index for both the
                // LazyColumn key (must be unique — duplicate titles would collide) and the
                // active-row comparison (avoids an O(N) linear search per row).
                itemsIndexed(headings, key = { index, _ -> index }) { index, heading ->
                    Text(
                        text = heading.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (heading.level <= 2) FontWeight.Medium else FontWeight.Normal,
                        color = if (index == activeHeadingIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(heading) }
                            .padding(
                                start = (16 + (heading.level - 1).coerceAtLeast(0) * 16).dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                    )
                }
            }
        }
    }
}
