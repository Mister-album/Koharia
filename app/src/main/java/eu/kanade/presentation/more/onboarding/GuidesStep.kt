package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import koharia.connection.ConnectionProvider
import koharia.connection.ui.ConnectionProviderIcon
import koharia.source.kavita.KavitaConnectionProvider
import koharia.source.komga.KomgaConnectionProvider
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.local.LocalFolderConnectionProvider
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.suwayomi.SuwayomiConnectionProvider
import tachiyomi.core.common.DocumentationUrls
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

internal class GuidesStep(
    private val providers: List<ConnectionProvider>,
    private val onAddConnection: (String) -> Unit,
    private val onRestoreBackup: () -> Unit,
) : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val handler = LocalUriHandler.current

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(stringResource(MR.strings.onboarding_guides_new_user, stringResource(MR.strings.app_name)))
            providers.forEach { provider ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ConnectionProviderIcon(provider.iconRes, Modifier.size(28.dp))
                        Text(
                            text = provider.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(
                            modifier = Modifier.testTag("onboarding-provider-${provider.id}"),
                            onClick = { onAddConnection(provider.id) },
                        ) {
                            Text(stringResource(MR.strings.action_add))
                        }
                    }
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { handler.openUri(DocumentationUrls.gettingStarted(context)) },
            ) {
                Text(stringResource(MR.strings.getting_started_guide))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Text(stringResource(MR.strings.onboarding_guides_returning_user, stringResource(MR.strings.app_name)))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRestoreBackup,
            ) {
                Text(stringResource(MR.strings.pref_restore_backup))
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun GuidesStepPreview() {
    val context = LocalContext.current
    TachiyomiPreviewTheme {
        GuidesStep(
            providers = listOf(
                KomgaConnectionProvider(),
                LanraragiConnectionProvider(context),
                SuwayomiConnectionProvider(context),
                KavitaConnectionProvider(context),
                SmangaConnectionProvider(context),
                LocalFolderConnectionProvider(context),
            ),
            onAddConnection = {},
            onRestoreBackup = {},
        ).Content()
    }
}
