package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

@Composable
fun NewUpdateScreen(
    versionName: String,
    changelogInfo: String,
    updateAvailable: Boolean,
    onOpenInBrowser: () -> Unit,
    onRejectUpdate: () -> Unit,
    onAcceptUpdate: () -> Unit,
) {
    InfoScreen(
        icon = if (updateAvailable) Icons.Outlined.NewReleases else Icons.Outlined.CheckCircle,
        headingText = stringResource(
            if (updateAvailable) {
                MR.strings.update_check_notification_update_available
            } else {
                MR.strings.update_check_no_new_updates
            },
        ),
        subtitleText = if (updateAvailable) {
            versionName
        } else {
            stringResource(MR.strings.update_check_current_version, versionName)
        },
        acceptText = stringResource(
            if (updateAvailable) MR.strings.update_check_confirm else MR.strings.action_close,
        ),
        onAcceptClick = if (updateAvailable) onAcceptUpdate else onRejectUpdate,
        rejectText = if (updateAvailable) stringResource(MR.strings.action_not_now) else null,
        onRejectClick = if (updateAvailable) onRejectUpdate else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.large),
        ) {
            if (changelogInfo.isNotBlank()) {
                MarkdownRender(
                    content = changelogInfo,
                    flavour = GFMFlavourDescriptor(),
                )
            } else {
                Text(text = stringResource(MR.strings.update_check_release_notes_unavailable))
            }

            TextButton(
                onClick = onOpenInBrowser,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            ) {
                Text(text = stringResource(MR.strings.update_check_open_release_page))
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun NewUpdateScreenPreview() {
    TachiyomiPreviewTheme {
        NewUpdateScreen(
            versionName = "v0.99.9",
            updateAvailable = true,
            changelogInfo = """
                ## Yay
                Foobar

                ### More info
                - Hello
                - World
            """.trimIndent(),
            onOpenInBrowser = {},
            onRejectUpdate = {},
            onAcceptUpdate = {},
        )
    }
}
