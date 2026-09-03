package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator

@Composable
internal fun BrowseSourceLoadingItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        EInkCircularProgressIndicator()
    }
}
