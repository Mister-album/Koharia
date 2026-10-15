package koharia.connection

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.ui.library.ConnectionLibraryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiRepository
import koharia.lanraragi.ui.LanraragiLibraryScreen
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class ShelfLateResponseUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rootSwitchKeepsCurrentShelfAfterOldResponse() = verify("switch")

    @Test fun rootDeletionKeepsCurrentShelfAfterOldResponse() = verify("delete")

    @Test fun nestedRestoreKeepsCurrentShelfAfterOldResponse() = verify("restore")

    private fun verify(mode: String): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("shelfLateAddress")
        assumeTrue(address != null)
        require(checkNotNull(address).startsWith("http://127.0.0.1:"))
        val prefix = "Late shelf $mode ${System.nanoTime()}"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<LanraragiRepository>()
        val inventory = profiles.profiles()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val owned = mutableSetOf<Long>()
        val backup = File(context.cacheDir, "$prefix.kohariabk")
        fun control(path: String): String = URL(address + "fixture/" + path).openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 3_000
        }.getInputStream().bufferedReader().use { it.readText() }
        suspend fun source(id: Long): LanraragiSource = withTimeout(10_000) {
            while (sources.get(id) !is LanraragiSource) delay(25)
            sources.get(id) as LanraragiSource
        }
        suspend fun create(role: String, title: String): LibraryConnectionProfile {
            val profile = profiles.add("lanraragi", "$prefix $role")
            owned += profile.id
            profiles.commitConfiguration(profile.id) { draft ->
                LanraragiPreferences(profile.id, draft).save(address, "fixture-$role", groupCollections = false)
            }
            val current = source(profile.id)
            val generation = System.nanoTime()
            repository.begin(profile.id, generation)
            repository.stage(profile.id, generation, listOf(LanraragiEntry(ARCHIVE, title = title, pageCount = 10)))
            repository.publish(profile.id, generation, System.currentTimeMillis())
            current.preferences.markIndexed(address)
            return profile
        }
        try {
            val old = create("old", "Original shelf")
            val next = create("new", "Current shelf")
            val previous = source(old.id)
            preferences.activeConnectionId.set(old.id)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(
                                if (mode ==
                                    "restore"
                                ) {
                                    LanraragiLibraryScreen(old.id, null, true)
                                } else {
                                    RootShelf()
                                },
                            )
                        }
                    }
                }
                awaitLabel("Original shelf")
                assertEquals("true", control("arm"))
                val delayed = async { runCatching { previous.refreshLibrary().getOrThrow() } }
                try {
                    withTimeout(10_000) { while (control("blocked") != "true") delay(25) }
                    val currentId = if (mode == "restore") {
                        writeBackup(
                            backup,
                            Backup(
                                emptyList(),
                                backupConnections = listOf(BackupConnection(old.id, "lanraragi", old.name)),
                                backupSourcePreferences = listOf(
                                    BackupSourcePreferences(
                                        "source_${old.id}",
                                        listOf(
                                            BackupPreference("address", StringPreferenceValue(address)),
                                            BackupPreference(
                                                Preference.privateKey("api_key"),
                                                StringPreferenceValue("fixture-new"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        )
                        BackupRestorer(context, BackupNotifier(context), false).restore(
                            Uri.fromFile(backup),
                            RestoreOptions(libraryEntries = false, categories = false, appSettings = false),
                        )
                        val current = source(old.id)
                        assertNotSame(previous, current)
                        current.refreshLibrary().getOrThrow()
                        old.id
                    } else {
                        preferences.activeConnectionId.set(next.id)
                        if (mode == "delete") profiles.remove(old.id).getOrThrow()
                        next.id
                    }
                    awaitLabel("Current shelf")
                    val before = repository.entries(currentId)
                    assertEquals(listOf("Current shelf"), before.map { it.title })
                    assertEquals("true", control("release"))
                    val outcome = withTimeout(10_000) { delayed.await() }
                    if (mode != "switch") {
                        assertTrue(outcome.isFailure)
                    } else {
                        assertTrue(outcome.isSuccess)
                    }
                    awaitLabel("Current shelf")
                    instrumentation.waitForIdleSync()
                    assertFalse(hasLabel(instrumentation.uiAutomation.rootInActiveWindow, "Obsolete shelf"))
                    assertEquals(before, repository.entries(currentId))
                    if (mode == "delete") assertTrue(repository.entries(old.id).isEmpty())
                    if (mode ==
                        "switch"
                    ) {
                        assertEquals(listOf("Obsolete shelf"), repository.entries(old.id).map { it.title })
                    }
                    assertEquals(currentId, preferences.activeConnectionId.get())
                } finally {
                    control("release")
                    withTimeout(10_000) { delayed.join() }
                }
            }
            assertTrue(profiles.profiles().containsAll(inventory))
        } finally {
            ConnectionLibraryTab.clearAllRuntimeState()
            profiles.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
            for (id in owned) {
                val profile = profiles.profiles().singleOrNull { it.id == id }
                check(profile == null || profile.name.startsWith(prefix))
                if (profile != null) profiles.remove(id).getOrThrow()
            }
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
            backup.delete()
        }
    }

    private suspend fun awaitLabel(label: String) = withTimeout(15_000) {
        while (!hasLabel(instrumentation.uiAutomation.rootInActiveWindow, label)) delay(100)
    }

    private fun hasLabel(node: AccessibilityNodeInfo?, label: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return true
        return (0 until node.childCount).any { hasLabel(node.getChild(it), label) }
    }

    private class RootShelf : Screen() {
        @Composable override fun Content() {
            val navigator = LocalNavigator.currentOrThrow
            TabNavigator(LibraryTab) {
                CompositionLocalProvider(LocalNavigator provides navigator) { LibraryTab.Content() }
            }
        }
    }

    private companion object {
        const val ARCHIVE = "0000000000000000000000000000000000000001"
    }

    private fun writeBackup(file: File, backup: Backup) {
        val parser = Injekt.get<ProtoBuf>()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(parser.encodeToByteArray(Backup.serializer(), backup)) }
        }.toByteArray()
        val envelope = KohariaBackupEnvelope(
            magic = KohariaBackupEnvelope.MAGIC,
            formatVersion = KohariaBackupEnvelope.FORMAT_VERSION,
            schemaVersion = KohariaBackupEnvelope.SCHEMA_VERSION,
            createdAt = System.currentTimeMillis(),
            applicationId = "app.koharia.dev.devicefixture",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(compressed)
                .joinToString("") { "%02x".format(it) },
            compressedPayload = compressed,
        )
        file.writeBytes(parser.encodeToByteArray(KohariaBackupEnvelope.serializer(), envelope))
    }
}
