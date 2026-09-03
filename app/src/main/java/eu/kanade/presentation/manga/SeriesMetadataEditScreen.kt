package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MetadataGenerationDialog
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.ui.SeriesMetadataEditScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SeriesMetadataEditScreen(
    state: SeriesMetadataEditScreenModel.State,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onGenresChange: (String) -> Unit,
    onOpenMetadataGeneration: () -> Unit,
    onDismissMetadataGeneration: () -> Unit,
    onFilenameTemplateChange: (MetadataFilenameTemplate) -> Unit,
    onGenerateMetadataPreview: () -> Unit,
    onApplyGeneratedMetadata: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                titleContent = {
                    AppBarTitle(
                        title = stringResource(MR.strings.action_edit_series_details),
                        subtitle = state.originalTitle,
                    )
                },
                navigateUp = navigateUp,
                actions = {
                    IconButton(
                        onClick = onSave,
                        enabled = state.canSave,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = stringResource(MR.strings.action_save),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isLoading || state.isSaving) {
                EInkLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.supportsMetadataGeneration) {
                ListItem(
                    modifier = Modifier.clickable(
                        enabled = !state.isLoading && !state.isSaving,
                        onClick = onOpenMetadataGeneration,
                    ),
                    headlineContent = { Text(stringResource(MR.strings.action_generate_metadata)) },
                    supportingContent = { Text(stringResource(MR.strings.metadata_generation_notice)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                        )
                    },
                )
            }
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.title)) },
                supportingText = {
                    if (state.title.isBlank()) {
                        Text(stringResource(MR.strings.information_required_plain))
                    }
                },
                enabled = !state.isSaving,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.author,
                onValueChange = onAuthorChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.author)) },
                enabled = !state.isSaving,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.artist,
                onValueChange = onArtistChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.artist)) },
                enabled = !state.isSaving,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.genres,
                onValueChange = onGenresChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.genres)) },
                supportingText = { Text(stringResource(MR.strings.series_details_genres_hint)) },
                enabled = !state.isSaving,
                minLines = 2,
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.description)) },
                enabled = !state.isSaving,
                minLines = 5,
            )
        }
    }

    if (state.showMetadataGeneration) {
        MetadataGenerationDialog(
            selectedTemplate = state.filenameTemplate,
            suggestion = state.generatedMetadata,
            isGenerating = state.isGeneratingMetadata,
            onTemplateSelected = onFilenameTemplateChange,
            onGeneratePreview = onGenerateMetadataPreview,
            onApply = onApplyGeneratedMetadata,
            onDismissRequest = onDismissMetadataGeneration,
        )
    }
}
