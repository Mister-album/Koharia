package koharia.epub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.svg.SvgDecoder
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.Buffer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun EpubImageOverlay(
    state: EpubImageUiState,
    onClosePreview: () -> Unit,
    onDismissActions: () -> Unit,
    onPreview: () -> Unit,
    onShowActions: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    if (state.previewVisible) {
        BackHandler(enabled = !state.actionsVisible, onBack = onClosePreview)
        EpubImagePreview(
            state = state,
            onClose = onClosePreview,
            onShowActions = onShowActions,
            onRetry = onRetry,
        )
    }

    if (state.actionsVisible) {
        EpubImageActionsSheet(
            isLoading = state.isLoading,
            onDismissRequest = onDismissActions,
            onPreview = onPreview,
            onSave = onSave,
            onShare = onShare,
            onCopy = onCopy,
        )
    }
}

@Composable
private fun EpubImagePreview(
    state: EpubImageUiState,
    onClose: () -> Unit,
    onShowActions: () -> Unit,
    onRetry: () -> Unit,
) {
    var controlsVisible by rememberSaveable(state.reference) { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (state.content == null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onClose,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        state.content?.let { content ->
            EpubZoomableImage(
                content = content,
                onImageClick = { controlsVisible = !controlsVisible },
                onOutsideClick = onClose,
            )
        }

        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
            state.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = state.errorMessage, color = Color.White)
                    TextButton(onClick = onRetry) {
                        Text(stringResource(MR.strings.action_retry))
                    }
                }
            }
        }

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onShowActions, enabled = !state.isLoading && state.content != null) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(MR.strings.epub_reader_image_actions),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpubZoomableImage(
    content: EpubImageContent,
    onImageClick: () -> Unit,
    onOutsideClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageView = remember(content) {
        ReaderPageImageView(context).apply {
            val source = Buffer().write(content.bytes)
            val config = ReaderPageImageView.Config(
                zoomDuration = IMAGE_ZOOM_DURATION_MS,
                cropBorders = false,
            )
            if (content.isSvg) {
                setImageWithCoil(source, config, SvgDecoder.Factory())
            } else {
                setImage(source, content.isAnimated, config)
            }
        }
    }
    DisposableEffect(imageView) {
        onDispose {
            imageView.onViewTapped = null
            imageView.recycle()
        }
    }
    AndroidView(
        factory = { imageView },
        update = { view ->
            view.onViewTapped = { isInsideImage ->
                if (isInsideImage) onImageClick() else onOutsideClick()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EpubImageActionsSheet(
    isLoading: Boolean,
    onDismissRequest: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.medium),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            val actionColors = ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.epub_reader_image_preview),
                    icon = Icons.Outlined.Fullscreen,
                    colors = actionColors,
                    onClick = onPreview,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_save),
                    icon = Icons.Outlined.Save,
                    colors = actionColors,
                    onClick = onSave,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_share),
                    icon = Icons.Outlined.Share,
                    colors = actionColors,
                    onClick = onShare,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_copy),
                    icon = Icons.Outlined.ContentCopy,
                    colors = actionColors,
                    onClick = onCopy,
                )
            }
        }
    }
}

private const val IMAGE_ZOOM_DURATION_MS = 250
