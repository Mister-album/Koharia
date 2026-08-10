package koharia.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LibraryConnectionSetupPrompt(
    onConfigureConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(MR.strings.connection_no_profiles_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = onConfigureConnection,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(text = stringResource(MR.strings.action_add_connection))
        }
    }
}
