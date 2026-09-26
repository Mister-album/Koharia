package koharia.cover

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun SavedCustomCoverDialog(onSelect: (Uri) -> Unit, onDismissRequest: () -> Unit) {
    val store = remember { Injekt.get<CustomCoverStore>() }
    var covers by remember { mutableStateOf<List<Uri>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            covers = store.savedCovers()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.custom_cover_choose_saved)) },
        text = {
            val images = covers
            when {
                failed -> Text(stringResource(MR.strings.notification_cover_update_failed))
                images == null -> CircularProgressIndicator()
                images.isEmpty() -> Text(stringResource(MR.strings.custom_cover_saved_empty))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(80.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(images, key = Uri::toString) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(MR.strings.manga_cover),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.aspectRatio(0.7f).clickable { onSelect(uri) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
