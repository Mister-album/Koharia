package koharia.connection

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaBookmarkKind
import koharia.kavita.KavitaBookmarkState
import koharia.kavita.KavitaChapterRef
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class KavitaActiveRestoreDeviceTest {
    @Test
    fun collidingAndNewProfilesRestoreWhileOldHttpSessionIsActive(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("activeRestoreAddress")
        assumeTrue(address != null)
        require(checkNotNull(address).matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val prefix = "Active restore ${System.nanoTime()}"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<KavitaRepository>()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val inventory = profiles.profiles()
        val owned = mutableSetOf<Long>()
        val temporaryPreferences = mutableListOf<String>()
        val backup = File(context.cacheDir, "$prefix.kohariabk")
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        fun account(role: String) = KavitaAccount(
            id = when (role) {
                "old" -> 1
                "new" -> 2
                else -> 3
            },
            username = role,
            token = "fixture-$role",
            roles = listOf("Login", "Bookmark"),
            kavitaVersion = "0.9.1.4",
        )
        suspend fun source(id: Long): KavitaSource = withTimeout(10_000) {
            while (sources.get(id) !is KavitaSource) delay(25)
            sources.get(id) as KavitaSource
        }
        fun control(path: String): String = URL(address + "fixture/" + path).openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 3_000
        }.getInputStream().bufferedReader().use { it.readText() }
        fun config(id: Long, role: String): BackupSourcePreferences {
            val name = "$prefix-$role"
            val store = context.getSharedPreferences(name, 0)
            check(store.all.isEmpty())
            temporaryPreferences += name
            KavitaPreferences(id, store).save(address, "fixture-$role", account(role))
            val values = store.all.map { (key, value) ->
                BackupPreference(
                    key,
                    when (value) {
                        is String -> StringPreferenceValue(value)
                        is Set<*> -> StringSetPreferenceValue(value.map { it as String }.toSet())
                        else -> error("Unexpected fixture preference type")
                    },
                )
            }
            return BackupSourcePreferences("source_$id", values)
        }
        try {
            val old = profiles.add("kavita", prefix)
            owned += old.id
            profiles.commitConfiguration(old.id) { draft ->
                KavitaPreferences(old.id, draft).save(address, "fixture-old", account("old"))
            }
            val original = source(old.id)
            val session = original.session()
            val oldAccount = original.preferences.accountKey
            assertEquals("old", session.api.getAccount(true).username)
            val state = KavitaBookmarkState(
                KavitaChapterRef(7, 8, 4, 1, 1),
                KavitaBookmarkKind.IMAGE,
                3,
                needsConfirmation = true,
            )
            val pending = KavitaOperation(state.key, Json.encodeToString(state), 100, true)
            repository.putOperation(old.id, oldAccount, pending)
            val delayed = async { runCatching { session.catalog.resource("Library/libraries", true) } }
            withTimeout(10_000) {
                while (control("blocked") != "true") delay(25)
            }
            val unusedId = System.currentTimeMillis() + 100_000
            check(!preferences.isKnownConnectionId(unusedId))
            writeBackup(
                backup,
                Backup(
                    emptyList(),
                    backupConnections = listOf(
                        BackupConnection(old.id, "kavita", prefix),
                        BackupConnection(unusedId, "kavita", "$prefix added"),
                    ),
                    backupSourcePreferences = listOf(config(old.id, "new"), config(unusedId, "third")),
                ),
            )
            BackupRestorer(context, BackupNotifier(context), false).restore(
                Uri.fromFile(backup),
                RestoreOptions(libraryEntries = false, categories = false, appSettings = false),
            )
            val imported = profiles.profiles().filter { it.id != old.id && it.name.startsWith(prefix) }
            assertEquals(2, imported.size)
            owned += imported.map { it.id }
            val replacement = imported.single { it.name == "$prefix (2)" }
            val added = imported.single { it.name == "$prefix added" }
            assertNotEquals(old.id, replacement.id)
            assertNotEquals(unusedId, added.id)
            assertTrue(profiles.requiresIdentityReplacement(replacement.id))
            assertTrue(profiles.requiresIdentityReplacement(added.id))
            assertFalse(profiles.requiresIdentityReplacement(old.id))
            val retained = source(old.id)
            assertNotSame(original, retained)
            assertTrue(runCatching { session.checkActive() }.isFailure)
            assertTrue(runCatching { original.session() }.isFailure)
            control("release")
            assertTrue(withTimeout(5_000) { delayed.await() }.isFailure)
            assertEquals(null, repository.cache(old.id, oldAccount, "resource/Library/libraries", "data"))
            assertEquals(pending, repository.operation(old.id, oldAccount, state.key))
            assertEquals("old", retained.preferences.principal)
            assertEquals(oldAccount, retained.preferences.accountKey)
            for ((id, role) in listOf(old.id to "old", replacement.id to "new", added.id to "third")) {
                val current = source(id)
                assertEquals(role, current.preferences.principal)
                assertEquals(role, current.session().api.getAccount(true).username)
                val result = current.session().catalog.resource("Library/libraries", true) as JsonArray
                assertEquals(role, (result.single() as JsonObject).getValue("name").jsonPrimitive.content)
                val stored = repository.cache(id, current.preferences.accountKey, "resource/Library/libraries", "data")
                assertEquals(result.toString(), checkNotNull(stored).payload)
            }
            assertTrue(profiles.profiles().containsAll(inventory))
            assertFalse(ConnectionRestoreState.isRestoring)
        } finally {
            runCatching { control("release") }
            profiles.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
            for (id in owned) {
                check(profiles.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                profiles.remove(id).getOrThrow()
                repository.removeConnection(id)
            }
            temporaryPreferences.forEach(context::deleteSharedPreferences)
            backup.delete()
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
            foreground.close()
        }
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
