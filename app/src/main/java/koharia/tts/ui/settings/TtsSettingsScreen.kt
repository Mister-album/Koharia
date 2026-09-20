package koharia.tts.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.util.LocalBackPress
import koharia.tts.TtsPreferences
import koharia.tts.TtsSecurePreferences
import koharia.tts.TtsVendor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Phase 4:TTS 引擎配置页(vendor 选择 + API key 输入)。
 *
 * 入口:
 *  - [koharia.epub.settings.EpubReaderSettingsSheet] 在朗读设置底部放一个
 *    「TTS 引擎设置」TextButton,点击 push 此 Screen。
 *
 * UX:
 *  - 顶部:vendor 单选列表(MiMo 需 key / Edge 免 key)
 *  - 选中 vendor 后:
 *    - 需要 key → API key 输入框(密码遮罩)+ 「获取 key」外链 + 状态文字
 *    - 无需 key → 「免 key 厂商」说明文字
 *  - 底部:音色信息(指向阅读设置)
 *
 * **保存策略**:输入框 onValueChange 时 debounce 500ms 后写入 secure prefs;
 * 切换 vendor 立即写入 vendorId + 必要时 reset voiceId。
 */
object TtsSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val handleBack = LocalBackPress.current
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val ttsPreferences = remember { Injekt.get<TtsPreferences>() }
        val securePrefs = remember { Injekt.get<TtsSecurePreferences>() }

        var selectedVendorId by remember { mutableStateOf(ttsPreferences.vendorId.get()) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.tts_engine_settings_title)) },
                    navigationIcon = {
                        if (handleBack != null) {
                            IconButton(onClick = handleBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ===== Vendor 选择 =====
                SectionHeader(text = stringResource(MR.strings.tts_engine_section))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.selectableGroup()) {
                        TtsVendor.ALL.forEachIndexed { index, vendor ->
                            VendorRow(
                                vendor = vendor,
                                selected = selectedVendorId == vendor.id,
                                onSelect = {
                                    if (selectedVendorId != vendor.id) {
                                        selectedVendorId = vendor.id
                                        ttsPreferences.vendorId.set(vendor.id)
                                        // v0.4.2-63:不需要"voice 非法则 reset" —— 音色已按 vendor
                                        // 分槽存储,各槽位要么是用户选过的合法值,要么未设置
                                        // (= 该 vendor 的默认音色),天然合法。
                                    }
                                },
                            )
                            if (index < TtsVendor.ALL.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }

                // ===== API key / 说明 =====
                val currentVendor = TtsVendor.fromId(selectedVendorId)

                // ===== 数据披露（常驻）=====
                // review 修复：明确写出"章节正文会上传至哪个厂商"，并随上方厂商选择实时更新。
                DataDisclosureCard(vendorDisplayName = currentVendor.displayName)

                if (currentVendor.needsApiKey) {
                    ApiKeySection(
                        vendor = currentVendor,
                        securePrefs = securePrefs,
                    )
                } else {
                    FreeVendorSection()
                }

                // ===== Voice 提示 =====
                // 显示**当前实际生效**的音色。原来这里渲染 currentVendor.defaultVoiceId(),
                // 无论用户选了什么都是 vendor 默认音色,会让人误以为「选了不生效」。
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(MR.strings.tts_engine_voice_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val voices = currentVendor.presetVoices()
                    // v0.4.2-63:音色按 vendor 分槽存储,读"当前 vendor 的槽位"
                    val voicePref = ttsPreferences.voiceIdFor(currentVendor.id)
                    val voiceId by voicePref.changes().collectAsState(voicePref.get())
                    // pref 非法时回退展示默认音色 —— 与 TtsService.observeVoicePreference
                    // 对非法 id 的处理保持一致。
                    val currentVoiceName = voices.firstOrNull { it.id == voiceId }?.name
                        ?: currentVendor.defaultVoiceId()
                    Text(
                        text = "$currentVoiceName  ·  ${currentVendor.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(MR.strings.tts_engine_voice_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    @Composable
    private fun VendorRow(
        vendor: TtsVendor,
        selected: Boolean,
        onSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onSelect,
                    role = Role.RadioButton,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vendor.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (vendor.needsApiKey) {
                        stringResource(MR.strings.tts_engine_vendor_requires_key)
                    } else {
                        stringResource(MR.strings.tts_engine_vendor_free)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun ApiKeySection(
        vendor: TtsVendor,
        securePrefs: TtsSecurePreferences,
    ) {
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()

        var apiKeyInput by remember { mutableStateOf(securePrefs.getApiKey(vendor.id).orEmpty()) }
        var showKey by remember { mutableStateOf(false) }
        var lastSaved by remember { mutableStateOf(apiKeyInput) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 旧版加密存储无法解密 ⟹ 明确告诉用户"要重填"，而不是让他对着"合成失败"猜。
            if (apiKeyInput.isBlank() && securePrefs.needsLegacyKeyReentry(vendor.id)) {
                LegacyKeyNotice()
            }

            Text(
                text = stringResource(MR.strings.tts_engine_api_key_label),
                style = MaterialTheme.typography.titleSmall,
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { newValue ->
                    apiKeyInput = newValue
                    // Debounce 500ms 后写 secure prefs
                    scope.launch {
                        delay(500)
                        if (apiKeyInput == newValue && apiKeyInput != lastSaved) {
                            securePrefs.setApiKey(vendor.id, newValue)
                            lastSaved = newValue
                        }
                    }
                },
                placeholder = { Text(stringResource(MR.strings.tts_engine_api_key_hint)) },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "🙈" else "👁", fontSize = 18.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // 状态 + 获取 key 链接
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val isConfigured = apiKeyInput.isNotBlank()
                Icon(
                    imageVector = if (isConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                    contentDescription = null,
                    tint = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConfigured) {
                        stringResource(MR.strings.tts_engine_status_ok)
                    } else {
                        stringResource(MR.strings.tts_engine_status_missing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )

                Spacer(modifier = Modifier.weight(1f))

                vendor.apiKeyHintUrl?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(MR.strings.tts_engine_get_api_key))
                    }
                }
            }
        }
    }

    /**
     * 旧版加密 key 无法迁移时的常驻提示。
     *
     * 只打日志用户看不到 —— 表现为"key 明明填过却说未配置"，很容易被当成 bug。
     * 用户重填后 [TtsSecurePreferences.needsLegacyKeyReentry] 立即变 false，提示自动消失。
     */
    @Composable
    private fun LegacyKeyNotice() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.tts_legacy_key_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(MR.strings.tts_legacy_key_notice_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }

    @Composable
    private fun FreeVendorSection() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(MR.strings.tts_engine_vendor_free),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    /**
     * 常驻数据披露卡片：写明当前所选厂商是章节正文的数据接收方，以及用途。
     *
     * 与首次启用的一次性 [koharia.epub.control.TtsDisclosureDialog] 互补 —— 这里保证用户
     * 任何时候回到设置页都能看到披露，且切换厂商后接收方名称即时更新。
     */
    @Composable
    private fun DataDisclosureCard(vendorDisplayName: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.tts_data_disclosure_settings_card_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        MR.strings.tts_data_disclosure_settings_card_body,
                        vendorDisplayName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
