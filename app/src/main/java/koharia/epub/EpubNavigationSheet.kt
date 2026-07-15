package koharia.epub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import koharia.domain.epub.model.EpubBookmark
import koharia.epub.model.EpubTocEntry
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpubNavigationSheet(
    entries: List<EpubTocEntry>,
    bookmarks: List<EpubBookmark>,
    currentHref: String?,
    onDismissRequest: () -> Unit,
    onSelectTocEntry: (EpubTocEntry) -> Unit,
    onSelectBookmark: (EpubBookmark) -> Unit,
    onDeleteBookmark: (EpubBookmark) -> Unit,
    onUpdateBookmarkNote: (EpubBookmark, String?) -> Unit,
) {
    EpubNavigationContent(
        entries = entries,
        bookmarks = bookmarks,
        currentHref = currentHref,
        onSelectTocEntry = onSelectTocEntry,
        onSelectBookmark = onSelectBookmark,
        onDeleteBookmark = onDeleteBookmark,
        onUpdateBookmarkNote = onUpdateBookmarkNote,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpubNavigationContent(
    entries: List<EpubTocEntry>,
    bookmarks: List<EpubBookmark>,
    currentHref: String?,
    onSelectTocEntry: (EpubTocEntry) -> Unit,
    onSelectBookmark: (EpubBookmark) -> Unit,
    onDeleteBookmark: (EpubBookmark) -> Unit,
    onUpdateBookmarkNote: (EpubBookmark, String?) -> Unit,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingBookmark by remember { mutableStateOf<EpubBookmark?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTabIndex) {
                0 -> TocTab(
                    entries = entries,
                    currentHref = currentHref,
                    onSelect = onSelectTocEntry,
                )
                else -> BookmarkTab(
                    bookmarks = bookmarks,
                    onSelect = onSelectBookmark,
                    onEdit = { editingBookmark = it },
                    onDelete = onDeleteBookmark,
                )
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text(stringResource(MR.strings.epub_reader_toc)) },
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text(stringResource(MR.strings.epub_reader_bookmarks)) },
            )
        }
    }

    editingBookmark?.let { bookmark ->
        EditBookmarkNoteDialog(
            bookmark = bookmark,
            onDismissRequest = { editingBookmark = null },
            onConfirm = { note ->
                onUpdateBookmarkNote(bookmark, note)
                editingBookmark = null
            },
        )
    }
}

@Composable
private fun TocTab(
    entries: List<EpubTocEntry>,
    currentHref: String?,
    onSelect: (EpubTocEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(entries, currentHref) {
        entries.indexOfFirst { it.link.href.toString() == currentHref }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.scrollToItem(currentIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries) { entry ->
            val selected = entry.link.href.toString() == currentHref
            Text(
                text = entry.title,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onSelect(entry) })
                    .padding(start = (16 + entry.depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkTab(
    bookmarks: List<EpubBookmark>,
    onSelect: (EpubBookmark) -> Unit,
    onEdit: (EpubBookmark) -> Unit,
    onDelete: (EpubBookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.strings.epub_reader_no_bookmarks),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { bookmark ->
            val progressionText = bookmark.progression
                ?.coerceIn(0.0, 1.0)
                ?.times(100)
                ?.roundToInt()
                ?.let { "$it%" }
            val supportingText = listOfNotNull(
                progressionText,
                bookmark.note?.takeIf(String::isNotBlank),
            ).joinToString(" | ").takeIf(String::isNotBlank)
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        onDelete(bookmark)
                        true
                    } else {
                        false
                    }
                },
            )
            Column {
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(MR.strings.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = bookmark.sectionTitle?.takeIf(String::isNotBlank)
                                    ?: stringResource(MR.strings.epub_reader_bookmark),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = supportingText?.let { text ->
                            {
                                Text(
                                    text = text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onEdit(bookmark) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(MR.strings.action_edit),
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = { onDelete(bookmark) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(MR.strings.action_delete),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onSelect(bookmark) },
                            onLongClick = { onEdit(bookmark) },
                        ),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EditBookmarkNoteDialog(
    bookmark: EpubBookmark,
    onDismissRequest: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var note by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.epub_reader_edit_bookmark_note)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(MR.strings.epub_reader_bookmark_note)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
