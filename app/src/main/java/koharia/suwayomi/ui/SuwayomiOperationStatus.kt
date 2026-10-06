package koharia.suwayomi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SuwayomiOperationStatus(loading: Boolean, error: Throwable?, retry: () -> Unit) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error != null) {
        Column {
            Text(LocalContext.current.suwayomiError(error))
            TextButton(onClick = retry, enabled = !loading) { Text(stringResource(MR.strings.action_retry)) }
        }
    }
}
