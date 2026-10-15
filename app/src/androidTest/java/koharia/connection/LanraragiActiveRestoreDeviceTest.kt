package koharia.connection

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class LanraragiActiveRestoreDeviceTest {
    @Test
    fun collidingAndNewProfilesRestoreWhileOldHttpSessionIsActive(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("activeRestoreAddress")
        assumeTrue(address != null)
        require(checkNotNull(address).matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val prefix = "LRR active restore ${System.nanoTime()}"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<LanraragiRepository>()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val inventory = profiles.profiles()
        val owned = mutableSetOf<Long>()
        val temporaryPreferences = mutableListOf<String>()
        val backup = File(context.cacheDir, "$prefix.kohariabk")
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        suspend fun source(id: Long): LanraragiSource = withTimeout(10_000) {
            while (sources.get(id) !is LanraragiSource) delay(25)
            sources.get(id) as LanraragiSource
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
            LanraragiPreferences(id, store).save(address, "fixture-$role")
            val values = store.all.map { (key, value) ->
                BackupPreference(
                    key,
                    when (value) {
                        is String -> StringPreferenceValue(value)
                        is Boolean -> BooleanPreferenceValue(value)
                        is Set<*> -> StringSetPreferenceValue(value.map { it as String }.toSet())
                        else -> error("Unexpected fixture preference type")
                    },
                )
            }
            return BackupSourcePreferences("source_$id", values)
        }
        try {
            val old = profiles.add("lanraragi", prefix)
            owned += old.id
            profiles.commitConfiguration(old.id) { draft ->
                LanraragiPreferences(old.id, draft).save(address, "fixture-old")
            }
            val original = source(old.id)
            val archive = "0000000000000000000000000000000000000001"
            val entry = LanraragiEntry(archive, title = "Original cache", pageCount = 10)
            val generation = System.nanoTime()
            repository.begin(old.id, generation)
            repository.stage(old.id, generation, listOf(entry))
            repository.publish(old.id, generation, System.currentTimeMillis())
            original.preferences.markIndexed(address)
            repository.record(old.id, LanraragiReadState(archive, 0, 10, 100, pending = false))
            val pending = checkNotNull(repository.readState(old.id, archive))
            val chapterUrl = original.resourceUrl(entry)
            val session = original.capturePageProgressSession(Chapter.create().copy(url = chapterUrl))
            val oldApi = original.api
            val delayed = async { runCatching { session.adapter.pullPageProgress(chapterUrl, JsonObject(emptyMap())) } }
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
                        BackupConnection(old.id, "lanraragi", prefix),
                        BackupConnection(unusedId, "lanraragi", "$prefix added"),
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
            assertTrue(runCatching { session.checkCurrent() }.isFailure)
            assertTrue(runCatching { oldApi.archive(archive) }.isFailure)
            control("release")
            val failure = withTimeout(5_000) { delayed.await() }.exceptionOrNull()
            assertTrue("Old reader request must fail after restore", failure != null)
            assertTrue(runCatching { session.checkCurrent() }.exceptionOrNull() is CancellationException)
            assertEquals(pending, repository.readState(old.id, archive))
            assertEquals(listOf(entry), repository.entries(old.id))
            assertEquals("fixture-old", retained.preferences.apiKey)
            for ((id, role) in listOf(old.id to "old", replacement.id to "new", added.id to "third")) {
                val current = source(id)
                assertEquals("fixture-$role", current.preferences.apiKey)
                assertEquals(role, current.api.archive(archive).title)
                val url = current.resourceUrl(entry)
                val result = checkNotNull(current.pullPageProgress(url, JsonObject(emptyMap())))
                val expected = when (role) {
                    "old" -> 1
                    "new" -> 5
                    else -> 7
                }
                assertEquals(expected, result.pageIndex)
                assertEquals(expected, repository.readState(id, archive)!!.pageIndex)
                assertFalse(repository.readState(id, archive)!!.pending)
            }
            assertTrue(profiles.profiles().containsAll(inventory))
            assertFalse(ConnectionRestoreState.isRestoring)
        } finally {
            runCatching { control("release") }
            profiles.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
            for (id in owned) {
                check(profiles.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                profiles.remove(id).getOrThrow()
                repository.remove(id)
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
