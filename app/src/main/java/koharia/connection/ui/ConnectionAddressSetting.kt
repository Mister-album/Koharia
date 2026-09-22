package koharia.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import koharia.connection.ConnectionAddressRouter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ConnectionAddressSetting(
    publicAddress: String,
    internalAddress: String,
    enabled: Boolean,
    onConfirm: (String, String) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    TextPreferenceWidget(
        title = stringResource(MR.strings.lanraragi_address),
        subtitle = publicAddress.ifBlank { stringResource(MR.strings.lanraragi_error_address) },
        enabled = enabled,
        onPreferenceClick = { showDialog = true },
    )
    if (showDialog) {
        var publicDraft by rememberSaveable { mutableStateOf(publicAddress) }
        var internalDraft by rememberSaveable { mutableStateOf(internalAddress) }
        var advancedExpanded by rememberSaveable { mutableStateOf(false) }
        val validPublic = ConnectionAddressRouter.normalize(publicDraft) != null
        val validInternal = internalDraft.isBlank() || ConnectionAddressRouter.normalize(internalDraft) != null
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(MR.strings.lanraragi_address)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = publicDraft,
                        onValueChange = { publicDraft = it },
                        label = { Text(stringResource(MR.strings.connection_public_address)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = publicDraft.isNotBlank() && !validPublic,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    TextButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(stringResource(MR.strings.connection_address_advanced))
                    }
                    if (advancedExpanded) {
                        OutlinedTextField(
                            value = internalDraft,
                            onValueChange = { internalDraft = it },
                            label = { Text(stringResource(MR.strings.connection_internal_address)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = !validInternal,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Text(
                            stringResource(MR.strings.connection_internal_address_summary),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validPublic && validInternal,
                    onClick = {
                        onConfirm(publicDraft.trim(), internalDraft.trim())
                        showDialog = false
                    },
                ) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
