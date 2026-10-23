package koharia.epub.replacement

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import koharia.connection.SharedAppPreferences
import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.ReplacementImport
import koharia.domain.textreplacement.ReplacementRule
import koharia.domain.textreplacement.ReplacementRuleFiles
import koharia.domain.textreplacement.ReplacementRuleGroup
import koharia.domain.textreplacement.ReplacementScope
import koharia.domain.textreplacement.ReplacementScopeState
import koharia.domain.textreplacement.StoredReplacementScope
import koharia.domain.textreplacement.TextReplacementContext
import koharia.domain.textreplacement.TextReplacementEngine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.LocalEInkDisplayPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

class TextReplacementScreen(private val mangaId: Long? = null, private val chapterId: Long? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        ReplacementMotion { TextReplacementContent(mangaId, chapterId, onClose = { navigator.pop() }) }
    }
}

@Composable
fun TextReplacementDialog(
    mangaId: Long,
    chapterId: Long,
    sample: String,
    previewDocument: ReplacementPreviewDocument? = null,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReplacementMotion { TextReplacementContent(mangaId, chapterId, sample, previewDocument, onClose) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplacementMotion(content: @Composable () -> Unit) {
    val ripple = if (LocalEInkDisplayPolicy.current.enabled) null else LocalRippleConfiguration.current
    CompositionLocalProvider(LocalRippleConfiguration provides ripple, content = content)
}

@Composable
private fun scopeLabel(scope: ReplacementScope) = stringResource(
    when (scope) {
        ReplacementScope.GLOBAL -> MR.strings.replacement_global
        ReplacementScope.SERIES -> MR.strings.replacement_series
        ReplacementScope.BOOK -> MR.strings.replacement_book
    },
)

@Composable
private fun TextReplacementContent(
    mangaId: Long?,
    chapterId: Long?,
    sample: String = "",
    previewDocument: ReplacementPreviewDocument? = null,
    onClose: () -> Unit,
) {
    val service = remember { TextReplacementService() }
    val coroutine = rememberCoroutineScope()
    val androidContext = LocalContext.current
    val records by remember { service.repository.observe() }.collectAsState(emptyList())
    var context by remember(mangaId, chapterId) { mutableStateOf<TextReplacementContext?>(null) }
    var scope by remember {
        mutableStateOf(
            if (chapterId !=
                null
            ) {
                ReplacementScope.BOOK
            } else if (mangaId != null) {
                ReplacementScope.SERIES
            } else {
                ReplacementScope.GLOBAL
            },
        )
    }
    var editor by remember { mutableStateOf<ReplacementRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<ReplacementImport?>(null) }
    var importGroup by remember { mutableStateOf(0) }
    var overwrite by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var targetTitle by remember { mutableStateOf("") }
    var importSettings by remember { mutableStateOf(true) }
    var importSample by remember { mutableStateOf(sample) }
    var importPreview by remember { mutableStateOf("") }
    val global = remember { Injekt.get<SharedAppPreferences>().epubReaderPreferences().textReplacementEnabled }
    val enabled by global.collectPreferenceAsState()
    val failureText = stringResource(MR.strings.replacement_error)
    LaunchedEffect(mangaId, chapterId) {
        try {
            context = withContext(Dispatchers.IO) {
                if (mangaId == null) {
                    TextReplacementContext()
                } else {
                    service.context(
                        mangaId,
                        chapterId,
                    )
                }
            }
            targetTitle = withContext(Dispatchers.IO) {
                listOfNotNull(
                    mangaId?.let {
                        Injekt.get<tachiyomi.domain.manga.interactor.GetManga>().await(it)?.title
                    },
                    chapterId?.let {
                        Injekt.get<tachiyomi.domain.chapter.interactor.GetChapter>().await(it)?.name
                    },
                ).joinToString(" / ")
            }
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            error = failureText
        }
    }
    val states = context?.let { service.states(it, records) }.orEmpty()
    val state = states[scope] ?: ReplacementScopeState()
    fun save(next: ReplacementScopeState) {
        if (busy) return
        val owner = context ?: return
        val target = scope
        busy = true
        coroutine.launch {
            try {
                // Re-resolve ownership before accepting a potentially stale editor.
                if (mangaId != null) check(service.context(mangaId, chapterId) == owner)
                service.repository.save(listOf(StoredReplacementScope(owner.key(target), target, owner, next)))
                error = null
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                error = failureText
            } finally {
                busy = false
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutine.launch {
                try {
                    importing = withContext(Dispatchers.IO) {
                        val bytes = checkNotNull(androidContext.contentResolver.openInputStream(uri)).use {
                            it.readNBytes(2 * 1024 * 1024 + 1)
                        }
                        require(bytes.size <= 2 * 1024 * 1024)
                        ReplacementRuleFiles.decode(bytes.toString(Charsets.UTF_8))
                    }
                    importGroup = 0
                    overwrite = emptySet()
                    importSettings = importing?.legado != true
                    importPreview = ""
                } catch (exception: Exception) {
                    if (exception is kotlinx.coroutines.CancellationException) throw exception
                    error = failureText
                }
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val contents = ReplacementRuleFiles.encode(listOf(ReplacementRuleGroup(scope, state)))
            coroutine.launch {
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(androidContext.contentResolver.openOutputStream(uri)).use {
                            it.write(contents.toByteArray())
                        }
                    }
                } catch (exception: Exception) {
                    if (exception is kotlinx.coroutines.CancellationException) throw exception
                    error = failureText
                }
            }
        }
    }
    var choosingScope by remember { mutableStateOf(false) }
    val editable = context != null && !busy
    val effective = EffectiveRuleSet.resolve(states, enabled).rules
    val unbound = records.filter { it.scope != ReplacementScope.GLOBAL && it.context.connectionId == 0L }
    PreferenceScaffold(
        titleRes = MR.strings.replacement_title,
        onBackPressed = onClose,
        actions = {
            RuleFileActions(
                enabled = editable,
                onImport = {
                    importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                },
                onExport = { exportFile.launch("koharia-text-replacement.json") },
            )
        },
        itemsProvider = {
            buildList {
                add(togglePreference(stringResource(MR.strings.replacement_master), enabled) { global.set(it) })
                if (states.size > 1) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.replacement_scope),
                            subtitle = scopeLabel(scope),
                            enabled = editable,
                            onClick = { choosingScope = true },
                        ),
                    )
                }
                if (scope != ReplacementScope.GLOBAL) {
                    add(
                        Preference.PreferenceItem.InfoPreference(
                            title = stringResource(MR.strings.replacement_target, targetTitle),
                        ),
                    )
                }
                if (scope == ReplacementScope.BOOK) {
                    add(
                        togglePreference(
                            stringResource(MR.strings.replacement_book_enabled),
                            state.enabled,
                            editable,
                        ) { save(state.copy(enabled = it)) },
                    )
                }
                if (scope != ReplacementScope.GLOBAL) {
                    add(
                        togglePreference(
                            stringResource(MR.strings.replacement_inherit),
                            state.inherit,
                            editable,
                        ) { save(state.copy(inherit = it)) },
                    )
                }
                error?.let { add(Preference.PreferenceItem.InfoPreference(it)) }
                if (context == null || busy) {
                    add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.replacement_loading)))
                }
                add(
                    Preference.PreferenceGroup(
                        title = stringResource(MR.strings.replacement_rules, scopeLabel(scope)),
                        preferenceItems = buildList {
                            add(
                                Preference.PreferenceItem.CustomPreference(
                                    title = stringResource(MR.strings.replacement_add),
                                ) {
                                    AddRuleButton(editable) { editor = ReplacementRule(pattern = "") }
                                },
                            )
                            if (state.rules.isEmpty()) {
                                add(
                                    Preference.PreferenceItem.InfoPreference(
                                        stringResource(MR.strings.replacement_empty),
                                    ),
                                )
                            }
                            state.rules.forEachIndexed { index, rule ->
                                add(
                                    Preference.PreferenceItem.CustomPreference(
                                        title = rule.name.ifBlank {
                                            rule.pattern
                                        },
                                    ) {
                                        RulePreference(
                                            rule = rule,
                                            scope = scope,
                                            index = index,
                                            state = state,
                                            editable = editable,
                                            onEdit = { editor = rule },
                                            onSave = ::save,
                                        )
                                    },
                                )
                            }
                        }.toImmutableList(),
                    ),
                )
                if (scope != ReplacementScope.GLOBAL) {
                    val parents = EffectiveRuleSet.resolve(states.filterKeys { it.ordinal < scope.ordinal }).rules
                    if (parents.isNotEmpty()) {
                        add(
                            Preference.PreferenceGroup(
                                title = stringResource(MR.strings.replacement_inherited),
                                preferenceItems = parents.map { inherited ->
                                    val reason = when {
                                        !state.inherit -> stringResource(MR.strings.replacement_inheritance_off)
                                        inherited.rule.key in state.excludedKeys -> stringResource(
                                            MR.strings.replacement_disabled_here,
                                        )
                                        effective.any {
                                            it.rule.key == inherited.rule.key &&
                                                it.rule.id != inherited.rule.id
                                        } ->
                                            stringResource(MR.strings.replacement_shadowed)
                                        else -> null
                                    }
                                    togglePreference(
                                        title = inherited.rule.name.ifBlank { inherited.rule.pattern },
                                        checked = inherited.rule.key !in state.excludedKeys,
                                        enabled = editable,
                                        subtitle = listOfNotNull(
                                            scopeLabel(inherited.scope),
                                            reason,
                                        ).joinToString(" · "),
                                    ) { include ->
                                        save(
                                            state.copy(
                                                excludedKeys = if (include) {
                                                    state.excludedKeys - inherited.rule.key
                                                } else {
                                                    state.excludedKeys + inherited.rule.key
                                                },
                                            ),
                                        )
                                    }
                                }.toImmutableList(),
                            ),
                        )
                    }
                }
                if (unbound.isNotEmpty()) {
                    add(
                        Preference.PreferenceGroup(
                            title = stringResource(MR.strings.replacement_rule_files),
                            preferenceItems = unbound.map { recovered ->
                                Preference.PreferenceItem.TextPreference(
                                    title = stringResource(MR.strings.replacement_unbound),
                                    enabled = editable,
                                    onClick = {
                                        importing = ReplacementImport(
                                            listOf(ReplacementRuleGroup(recovered.scope, recovered.state)),
                                            false,
                                        )
                                        importGroup = 0
                                        overwrite = emptySet()
                                        importSettings = true
                                    },
                                )
                            }.toImmutableList(),
                        ),
                    )
                }
                add(
                    Preference.PreferenceGroup(
                        title = stringResource(MR.strings.replacement_effective),
                        preferenceItems = if (effective.isEmpty()) {
                            listOf(
                                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.replacement_empty)),
                            ).toImmutableList()
                        } else {
                            effective.map {
                                Preference.PreferenceItem.TextPreference(
                                    title = it.rule.name.ifBlank { it.rule.pattern },
                                    subtitle =
                                    scopeLabel(it.scope) + " · " + it.rule.pattern + " → " + it.rule.replacement,
                                )
                            }.toImmutableList()
                        },
                    ),
                )
            }
        },
    )
    if (choosingScope) {
        AlertDialog(
            onDismissRequest = { choosingScope = false },
            title = { Text(stringResource(MR.strings.replacement_scope)) },
            text = {
                Column {
                    states.keys.toList().reversed().forEach { option ->
                        TextPreferenceWidget(
                            title = scopeLabel(option),
                            widget = { if (option == scope) Icon(Icons.Filled.Check, null) },
                            onPreferenceClick = {
                                scope = option
                                importing = null
                                choosingScope = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingScope = false }) { Text(stringResource(MR.strings.replacement_cancel)) }
            },
        )
    }
    editor?.let { rule ->
        RuleEditor(rule, sample, previewDocument, states, scope, enabled, onDismiss = { editor = null }) { updated ->
            if (state.rules.any { it.id != updated.id && it.key == updated.key }) {
                error = failureText
            } else {
                save(
                    state.copy(
                        rules = if (state.rules.any {
                                it.id == updated.id
                            }
                        ) {
                            state.rules.map {
                                if (it.id == updated.id) updated else it
                            }
                        } else {
                            state.rules + updated
                        },
                    ),
                )
                editor = null
            }
        }
    }
    importing?.let { imported ->
        val group = imported.groups.getOrNull(importGroup)
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text(stringResource(MR.strings.replacement_import_preview)) },
            text = {
                LazyColumn {
                    item {
                        Text(scopeLabel(scope))
                        if (scope != ReplacementScope.GLOBAL) {
                            Text(
                                stringResource(
                                    MR.strings.replacement_target,
                                    targetTitle,
                                ),
                            )
                        }
                        if (imported.legado) Text(stringResource(MR.strings.replacement_legado_warning))
                        Row {
                            imported.groups.forEachIndexed {
                                    i,
                                    entry,
                                ->
                                TextButton(onClick = {
                                    importGroup = i
                                    overwrite = emptySet()
                                }) {
                                    Text(scopeLabel(entry.scope))
                                }
                            }
                        }
                        if (!imported.legado) {
                            Toggle(
                                stringResource(MR.strings.replacement_import_settings),
                                importSettings,
                            ) {
                                importSettings = it
                            }
                        }
                        OutlinedTextField(
                            importSample,
                            {
                                importSample = it
                            },
                            label = {
                                Text(stringResource(MR.strings.replacement_sample))
                            },
                            maxLines = 4,
                        )
                        TextButton(onClick = {
                            val targetState = group?.state ?: return@TextButton
                            val input = importSample
                            coroutine.launch {
                                importPreview = withContext(Dispatchers.Default) {
                                    runCatching {
                                        val merged = ReplacementRuleFiles.merge(
                                            state,
                                            targetState,
                                            overwrite,
                                            importSettings,
                                        )
                                        val effective = EffectiveRuleSet.resolve(states + (scope to merged), enabled)
                                        if (input == sample && previewDocument != null) {
                                            previewDocument.preview(effective).text
                                        } else {
                                            TextReplacementEngine(effective).replace(input).text
                                        }
                                    }.getOrElse { failureText }
                                }
                            }
                        }) { Text(stringResource(MR.strings.replacement_preview)) }
                        Text(importPreview)
                    }
                    itemsIndexed(group?.state?.rules.orEmpty()) { _, rule ->
                        val conflict = state.rules.any { it.id == rule.id || it.key == rule.key }
                        Text(rule.name.ifBlank { rule.pattern } + " → " + rule.replacement)
                        if (rule.issue !=
                            null
                        ) {
                            Text(stringResource(MR.strings.replacement_needs_review) + ": " + rule.issue)
                        }
                        if (conflict) {
                            Toggle(
                                stringResource(MR.strings.replacement_overwrite),
                                rule.id in overwrite,
                            ) {
                                overwrite = if (it) overwrite + rule.id else overwrite - rule.id
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = group != null && !busy, onClick = {
                    val incoming = checkNotNull(group).state
                    save(ReplacementRuleFiles.merge(state, incoming, overwrite, importSettings))
                    importing = null
                }) { Text(stringResource(MR.strings.replacement_import)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    importing = null
                }) {
                    Text(stringResource(MR.strings.replacement_cancel))
                }
            },

        )
    }
}

@Composable
private fun AddRuleButton(enabled: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.strings.replacement_add))
        }
    }
}

@Composable
private fun RuleFileActions(enabled: Boolean, onImport: () -> Unit, onExport: () -> Unit) {
    IconButton(onClick = onImport, enabled = enabled) {
        Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(MR.strings.replacement_import))
    }
    IconButton(onClick = onExport, enabled = enabled) {
        Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(MR.strings.replacement_export))
    }
}

@Composable
private fun RulePreference(
    rule: ReplacementRule,
    scope: ReplacementScope,
    index: Int,
    state: ReplacementScopeState,
    editable: Boolean,
    onEdit: () -> Unit,
    onSave: (ReplacementScopeState) -> Unit,
) {
    Column {
        Toggle(
            label = rule.name.ifBlank { rule.pattern },
            checked = rule.enabled,
            enabled = editable,
            subtitle = buildString {
                append(scopeLabel(scope)).append(" · ")
                append(rule.pattern).append(" → ").append(rule.replacement)
                if (rule.issue != null) {
                    append("\n").append(
                        stringResource(MR.strings.replacement_needs_review),
                    )
                }
            },
        ) { checked ->
            if (checked && rule.issue != null) {
                onEdit()
            } else {
                onSave(
                    state.copy(
                        rules = state.rules.map {
                            if (it.id == rule.id) it.copy(enabled = checked) else it
                        },
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onEdit() }, enabled = editable) {
                Text(stringResource(MR.strings.replacement_edit))
            }
            IconButton(enabled = editable && index > 0, onClick = {
                val rules = state.rules.toMutableList()
                java.util.Collections.swap(rules, index, index - 1)
                onSave(state.copy(rules = rules))
            }) {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    stringResource(MR.strings.replacement_up),
                )
            }
            IconButton(enabled = editable && index < state.rules.lastIndex, onClick = {
                val rules = state.rules.toMutableList()
                java.util.Collections.swap(rules, index, index + 1)
                onSave(state.copy(rules = rules))
            }) {
                Icon(
                    Icons.Outlined.ArrowDownward,
                    stringResource(MR.strings.replacement_down),
                )
            }
            IconButton(enabled = editable, onClick = {
                onSave(state.copy(rules = state.rules.filterNot { it.id == rule.id }))
            }) {
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(MR.strings.replacement_delete),
                )
            }
        }
    }
}

private fun togglePreference(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) = Preference.PreferenceItem.CustomPreference(title) {
    Toggle(title, checked, enabled, subtitle, onChange)
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    SwitchPreferenceWidget(
        title = label,
        subtitle = subtitle,
        checked = checked,
        enabled = enabled,
        onCheckedChanged = onChange,
    )
}

@Composable
private fun RuleEditor(
    original: ReplacementRule,
    chapterSample: String,
    previewDocument: ReplacementPreviewDocument?,
    states: Map<
        ReplacementScope,
        ReplacementScopeState,
        >,
    scope: ReplacementScope,
    masterEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (ReplacementRule) -> Unit,
) {
    var rule by remember(original) { mutableStateOf(original) }
    var sample by remember { mutableStateOf(chapterSample) }
    var preview by remember { mutableStateOf("") }
    LaunchedEffect(chapterSample) { if (sample.isEmpty()) sample = chapterSample }
    var invalid by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    val resultLabel = stringResource(MR.strings.replacement_matches)
    val invalidLabel = stringResource(MR.strings.replacement_invalid)
    val shadowedLabel = stringResource(MR.strings.replacement_shadowed)
    val overlapLabel = stringResource(MR.strings.replacement_overlap)
    val inactiveLabel = stringResource(MR.strings.replacement_inactive)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.replacement_edit_scoped, scopeLabel(scope))) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        rule.name,
                        {
                            rule = rule.copy(name = it)
                        },
                        label = {
                            Text(stringResource(MR.strings.replacement_name))
                        },
                    )
                }
                item {
                    OutlinedTextField(
                        rule.pattern,
                        {
                            rule = rule.copy(pattern = it)
                        },
                        label = {
                            Text(stringResource(MR.strings.replacement_find))
                        },
                    )
                }
                item {
                    OutlinedTextField(
                        rule.replacement,
                        {
                            rule = rule.copy(replacement = it)
                        },
                        label = {
                            Text(stringResource(MR.strings.replacement_with))
                        },
                    )
                }
                item {
                    Toggle(stringResource(MR.strings.replacement_regex), rule.regex) { rule = rule.copy(regex = it) }
                    Toggle(
                        stringResource(MR.strings.replacement_ignore_case),
                        rule.ignoreCase,
                    ) {
                        rule = rule.copy(ignoreCase = it)
                    }
                    Toggle(
                        stringResource(MR.strings.replacement_enabled),
                        rule.enabled,
                    ) {
                        rule = rule.copy(enabled = it)
                    }
                    if (rule.regex) Text(stringResource(MR.strings.replacement_regex_help))
                    OutlinedTextField(
                        sample,
                        {
                            sample = it
                        },
                        label = {
                            Text(stringResource(MR.strings.replacement_sample))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5,
                    )
                    TextButton(onClick = {
                        val candidate = rule.copy(issue = null)
                        val input = sample
                        coroutine.launch {
                            preview = withContext(Dispatchers.Default) {
                                runCatching {
                                    val own = states[scope] ?: ReplacementScopeState()
                                    require(own.rules.none { it.id != candidate.id && it.key == candidate.key })
                                    val testState = own.copy(
                                        rules = if (own.rules.any { it.id == candidate.id }) {
                                            own.rules.map { if (it.id == candidate.id) candidate else it }
                                        } else {
                                            own.rules + candidate
                                        },
                                    )
                                    val effective = EffectiveRuleSet.resolve(
                                        states + (scope to testState),
                                        masterEnabled,
                                    )
                                    val result = if (input == chapterSample && previewDocument != null) {
                                        previewDocument.preview(effective)
                                    } else {
                                        TextReplacementEngine(effective).replace(input)
                                    }
                                    val ownRules = EffectiveRuleSet(
                                        listOf(
                                            koharia.domain.textreplacement.EffectiveReplacementRule(candidate, scope),
                                        ),
                                    )
                                    val possible = if (input == chapterSample && previewDocument != null) {
                                        previewDocument.preview(ownRules)
                                    } else {
                                        TextReplacementEngine(ownRules).replace(input)
                                    }
                                    val accepted = result.edits.count { it.ruleId == candidate.id }
                                    val reason = when {
                                        effective.rules.none { it.rule.id == candidate.id } -> {
                                            if (effective.rules.any {
                                                    it.rule.key == candidate.key
                                                }
                                            ) {
                                                shadowedLabel
                                            } else {
                                                inactiveLabel
                                            }
                                        }
                                        accepted < possible.edits.size -> overlapLabel
                                        else -> ""
                                    }
                                    "$resultLabel: $accepted / ${possible.edits.size}\n$reason\n${result.text}"
                                }.getOrElse { invalidLabel }
                            }
                        }
                    }) { Text(stringResource(MR.strings.replacement_preview)) }
                    Text(preview)
                    if (invalid) Text(invalidLabel)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                invalid = TextReplacementEngine.validate(rule) != null
                if (!invalid) onSave(rule.copy(issue = null))
            }) {
                Text(stringResource(MR.strings.replacement_save))
            }
        },

        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.replacement_cancel)) } },
    )
}
