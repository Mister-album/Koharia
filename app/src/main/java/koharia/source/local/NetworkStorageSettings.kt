package koharia.source.local

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import koharia.storage.LibraryStorageBackend
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.SmbAddress
import koharia.storage.StorageEntry
import koharia.storage.StorageFailure
import koharia.storage.StorageIdentity
import koharia.storage.StoragePath
import koharia.storage.StoragePendingProgress
import koharia.storage.WebDavAddress
import koharia.storage.authenticateSmbServer
import koharia.storage.authenticateWebDavServer
import koharia.storage.browseSmbServer
import koharia.storage.browseStorageDirectories
import koharia.storage.browseWebDavServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

@kotlinx.serialization.Serializable
internal data class NetworkStorageDraft(
    val configuration: NetworkStorageConfiguration,
    val username: String,
    val password: String,
) {
    suspend fun authenticate() {
        for (address in listOf(configuration.address, configuration.internalAddress).filter { it.isNotBlank() }) {
            koharia.connection.ConnectionValidation.at(
                if (address == configuration.address) {
                    koharia.connection.ConnectionValidation.Endpoint.PUBLIC
                } else {
                    koharia.connection.ConnectionValidation.Endpoint.INTERNAL
                },
            ) {
                if (configuration.mode == LibraryStorageMode.SMB) {
                    authenticateSmbServer(address, username, password, configuration.domain.trim())
                } else {
                    authenticateWebDavServer(address, username, password)
                }
            }
        }
        require(configuration.address.isNotBlank())
    }

    fun withRoot(path: String): NetworkStorageDraft {
        val normalized = StoragePath.normalize(path)
        fun resolve(address: String): String {
            if (address.isBlank()) return ""
            return when (configuration.mode) {
                LibraryStorageMode.SMB -> SmbAddress.parse(address).at(normalized)
                LibraryStorageMode.WEBDAV -> WebDavAddress.parse(address).at(normalized)
                LibraryStorageMode.LOCAL -> address
            }
        }
        return copy(
            configuration = configuration.copy(
                address = resolve(configuration.address),
                internalAddress = resolve(configuration.internalAddress),
            ),
        )
    }

    /** Setup step two only needs a server endpoint: the library folder is chosen in a later step. */
    val endpointValid: Boolean
        get() = when (configuration.mode) {
            LibraryStorageMode.LOCAL -> true
            LibraryStorageMode.SMB ->
                runCatching { SmbAddress.parse(configuration.address).server.isNotBlank() }.getOrDefault(false)
            LibraryStorageMode.WEBDAV ->
                runCatching { WebDavAddress.parse(configuration.address).endpoint.isNotBlank() }.getOrDefault(false)
        }
    val valid: Boolean
        get() = when (configuration.mode) {
            LibraryStorageMode.LOCAL -> true
            LibraryStorageMode.SMB ->
                runCatching { SmbAddress.parse(configuration.address).root.isNotEmpty() }.getOrDefault(false)
            LibraryStorageMode.WEBDAV -> endpointValid
        }

    fun resolvedConfiguration(): NetworkStorageConfiguration = when (configuration.mode) {
        LibraryStorageMode.LOCAL -> configuration
        LibraryStorageMode.SMB -> {
            val primary = SmbAddress.parse(configuration.address)
            val internal = configuration.internalAddress.takeIf { it.isNotBlank() }?.let(SmbAddress::parse)
            configuration.copy(
                address = primary.at(primary.root),
                internalAddress = internal?.at(internal.root.ifEmpty { primary.root }).orEmpty(),
            )
        }
        LibraryStorageMode.WEBDAV -> {
            // The primary address is kept verbatim so that re-saving an unchanged connection cannot
            // change the stored string (and the identity check that compares against it).
            val primary = WebDavAddress.parse(configuration.address)
            val internal = configuration.internalAddress.takeIf { it.isNotBlank() }?.let(WebDavAddress::parse)
            configuration.copy(
                internalAddress = internal?.at(internal.root.ifEmpty { primary.root }).orEmpty(),
            )
        }
    }
    suspend fun probe(path: String = "") {
        val resolved = resolvedConfiguration()
        NetworkStorageRuntime.backend(resolved, resolved.address.trim(), username, password).use {
            check(it.stat(path).directory)
        }
    }
    suspend fun directories(path: String): List<StorageEntry> = withContext(Dispatchers.IO) {
        val resolved = resolvedConfiguration()
        NetworkStorageRuntime.backend(resolved, resolved.address.trim(), username, password).use {
            browseStorageDirectories(it, path)
        }
    }

    private suspend fun <T> directoryBackend(
        path: String,
        serverLevel: Boolean,
        action: suspend (LibraryStorageBackend, String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val normalized = StoragePath.normalize(path)
        val smbServer = serverLevel && configuration.mode == LibraryStorageMode.SMB
        val webDavServer = serverLevel && configuration.mode == LibraryStorageMode.WEBDAV
        if (smbServer) require(normalized.isNotEmpty())
        val resolved = when {
            smbServer -> configuration.copy(
                address = SmbAddress.parse(configuration.address).at(normalized.substringBefore('/')),
            )
            webDavServer -> configuration.copy(
                address = WebDavAddress.parse(configuration.address).at(""),
            )
            else -> resolvedConfiguration()
        }
        val relative = if (smbServer) normalized.substringAfter('/', "") else normalized
        NetworkStorageRuntime.backend(resolved, resolved.address.trim(), username, password).use {
            action(it, relative)
        }
    }

    suspend fun canCreateDirectory(path: String, serverLevel: Boolean = false): Boolean? {
        if (serverLevel && configuration.mode == LibraryStorageMode.SMB && path.isEmpty()) return false
        return directoryBackend(path, serverLevel) { backend, relative ->
            if (!backend.capabilities.writable) false else backend.directoryCreationAllowed(relative)
        }
    }

    suspend fun createDirectory(parent: String, name: String, serverLevel: Boolean = false) =
        directoryBackend(parent, serverLevel) { backend, relative -> createStorageDirectory(backend, relative, name) }
}

@Composable
internal fun NetworkStorageConnectionFields(
    draft: NetworkStorageDraft,
    enabled: Boolean,
    showRootPicker: Boolean = true,
    onChange: (NetworkStorageDraft) -> Unit,
) {
    val config = draft.configuration
    val smb = config.mode == LibraryStorageMode.SMB
    val smbAddress = if (smb) runCatching { SmbAddress.parse(config.address) }.getOrNull() else null
    val webDavAddress = if (smb) null else WebDavAddress.of(config.address)
    val webDavAddressRejected = config.mode == LibraryStorageMode.WEBDAV &&
        config.address.isNotBlank() && webDavAddress == null
    var choosingRoot by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    Column {
        PreferenceGroupHeader(title = if (config.mode == LibraryStorageMode.SMB) "SMB" else "WebDAV")
        OutlinedTextField(
            value = if (smbAddress?.root?.isNotEmpty() == true) smbAddress.server else config.address,
            onValueChange = { onChange(draft.copy(configuration = config.copy(address = it))) },
            label = {
                Text(stringResource(if (smb) MR.strings.storage_smb_server else MR.strings.storage_primary_address))
            },
            singleLine = true,
            enabled = enabled,
            isError = webDavAddressRejected,
            supportingText = if (webDavAddressRejected) {
                { Text(stringResource(MR.strings.storage_webdav_address_invalid)) }
            } else {
                null
            },
            modifier = Modifier.testTag(
                "storage-primary-address",
            ).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TextButton(
            onClick = { advancedExpanded = !advancedExpanded },
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.padding(horizontal = 16.dp).testTag("storage-advanced"),
        ) { Text(stringResource(MR.strings.connection_address_advanced)) }
        if (advancedExpanded) {
            OutlinedTextField(
                value = config.internalAddress,
                onValueChange = {
                    onChange(draft.copy(configuration = config.copy(internalAddress = it)))
                },
                label = { Text(stringResource(MR.strings.storage_internal_address)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.testTag(
                    "storage-internal-address",
                ).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SetupNotice(
            text = stringResource(
                if (config.mode ==
                    LibraryStorageMode.SMB
                ) {
                    MR.strings.storage_smb_address_help
                } else {
                    MR.strings.storage_webdav_address_help
                },
            ),
        )
        OutlinedTextField(
            value = draft.username,
            onValueChange = { onChange(draft.copy(username = it)) },
            label = { Text(stringResource(MR.strings.username)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.testTag("storage-username").fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = draft.password,
            onValueChange = { onChange(draft.copy(password = it)) },
            label = { Text(stringResource(MR.strings.password)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.testTag("storage-password").fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (config.mode == LibraryStorageMode.SMB) {
            OutlinedTextField(
                value = config.domain,
                onValueChange = { onChange(draft.copy(configuration = config.copy(domain = it))) },
                label = { Text(stringResource(MR.strings.storage_smb_domain)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (showRootPicker) {
            TextPreferenceWidget(
                title = stringResource(
                    if (smb) MR.strings.storage_smb_choose_share else MR.strings.storage_webdav_choose_folder,
                ),
                subtitle = if (smb) {
                    smbAddress?.root?.takeIf { it.isNotEmpty() }
                        ?: stringResource(MR.strings.storage_smb_no_share)
                } else {
                    webDavAddress?.root?.takeIf { it.isNotEmpty() }
                        ?: stringResource(MR.strings.storage_webdav_no_folder)
                },
                enabled = enabled && (if (smb) smbAddress != null else webDavAddress != null),
                onPreferenceClick = { choosingRoot = true },
            )
        }
    }
    if (choosingRoot) {
        NetworkStorageDirectoryDialog(
            // SMB keeps listing shares so a different share can be picked; WebDAV starts inside the
            // folder the address currently points at and can still walk up to the endpoint root.
            initialPath = if (smb) "" else webDavAddress?.root.orEmpty(),
            draft = draft,
            onDismiss = { choosingRoot = false },
            onConfirm = { path ->
                val address = if (smb) {
                    requireNotNull(smbAddress).at(path)
                } else {
                    requireNotNull(webDavAddress).at(path)
                }
                onChange(draft.copy(configuration = config.copy(address = address)))
                choosingRoot = false
            },
            loadDirectories = { path ->
                if (smb) {
                    browseSmbServer(config.address, draft.username, draft.password, config.domain, path)
                } else {
                    browseWebDavServer(config.address, draft.username, draft.password, path)
                }
            },
            allowRootSelection = !smb,
            createDirectory = { parent, name -> draft.createDirectory(parent, name, serverLevel = true) },
            creationAllowed = { path -> draft.canCreateDirectory(path, serverLevel = true) },
        )
    }
}

/** Compares the effective endpoint and folder, so a trailing slash is not treated as a different root. */
internal fun sameNetworkRoot(mode: LibraryStorageMode, left: String, right: String): Boolean = runCatching {
    when (mode) {
        LibraryStorageMode.SMB -> SmbAddress.parse(left) == SmbAddress.parse(right)
        LibraryStorageMode.WEBDAV -> WebDavAddress.parse(left) == WebDavAddress.parse(right)
        LibraryStorageMode.LOCAL -> left == right
    }
}.getOrDefault(false)

internal suspend fun saveNetworkLibraryDraft(
    context: Context,
    sourceId: Long,
    draft: NetworkStorageDraft,
    initial: NetworkStorageConfiguration,
    library: LocalLibraryConfig,
    assignments: Map<String, String>,
    isNew: Boolean,
): LocalLibraryConfig {
    val preferences = NetworkStoragePreferences(sourceId)
    val resolved = draft.resolvedConfiguration()
    val config = resolved.copy(
        address = resolved.address.trim(),
        internalAddress = resolved.internalAddress.trim(),
        domain = draft.configuration.domain.trim(),
    )
    require(isNew || config.mode == initial.mode)
    if (!isNew && !initial.persistentIdentity &&
        !sameNetworkRoot(config.mode, config.address, initial.address)
    ) {
        throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
    }
    var enabled = if (library.setupCompleted) library else library.enabledLibraryConfiguration()
    draft.authenticate()
    val verified = NetworkStorageRuntime.backend(
        config,
        config.address,
        draft.username,
        draft.password,
        validation = true,
    ).use { primary ->
        koharia.connection.ConnectionValidation.at(koharia.connection.ConnectionValidation.Endpoint.PUBLIC) {
            var persistent = true
            val identity = try {
                StorageIdentity.ensure(primary)
            } catch (failure: StorageFailure) {
                if (failure.reason !in
                    setOf(StorageFailure.Reason.PERMISSION, StorageFailure.Reason.UNSUPPORTED)
                ) {
                    throw failure
                }
                check(primary.stat("").directory)
                persistent = false
                initial.rootIdentity.takeIf { !initial.persistentIdentity && it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
            }
            enabled = prepareInitialNetworkDirectories(primary, enabled)
            require(enabled.roots.isNotEmpty())
            enabled.roots.forEach { check(primary.stat(it.relativePath).directory) }
            if (persistent &&
                config.internalAddress.isNotBlank()
            ) {
                NetworkStorageRuntime.backend(
                    config,
                    config.internalAddress,
                    draft.username,
                    draft.password,
                    validation = true,
                ).use {
                    koharia.connection.ConnectionValidation.at(
                        koharia.connection.ConnectionValidation.Endpoint.INTERNAL,
                    ) {
                        StorageIdentity.verify(primary, it, identity)
                    }
                }
            }
            config.copy(
                rootIdentity = identity,
                persistentIdentity = persistent,
                verifiedInternal =
                persistent && config.internalAddress.isNotBlank(),
                needsValidation = false,
                accountIdentity = "",
            )
        }
    }
    if (!isNew &&
        (
            verified.rootIdentity != initial.rootIdentity ||
                NetworkStoragePreferences.account(verified, draft.username) != preferences.account
            )
    ) {
        throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
    }
    Injekt.get<LocalLibraryRefreshTasks>().cancel(sourceId)
    preferences.save(verified, draft.username, draft.password)
    NetworkStorageRuntime.invalidate(sourceId)
    val runtime = NetworkStorageRuntime.get(context, sourceId)
    runtime.records.list("scans").forEach { runtime.records.remove("scans", it.key, it.revision) }
    val saved = enabled.copy(
        libraryId = verified.rootIdentity,
        setupCompleted = true,
        roots = enabled.roots.map { it.copy(treeUri = runtime.uri("").toString()) },
    )
    LocalLibraryPreferences(sourceId, Injekt.get<Json>()).saveLibraryDraft(
        saved,
        assignments.filterValues {
            saved.bookshelf(it) !=
                null
        },
    )
    return saved
}

@Composable
internal fun NetworkStoragePendingSettings(sourceId: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by remember(sourceId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var busy by remember(sourceId) { mutableStateOf(false) }
    var failed by remember(sourceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        try {
            pending = withContext(Dispatchers.IO) {
                val runtime = NetworkStorageRuntime.get(context, sourceId)
                runtime.records.list("progress").count {
                    runtime.records.json.decodeFromString<StoragePendingProgress>(it.payload).pending
                } to runtime.records.list("mutations").size
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            pending = null
            failed = true
        }
    }
    LaunchedEffect(sourceId) { load() }
    Column {
        pending?.let {
            TextPreferenceWidget(title = stringResource(MR.strings.storage_pending_state, it.first, it.second))
        }
        TextPreferenceWidget(
            title = stringResource(
                MR.strings.storage_retry_pending,
            ),
            enabled = !busy,
            onPreferenceClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val runtime = NetworkStorageRuntime.get(context, sourceId)
                            runtime.mutations.reconcile()
                            runtime.progress.flush()
                        }
                        failed = false
                    } catch (
                        error: CancellationException,
                    ) {
                        throw error
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                        load()
                    }
                }
            },
        )
        if (busy) EInkLinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) TextPreferenceWidget(title = stringResource(MR.strings.storage_operation_failed))
    }
}
