package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.SManga
import koharia.connection.LibraryMetadataField
import koharia.connection.LibraryMetadataSuggestion
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.MetadataSuggestionSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MetadataGenerationDialog(
    selectedTemplate: MetadataFilenameTemplate,
    suggestion: LibraryMetadataSuggestion?,
    isGenerating: Boolean,
    onTemplateSelected: (MetadataFilenameTemplate) -> Unit,
    onGeneratePreview: () -> Unit,
    onApply: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val hasGeneratedFields = suggestion?.fieldSources?.isNotEmpty() == true
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.metadata_generation_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(MR.strings.metadata_generation_notice),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(MR.strings.metadata_filename_template),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                MetadataFilenameTemplate.entries.forEach { template ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedTemplate == template,
                                enabled = !isGenerating,
                                role = Role.RadioButton,
                                onClick = { onTemplateSelected(template) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedTemplate == template,
                            onClick = null,
                            enabled = !isGenerating,
                        )
                        Text(
                            text = stringResource(template.labelResource()),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                if (isGenerating) {
                    EInkCircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 20.dp),
                    )
                } else if (suggestion != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = stringResource(MR.strings.metadata_generated_preview),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(
                            MR.strings.metadata_generation_filename_matches,
                            suggestion.matchedFilenameCount,
                            suggestion.totalItemCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    if (hasGeneratedFields) {
                        LibraryMetadataField.entries.forEach { field ->
                            suggestion.fieldSources[field]?.let { source ->
                                GeneratedMetadataField(
                                    field = field,
                                    value = suggestion.value(field),
                                    source = source,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(MR.strings.metadata_generation_no_fields),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (suggestion == null) onGeneratePreview else onApply,
                enabled = !isGenerating && (suggestion == null || hasGeneratedFields),
            ) {
                Text(
                    stringResource(
                        if (suggestion == null) {
                            MR.strings.metadata_generate_preview
                        } else {
                            MR.strings.metadata_apply_preview
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isGenerating,
            ) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun GeneratedMetadataField(
    field: LibraryMetadataField,
    value: String,
    source: MetadataSuggestionSource,
) {
    ListItem(
        headlineContent = {
            Text(
                text = value,
                maxLines = if (field == LibraryMetadataField.DESCRIPTION) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        overlineContent = { Text(stringResource(field.labelResource())) },
        supportingContent = { Text(stringResource(source.labelResource())) },
    )
}

@Composable
private fun LibraryMetadataSuggestion.value(field: LibraryMetadataField): String = when (field) {
    LibraryMetadataField.TITLE -> metadata.title.orEmpty()
    LibraryMetadataField.AUTHOR -> metadata.author.orEmpty()
    LibraryMetadataField.ARTIST -> metadata.artist.orEmpty()
    LibraryMetadataField.DESCRIPTION -> metadata.description.orEmpty()
    LibraryMetadataField.GENRES -> metadata.genres.joinToString(", ")
    LibraryMetadataField.STATUS -> when (metadata.status) {
        SManga.ONGOING -> stringResource(MR.strings.ongoing)
        SManga.COMPLETED -> stringResource(MR.strings.completed)
        SManga.LICENSED -> stringResource(MR.strings.licensed)
        SManga.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
        SManga.CANCELLED -> stringResource(MR.strings.cancelled)
        SManga.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
        else -> stringResource(MR.strings.unknown_status)
    }
}

private fun MetadataFilenameTemplate.labelResource(): StringResource = when (this) {
    MetadataFilenameTemplate.AUTO -> MR.strings.metadata_template_auto
    MetadataFilenameTemplate.SERIES_VOLUME_TITLE -> MR.strings.metadata_template_series_volume_title
    MetadataFilenameTemplate.SERIES_CHAPTER_TITLE -> MR.strings.metadata_template_series_chapter_title
    MetadataFilenameTemplate.SERIES_TITLE -> MR.strings.metadata_template_series_title
    MetadataFilenameTemplate.FOLDER_ITEM_TITLE -> MR.strings.metadata_template_folder_item_title
}

private fun LibraryMetadataField.labelResource(): StringResource = when (this) {
    LibraryMetadataField.TITLE -> MR.strings.title
    LibraryMetadataField.AUTHOR -> MR.strings.author
    LibraryMetadataField.ARTIST -> MR.strings.artist
    LibraryMetadataField.DESCRIPTION -> MR.strings.description
    LibraryMetadataField.GENRES -> MR.strings.genres
    LibraryMetadataField.STATUS -> MR.strings.status
}

private fun MetadataSuggestionSource.labelResource(): StringResource = when (this) {
    MetadataSuggestionSource.FOLDER -> MR.strings.metadata_source_folder
    MetadataSuggestionSource.EPUB_EMBEDDED -> MR.strings.metadata_source_epub
    MetadataSuggestionSource.ITEM_FILENAME -> MR.strings.metadata_source_filename
}
