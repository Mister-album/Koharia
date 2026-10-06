package koharia.suwayomi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import koharia.suwayomi.SuwayomiApi
import okhttp3.OkHttpClient
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator

/** One server image reference, so rows and the preview share the same authenticated request. */
internal data class SuwayomiImageRef(
    val url: String?,
    val client: OkHttpClient,
    val api: SuwayomiApi,
)

/**
 * A server cover for migration comparisons. Covers come from the same authenticated endpoint as
 * catalogue thumbnails and share a bounded bitmap and disk cache. A failed or missing cover
 * degrades to a plain placeholder instead of hiding the row.
 */
@Composable
internal fun SuwayomiThumbnail(
    ref: SuwayomiImageRef,
    description: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val context = LocalContext.current
    val cacheKey = ref.url?.let { SuwayomiImageCache.key(ref.api, it, THUMBNAIL_MAX_WIDTH) }
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let(SuwayomiImageCache::peek)) }
    LaunchedEffect(ref.url, ref.client, ref.api) {
        val target = ref.url ?: return@LaunchedEffect
        cacheKey?.let(SuwayomiImageCache::peek)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val loaded = SuwayomiImageCache.load(context, ref.client, ref.api, target, THUMBNAIL_MAX_WIDTH)
        if (loaded != null) {
            bitmap = loaded
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val image = bitmap
    if (image == null) {
        Box(
            modifier = modifier
                .size(width = size, height = size * 4 / 3)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = description,
            modifier = modifier.size(width = size, height = size * 4 / 3).clip(shape),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * A row cover that opens the full-resolution preview when tapped. The preview reuses the source
 * files identity for its Auth header, so nothing is fetched unauthenticated.
 */
@Composable
internal fun SuwayomiPreviewableThumbnail(
    ref: SuwayomiImageRef,
    title: String,
    subtitle: String? = null,
    size: Dp = 56.dp,
) {
    var preview by remember { mutableStateOf(false) }
    SuwayomiThumbnail(
        ref = ref,
        description = title,
        size = size,
        modifier = Modifier.clickable(enabled = ref.url != null) { preview = true },
    )
    if (preview) {
        SuwayomiImagePreviewDialog(
            ref = ref,
            title = title,
            subtitle = subtitle,
            onDismiss = { preview = false },
        )
    }
}

/** Full-size cover preview for one migration row. */
@Composable
internal fun SuwayomiImagePreviewDialog(
    ref: SuwayomiImageRef,
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cacheKey = ref.url?.let { SuwayomiImageCache.key(ref.api, it, PREVIEW_MAX_WIDTH) }
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let(SuwayomiImageCache::peek)) }
    var loading by remember(ref.url) { mutableStateOf(bitmap == null && ref.url != null) }
    LaunchedEffect(ref.url, ref.client, ref.api) {
        val target = ref.url ?: return@LaunchedEffect
        cacheKey?.let(SuwayomiImageCache::peek)?.let {
            bitmap = it
            loading = false
            return@LaunchedEffect
        }
        val loaded = SuwayomiImageCache.load(context, ref.client, ref.api, target, PREVIEW_MAX_WIDTH)
        bitmap = loaded
        loading = false
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            val image = bitmap
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 640.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> EInkCircularProgressIndicator()
                    image == null -> Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.ok))
            }
        }
    }
}

private const val THUMBNAIL_MAX_WIDTH = 256
private const val PREVIEW_MAX_WIDTH = 1024
