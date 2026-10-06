package koharia.suwayomi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import koharia.suwayomi.SuwayomiApi
import okhttp3.OkHttpClient

/**
 * Server icons need the connection's authenticated client, which the shared cover pipeline does
 * not own for catalogue rows. The shared bounded bitmap and disk cache avoids repeat downloads.
 */
@Composable
internal fun SuwayomiIcon(
    url: String?,
    description: String?,
    client: OkHttpClient,
    api: SuwayomiApi,
    modifier: Modifier = Modifier.size(40.dp),
) {
    val context = LocalContext.current
    val cacheKey = url?.let { SuwayomiImageCache.key(api, it, 128) }
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let(SuwayomiImageCache::peek)) }
    LaunchedEffect(url, client, api) {
        val target = url ?: return@LaunchedEffect
        cacheKey?.let(SuwayomiImageCache::peek)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val loaded = SuwayomiImageCache.load(context, client, api, target, 128)
        if (loaded != null) {
            bitmap = loaded
        }
    }
    val image = bitmap
    if (image == null) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = description,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = description,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
