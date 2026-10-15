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
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.komga.progress.KomgaApiPageTransport
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class KomgaActiveRestoreDeviceTest {
    @Test
    fun collidingAndNewProfilesRestoreWhileOldHttpSessionIsActive(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("activeRestoreAddress")
        assumeTrue(address != null)
        require(checkNotNull(address).matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        val prefix = "Komga active restore ${System.nanoTime()}"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<KomgaPageEventRepository>()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val inventory = profiles.profiles()
        val owned = mutableSetOf<Long>()
        val backup = File(context.cacheDir, "$prefix.kohariabk")
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        suspend fun source(id: Long): KomgaSource = withTimeout(10_000) {
            while (sources.get(id) !is KomgaSource) delay(25)
            sources.get(id) as KomgaSource
        }
        fun control(path: String): String = URL(address + "fixture/" + path).openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 3_000
        }.getInputStream().bufferedReader().use { it.readText() }
        fun config(id: Long, role: String) = BackupSourcePreferences(
            "source_$id",
            listOf(
                BackupPreference("Address", StringPreferenceValue(address.trimEnd('/'))),
                BackupPreference("AuthMode", StringPreferenceValue("Credentials")),
                BackupPreference("Username", StringPreferenceValue("fixture-$role")),
                BackupPreference("Password", StringPreferenceValue("fixture-password")),
            ),
        )
        fun transport(source: KomgaSource): KomgaApiPageTransport {
            val captured = captureKomgaProgressSession(source, sources)
            return KomgaApiPageTransport(KomgaApi(6, source.client), captured, address + "api/v1/books/book") {
                captured.guard.requireCurrent()
            }
        }
        try {
            val old = profiles.add("komga", prefix)
            owned += old.id
            profiles.commitConfiguration(old.id) { draft ->
                check(
                    draft.edit().putString("Address", address.trimEnd('/')).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture-old").putString("Password", "fixture-password").commit(),
                )
            }
            val original = source(old.id)
            val session = captureKomgaProgressSession(original, sources)
            val oldAccount = session.account.key
            val baseline = transport(original).read()
            assertEquals(2, baseline.page)
            val key = KomgaReadStatusKey(old.id, "restored:owned-fixture", "book")
            val pending = KomgaPageEvent(key, 1, 0, 3, 10, 100, null, KomgaReadStatusPhase.HELD)
            repository.restoreHeld(listOf(KomgaPageState(pending, null)))
            assertEquals(pending, repository.get(key))
            val oldTransport = transport(original)
            val delayed = async { runCatching { oldTransport.read() } }
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
                        BackupConnection(old.id, "komga", prefix),
                        BackupConnection(unusedId, "komga", "$prefix added"),
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
            assertTrue(runCatching { session.guard.requireCurrent() }.isFailure)
            assertTrue(runCatching { captureKomgaProgressSession(original, sources) }.isFailure)
            control("release")
            val failure = withTimeout(5_000) { delayed.await() }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it is KomgaProgressOwnerChanged })
            assertEquals(pending, repository.get(key))
            assertEquals("fixture-old", sourcePreferences("source_${old.id}").getString("Username", ""))
            assertEquals(oldAccount, retained.progressAccount().key)
            for ((id, role) in listOf(old.id to "old", replacement.id to "new", added.id to "third")) {
                val current = source(id)
                assertEquals("fixture-$role", sourcePreferences("source_$id").getString("Username", ""))
                val result = transport(current).read()
                assertEquals(
                    when (role) {
                        "old" -> 2
                        "new" -> 6
                        else -> 8
                    },
                    result.page,
                )
                if (id != old.id) {
                    assertNotEquals(oldAccount, current.progressAccount().key)
                    assertTrue(
                        repository.states(id, current.progressAccount().key, listOf("book")).values.all {
                            it.event ==
                                null &&
                                it.attempt == null
                        },
                    )
                }
            }
            assertEquals(pending, repository.get(key))
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
