package koharia.storage

import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProgressSyncStatus
import koharia.connection.LibraryConnectionProfile
import koharia.connection.ProgressStatuses
import koharia.domain.storage.LibraryStorageRepository
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryLocator
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.NetworkStoragePendingSettings
import koharia.source.local.NetworkStoragePendingState
import koharia.source.local.observeNetworkStoragePendingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class StorageProgressStatusDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = Injekt.get<LibraryStorageRepository>()

    @Test
    fun settingsDisplayDistinguishesHoldsAndRebindsAfterResumeAndConnectionSwitch(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        fixture(LibraryStorageMode.WEBDAV) { first, _, firstStore, _ ->
            fixture(LibraryStorageMode.SMB) { second, _, secondStore, _ ->
                val event = StorageProgressRecord("unindexed", "v1", "writer", 1, 100)
                firstStore.put("progress", event.resource, StoragePendingProgress(event, true, true))
                secondStore.put("progress", event.resource, StoragePendingProgress(event, true))
                val selected = MutableStateFlow(first.id)
                val retryLabel = context.stringResource(MR.strings.storage_retry_pending)
                val heldLabel = context.stringResource(MR.strings.storage_held_state, 1)
                ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            val sourceId by selected.collectAsState()
                            TachiyomiPreviewTheme {
                                Column(Modifier.systemBarsPadding()) { NetworkStoragePendingSettings(sourceId) }
                            }
                        }
                    }
                    awaitUi { findLabel(heldLabel) != null && actionEnabled(retryLabel) == false }
                    awaitUi { findLabel(context.stringResource(MR.strings.storage_pending_state, 0, 0)) != null }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    firstStore.put("progress", event.resource, StoragePendingProgress(event, true))
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    awaitUi {
                        findLabel(heldLabel) == null && actionEnabled(retryLabel) == true &&
                            findLabel(context.stringResource(MR.strings.storage_pending_state, 1, 0)) != null
                    }
                    selected.value = second.id
                    firstStore.put("progress", event.resource, StoragePendingProgress(event, true, true))
                    secondStore.put("progress", event.resource, StoragePendingProgress(event, false))
                    awaitUi {
                        findLabel(heldLabel) == null && actionEnabled(retryLabel) == false &&
                            findLabel(context.stringResource(MR.strings.storage_pending_state, 0, 0)) != null
                    }
                    secondStore.put("progress", event.resource, "corrupt")
                    awaitUi { findLabel(context.stringResource(MR.strings.storage_unavailable_state, 1)) != null }
                    assertTrue(actionEnabled(retryLabel) == false)
                }
                assertFalse(runtimeExists(first.id))
                assertFalse(runtimeExists(second.id))
            }
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        if (!condition()) {
            val labels = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.let { labels += "visible=${node.isVisibleToUser}, enabled=${node.isEnabled}, text=$it" }
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
            throw AssertionError("Storage pending settings did not reach the expected state: $labels")
        }
    }

    private fun findLabel(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun actionEnabled(label: String): Boolean? {
        var node = findLabel(label)
        while (node != null) {
            if (!node.isEnabled) return false
            if (node.isClickable) return true
            node = node.parent
        }
        return null
    }

    @Test
    fun foreignDocumentCannotConstructCurrentOwnersRuntime(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            fixture(mode) { source, _, store, _ ->
                store.put(
                    "progress",
                    "book",
                    StoragePendingProgress(StorageProgressRecord("book", "v1", "writer", 1, 100), true),
                )
                assertTrue(
                    runCatching {
                        NetworkStorageRuntime.fromDocumentId(context, "${source.id}|foreign-account|book.epub")
                    }.exceptionOrNull() is IllegalStateException,
                )
                assertFalse(runtimeExists(source.id))
                assertTrue(store.get<StoragePendingProgress>("progress", "book")!!.pending)
            }
        }
    }

    @Test
    fun runtimeCreationCannotStartAnotherOwnersConnectivityFlush(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            fixture(mode) { source, network, store, _ ->
                val original = network.configuration
                val replacement = original.copy(accountIdentity = "replacement")
                val other = StorageRecordStore(
                    StorageSession(source.id, replacement.accountIdentity, original.rootIdentity) { true },
                    repository,
                    Json,
                )
                other.put(
                    "progress",
                    "book",
                    StoragePendingProgress(StorageProgressRecord("book", "v1", "writer", 1, 100), true),
                )
                var checks = 0
                try {
                    val rejected = runCatching {
                        NetworkStorageRuntime.getForOwner(context, source.id) {
                            if (network.configuration !=
                                original
                            ) {
                                throw kotlinx.coroutines.CancellationException("Obsolete fixture owner")
                            }
                            if (++checks == 1) network.save(replacement, "fixture", "fixture")
                        }
                    }
                    assertTrue(rejected.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                    val field = NetworkStorageRuntime::class.java.getDeclaredField("sessions").apply {
                        isAccessible =
                            true
                    }
                    val runtime = (field.get(null) as Map<*, *>)[source.id] as NetworkStorageRuntime
                    assertTrue(
                        runCatching {
                            runtime.progress.flush()
                        }.exceptionOrNull() is kotlinx.coroutines.CancellationException,
                    )
                    assertTrue(other.get<StoragePendingProgress>("progress", "book")!!.pending)
                    assertTrue(store.list("progress").isEmpty())
                } finally {
                    NetworkStorageRuntime.invalidate(source.id)
                }
            }
        }
    }

    @Test
    fun settingsObserveAllCurrentOwnerRecordsWithoutRuntimeAndNeverReleaseHolds(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            fixture(mode) { source, network, store, _ ->
                val event = StorageProgressRecord("unindexed-book", "v1", "writer", 1, 100)
                val pending = StoragePendingProgress(event, true)
                val held = StoragePendingProgress(event.copy(resource = "held-book"), false, true)
                store.put("progress", event.resource, pending)
                store.put("progress", held.record.resource, held)
                store.put("progress", "broken-book", "corrupt")
                store.put("mutations", "review", "unreadable mutation remains visible")
                repository.put(
                    source.id,
                    "foreign-account",
                    store.session.rootIdentity,
                    "progress",
                    koharia.domain.storage.StorageRecord("foreign", "corrupt", 1),
                )
                repository.put(
                    source.id,
                    store.session.account,
                    "foreign-root",
                    "progress",
                    koharia.domain.storage.StorageRecord("foreign", "corrupt", 1),
                )
                coroutineScope {
                    val updates = Channel<NetworkStoragePendingState?>(Channel.UNLIMITED)
                    val job = launch { observeNetworkStoragePendingState(context, source.id).collect(updates::send) }
                    try {
                        val active = updates.expectSummary(StoragePendingSummary(1, 1, 1, 1))
                        assertTrue(active.retry != null)
                        store.put("progress", event.resource, pending.copy(pending = false))
                        updates.expectSummary(StoragePendingSummary(0, 1, 1, 1))
                        store.remove("mutations", "review", store.list("mutations").single().revision)
                        val heldOnly = updates.expectSummary(StoragePendingSummary(held = 1, unavailable = 1))
                        assertTrue(heldOnly.retry == null)
                        store.put("progress", event.resource, pending)
                        updates.expectSummary(StoragePendingSummary(1, 1, 1))
                        network.save(network.configuration.copy(needsValidation = true), "fixture", "fixture")
                        val unverified = updates.expectSummary(StoragePendingSummary(held = 2, unavailable = 1))
                        assertTrue(unverified.retry == null)
                        assertFalse(unverified.writesAllowed)
                        assertTrue(
                            runCatching {
                                active.retry!!.invoke()
                            }.exceptionOrNull() is kotlinx.coroutines.CancellationException,
                        )
                        network.save(network.configuration.copy(needsValidation = false), "fixture", "fixture")
                        val resumed = updates.expectSummary(StoragePendingSummary(1, 1, 1))
                        val changes = Injekt.get<ConnectionIdentityChanges>()
                        changes.beginConfiguration(source.id)
                        try {
                            val paused = updates.expectSummary(StoragePendingSummary(held = 2, unavailable = 1))
                            assertTrue(paused.retry == null)
                            assertTrue(runCatching { resumed.retry!!.invoke() }.isFailure)
                        } finally {
                            changes.finishConfiguration(source.id)
                        }
                        updates.expectSummary(StoragePendingSummary(1, 1, 1))
                        assertEquals(held, store.get<StoragePendingProgress>("progress", held.record.resource))
                        network.save(network.configuration.copy(accountIdentity = "other-account"), "other", "fixture")
                        updates.expectSummary(StoragePendingSummary())
                        assertTrue(
                            runCatching {
                                resumed.retry!!.invoke()
                            }.exceptionOrNull() is kotlinx.coroutines.CancellationException,
                        )
                        assertFalse(runtimeExists(source.id))
                    } finally {
                        job.cancelAndJoin()
                        updates.close()
                    }
                }
            }
        }
    }

    private suspend fun Channel<NetworkStoragePendingState?>.expectSummary(
        expected: StoragePendingSummary,
    ): NetworkStoragePendingState = withTimeout(5_000) {
        var state = receive()
        while (state?.summary != expected) state = receive()
        checkNotNull(state)
    }

    @Test
    fun restoredPendingStateStaysHeldAfterRestartAndContinuedReading(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            fixture(mode) { source, _, store, url ->
                val originalId = System.nanoTime()
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(originalId))
                val original = StorageRecordStore(store.session.copy(connectionId = originalId), repository, Json)
                check(original.list("progress").isEmpty())
                try {
                    val entry = StorageEntry("book.epub", false, 100, version = "v1")
                    val pending =
                        StoragePendingProgress(StorageProgressRecord("book", "v1", "old-writer", 9, 100, 2, 10), true)
                    original.put("progress", "book", pending)
                    original.put("identities", entry.path, "book")
                    val backup = StorageBackup(repository, Json)
                    val payload = backup.create(originalId, store.session.account, store.session.rootIdentity)
                    store.put("directories", "", StorageDirectorySnapshot(StorageEntry("", true), listOf(entry), 1))
                    backup.restore(source.id, store.session.rootIdentity, payload)
                    assertEquals(
                        pending.copy(requiresConfirmation = true),
                        store.get<StoragePendingProgress>("progress", "book"),
                    )
                    observe(source.observeProgressSyncStatus(listOf(url))) { updates ->
                        updates.expect(url, ConnectionProgressSyncStatus.HELD)
                        var progress = StorageProgress(NoProgressRequests, store, "current-writer")
                        progress.flush()
                        assertEquals(pending.record, progress.pull("book", "v1"))
                        val continued = progress.record("book", "v1", 200, locator = "continued local EPUB position")
                        progress = StorageProgress(NoProgressRequests, store, "current-writer")
                        progress.flush()
                        assertEquals(koharia.connection.ConnectionReadStatus.HELD, progress.readStatus("book"))
                        assertTrue(pending.record.device != continued.device)
                        assertEquals(continued.device, progress.cached("book")!!.device)
                        assertEquals("continued local EPUB position", progress.cached("book")!!.locator)
                        backup.restore(source.id, store.session.rootIdentity, payload)
                        assertEquals(200L, progress.cached("book")!!.modifiedAt)
                        updates.expect(url, ConnectionProgressSyncStatus.HELD)
                    }
                } finally {
                    repository.removeConnection(originalId)
                }
            }
        }
    }

    private object NoProgressRequests : LibraryStorageBackend {
        override val capabilities = StorageCapabilities(true, true, true, true)
        override suspend fun stat(path: String): StorageEntry = error("Held progress performed stat")
        override suspend fun list(path: String): List<StorageEntry> = error("Held progress performed list")
        override suspend fun read(
            entry: StorageEntry,
            offset: Long,
            length: Int,
        ): ByteArray = error("Held progress read")
        override suspend fun createDirectory(path: String): Unit = error("Held progress created a directory")
        override suspend fun write(
            path: String,
            data: java.io.InputStream,
            length: Long,
            expectedVersion: String?,
        ): Unit =
            error("Held progress uploaded")
        override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Held progress moved a file")
        override suspend fun delete(entry: StorageEntry): Unit = error("Held progress deleted a file")
        override fun close() = Unit
    }

    @Test
    fun webdavAndSmbObservePersistedStatusWithoutCreatingRuntime(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            fixture(mode) { source, network, store, url ->
                val resource = "stable-book"
                val entry = StorageEntry("book.epub", false, 100, version = "v1", identity = "native-book")
                val directory = StorageDirectorySnapshot(StorageEntry("", true), listOf(entry), 1)
                val progress = StoragePendingProgress(
                    StorageProgressRecord(resource, "v1", "fixture", 1, 100, page = 2, total = 10),
                    true,
                )
                store.put("identities", entry.path, resource)
                store.put("native-identities", "native-book", "wrong-fallback")
                store.put("progress", resource, progress)
                val foreign = StorageRecordStore(
                    StorageSession(source.id, "foreign", store.session.rootIdentity) { true },
                    repository,
                    Json,
                )
                foreign.put("progress", resource, "broken")
                observe(source.observeProgressSyncStatus(listOf(url))) { updates ->
                    updates.expect(url, ConnectionProgressSyncStatus.UNAVAILABLE)
                    store.put("directories", "", directory)
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put("directories", "", "broken")
                    updates.expect(url, ConnectionProgressSyncStatus.UNAVAILABLE)
                    store.put("directories", "", directory)
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put("identities", entry.path, mapOf("invalid" to true))
                    updates.expect(url, ConnectionProgressSyncStatus.UNAVAILABLE)
                    store.put("identities", entry.path, resource)
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put("progress", resource, progress.copy(pending = false))
                    updates.expectEmpty()
                    network.save(network.configuration.copy(persistentIdentity = false), "fixture", "fixture")
                    updates.expect(url, ConnectionProgressSyncStatus.LOCAL_ONLY)
                    store.put("progress", resource, progress)
                    updates.expect(url, ConnectionProgressSyncStatus.HELD)
                    network.save(
                        network.configuration.copy(persistentIdentity = true, needsValidation = true),
                        "fixture",
                        "fixture",
                    )
                    updates.expect(url, ConnectionProgressSyncStatus.HELD)
                    network.save(network.configuration.copy(needsValidation = false), "fixture", "fixture")
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put("progress", resource, progress.copy(requiresConfirmation = true))
                    updates.expect(url, ConnectionProgressSyncStatus.HELD)
                    store.put("progress", resource, progress)
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put(
                        "directories",
                        "",
                        directory.copy(children = listOf(entry.copy(version = "v2")), generation = 2),
                    )
                    updates.expect(url, ConnectionProgressSyncStatus.CONFLICT)
                    store.put("directories", "", directory.copy(generation = 3))
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put("identities", entry.path, "moved-resource")
                    updates.expectEmpty()
                    store.put(
                        "progress",
                        "moved-resource",
                        progress.copy(record = progress.record.copy(resource = "moved-resource")),
                    )
                    updates.expect(url, ConnectionProgressSyncStatus.PENDING)
                    store.put("progress", "moved-resource", "broken")
                    updates.expect(url, ConnectionProgressSyncStatus.UNAVAILABLE)
                    network.save(network.configuration.copy(accountIdentity = "replacement"), "other", "fixture")
                    updates.expect(url, ConnectionProgressSyncStatus.UNVERIFIED_OWNER)
                    assertFalse(runtimeExists(source.id))
                }
                assertEquals(progress, store.get<StoragePendingProgress>("progress", resource))
            }
        }
    }

    @Test
    fun nativeDirectoryDoesNotProbeSafOrInventRemotePendingState(): Unit = runBlocking(Dispatchers.IO) {
        fixture(LibraryStorageMode.WEBDAV) { source, _, _, url ->
            val prefs = LocalLibraryPreferences(source.id, Json)
            prefs.setConfig(
                LocalLibraryConfig(roots = listOf(LocalLibraryRootConfig("native", "content://fixture/root"))),
            )
            assertTrue(withTimeout(5_000) { source.observeProgressSyncStatus(listOf(url)).drop(1).first() }.isEmpty())
            assertFalse(runtimeExists(source.id))
        }
    }

    @Test
    fun scopedRecordObserverBatchesAndTracksOnlyRequestedRootAndKeys(): Unit = runBlocking(Dispatchers.IO) {
        fixture(LibraryStorageMode.SMB) { source, _, store, _ ->
            Injekt.get<tachiyomi.data.Database>().transaction {
                for (i in 1..1001) {
                    store.put("progress", "$i", i)
                    repository.put(
                        source.id,
                        store.session.account,
                        "foreign-root",
                        "progress",
                        koharia.domain.storage.StorageRecord("$i", "foreign", i.toLong()),
                    )
                }
            }
            val rows = withTimeout(10_000) {
                repository.observe(
                    source.id,
                    store.session.account,
                    store.session.rootIdentity,
                    "progress",
                    (1..1001).map { "$it" },
                ).first()
            }
            assertEquals(1001, rows.size)
            assertTrue(rows.all { it.payload == it.key })
            assertTrue(
                repository.observe(
                    source.id,
                    store.session.account,
                    store.session.rootIdentity,
                    "progress",
                    emptyList(),
                ).first().isEmpty(),
            )
        }
    }

    private suspend fun observe(
        flow: Flow<ProgressStatuses>,
        block: suspend (Channel<ProgressStatuses>) -> Unit,
    ) = coroutineScope {
        val updates = Channel<ProgressStatuses>(Channel.UNLIMITED)
        val job = launch { flow.collect { updates.send(it) } }
        try {
            block(updates)
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun Channel<ProgressStatuses>.expect(
        url: String,
        status: ConnectionProgressSyncStatus,
    ) = withTimeout(5_000) {
        while (receive()[url] != setOf(status)) Unit
    }

    private suspend fun Channel<ProgressStatuses>.expectEmpty() = withTimeout(5_000) {
        while (receive().isNotEmpty()) Unit
    }

    private fun runtimeExists(id: Long): Boolean {
        val field = NetworkStorageRuntime::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        return (field.get(null) as Map<*, *>).containsKey(id)
    }

    private suspend fun fixture(
        mode: LibraryStorageMode,
        block: suspend (LocalFolderSource, NetworkStoragePreferences, StorageRecordStore, String) -> Unit,
    ) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val prefs = sourcePreferences("source_$id")
        check(prefs.all.isEmpty())
        check(!runtimeExists(id))
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val counter = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { requests.incrementAndGet() }
                } catch (_: java.io.IOException) {
                    if (!listener.isClosed) requests.incrementAndGet()
                }
            }
        }.apply { start() }
        var source: LocalFolderSource? = null
        try {
            val network = NetworkStoragePreferences(id)
            network.save(
                NetworkStorageConfiguration(
                    mode = mode,
                    address = if (mode == LibraryStorageMode.WEBDAV) {
                        "http://127.0.0.1:${listener.localPort}"
                    } else {
                        "smb://127.0.0.1:${listener.localPort}/share"
                    },
                    rootIdentity = "root-$id",
                ),
                "fixture",
                "fixture",
            )
            val uri = DocumentsContract.buildDocumentUri(
                NetworkStorageRuntime.authority(context),
                "$id|${network.account}|",
            )
            val root = LocalLibraryRootConfig("root", uri.toString())
            LocalLibraryPreferences(id, Json).setConfig(LocalLibraryConfig(roots = listOf(root)))
            val owner =
                LocalFolderSource(
                    context,
                    id,
                    "Status fixture",
                    LibraryConnectionProfile(id, "local-folder", "Status fixture"),
                )
            source = owner
            val store =
                StorageRecordStore(
                    StorageSession(id, network.account, network.configuration.rootIdentity) {
                        true
                    },
                    repository,
                    Json,
                )
            block(owner, network, store, LocalLibraryLocator.chapterUrl(id, root.id, "book.epub"))
            assertFalse(runtimeExists(id))
            assertEquals(0, requests.get())
        } finally {
            source?.close()
            listener.close()
            counter.join(2_000)
            NetworkStorageRuntime.invalidate(id)
            repository.removeConnection(id)
            check(prefs.edit().clear().commit())
        }
    }
}
