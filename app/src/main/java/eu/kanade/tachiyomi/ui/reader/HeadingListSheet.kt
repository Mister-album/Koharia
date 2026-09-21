package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import koharia.document.DocumentHeading
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Bottom sheet that lists document headings (level + title) for a text-format document
 * (currently only Markdown). Tap a row to navigate the reader to that heading's page.
 *
 * Mirrors the EPUB navigation sheet styling but is rendered when the underlying reader is a
 * reflowable text-format document served by [koharia.document.DocumentSession] rather than
 * a Readium publication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HeadingListSheet(
    headings: List<DocumentHeading>,
    currentPageIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (DocumentHeading) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Most recent heading at or before the current page.
    val activeHeadingIndex = headings.indexOfLast { it.pageIndex <= currentPageIndex }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(MR.strings.epub_reader_toc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
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
