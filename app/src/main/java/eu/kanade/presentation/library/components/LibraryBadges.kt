package eu.kanade.presentation.library.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import koharia.connection.MangaDownloadState
import koharia.connection.MangaDownloadStatus
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val LibraryReadProgressCornerSize = 32.dp

@Composable
internal fun ManualDownloadIndicator(state: MangaDownloadState, modifier: Modifier = Modifier) {
    if (state.status == MangaDownloadStatus.NONE) return
    val showCount by Injekt.get<LibraryPreferences>().showShelfDownloadCount.collectAsState()
    val description = when (state.status) {
        MangaDownloadStatus.COMPLETE -> stringResource(MR.strings.shelf_download_complete, state.count)
        MangaDownloadStatus.PARTIAL -> stringResource(MR.strings.shelf_download_partial, state.count, state.total ?: 0)
        else -> stringResource(MR.strings.shelf_download_unknown, state.count)
    }
    val accessible = modifier.semantics { contentDescription = description }
    if (state.status == MangaDownloadStatus.COMPLETE) {
        DownloadedCorner(accessible)
    } else {
        androidx.compose.foundation.layout.Row(
            modifier = accessible.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Download,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (showCount) {
                Text(
                    if (state.status ==
                        MangaDownloadStatus.PARTIAL
                    ) {
                        "${state.count}/${state.total}"
                    } else {
                        "${state.count}/?"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

enum class MangaReadProgressDisplay {
    CHAPTERS,
    PAGE,
    PERCENTAGE,
}

data class MangaReadProgress(
    val readCount: Long,
    val totalChapterCount: Long,
    val display: MangaReadProgressDisplay = MangaReadProgressDisplay.CHAPTERS,
)

@Composable
internal fun MangaReadProgress.displayText(): String? {
    return when (display) {
        MangaReadProgressDisplay.CHAPTERS -> {
            takeIf { totalChapterCount > 0 }?.let { "$readCount/$totalChapterCount" }
        }
        MangaReadProgressDisplay.PAGE -> {
            stringResource(MR.strings.chapter_progress_short, readCount)
        }
        MangaReadProgressDisplay.PERCENTAGE -> {
            stringResource(MR.strings.epub_chapter_progress_short, readCount)
        }
    }
}

@Composable
internal fun DownloadsBadge(count: Long) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        Badge(text = "$count")
    }
}

@Composable
internal fun DownloadedCorner(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
internal fun DownloadedBadge(enabled: Boolean) {
    if (enabled) {
        Badge(
            imageVector = Icons.Filled.Check,
            color = MaterialTheme.colorScheme.primaryContainer,
            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
internal fun LibraryReadProgressCorner(
    readCount: Long,
    totalChapterCount: Long,
    text: String? = null,
    modifier: Modifier = Modifier,
) {
    if (readCount < 0 || totalChapterCount <= 0) return

    val cornerColor = MaterialTheme.colorScheme.primaryContainer
    val progressColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(LibraryReadProgressCornerSize)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    close()
                },
                color = cornerColor,
            )
        }
        Text(
            text = text ?: "$readCount/$totalChapterCount",
            color = progressColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                lineHeight = 8.sp,
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-1).dp, y = 5.dp)
                .rotate(45f),
        )
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        Badge(
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
