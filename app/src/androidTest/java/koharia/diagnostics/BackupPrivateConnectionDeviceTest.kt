package koharia.diagnostics

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class BackupPrivateConnectionDeviceTest {
    @Test fun allRegisteredProviderCredentialFieldsRespectPrivateExport(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val credentials = mapOf(
            "komga" to listOf("Username", "Password", "API key", "Api key"),
            "lanraragi" to listOf(Preference.privateKey("api_key")),
            "kavita" to listOf("kavita_key", "kavita_principal", "kavita_identity").map { Preference.privateKey(it) },
            "smanga" to listOf("username", "password").map { Preference.privateKey(it) },
            "suwayomi" to listOf("username", "password").map { Preference.privateKey(it) },
            "local-folder" to listOf("storage_username", "storage_password").map { Preference.privateKey(it) },
        )
        assertEquals(
            Injekt.get<ConnectionRegistry>().availableProviders().map { it.id }.toSet(),
            credentials.keys,
        )
        val baseId = System.nanoTime()
        val inventory = "backup-private-$baseId"
        val profiles = credentials.keys.mapIndexed { index, provider ->
            LibraryConnectionProfile(baseId + index, provider, "Isolated private fields $index")
        }
        val existing = Injekt.get<ConnectionPreferences>().getProfiles()
        val file = File(context.cacheDir, "$inventory.kohariabackup")
        val prefs =
            ConnectionPreferences(AndroidPreferenceStore(context, context.getSharedPreferences(inventory, 0)), Json)
        // Use production export with an isolated inventory; never register credential fixtures as live Sources.
        val sources = object : SourceManager by Injekt.get<SourceManager>() {
            override fun getCatalogueSources(): List<CatalogueSource> = emptyList()
        }
        try {
            prefs.setProfiles(profiles)
            profiles.forEach { profile ->
                val source = sourcePreferences("source_${profile.id}")
                assertTrue(source.all.isEmpty())
                source.edit().apply {
                    putString("fixture_public_setting", "kept")
                    credentials.getValue(profile.providerId).forEach { key -> putString(key, "fixture-canary-$key") }
                }.commit().also { assertTrue(it) }
            }
            val creator = BackupCreator(
                context,
                isAutoBackup = false,
                sourceManager = sources,
                preferenceBackupCreator = PreferenceBackupCreator(sources, connectionPreferences = prefs),
            )
            for (includePrivate in listOf(false, true)) {
                file.createNewFile()
                creator.backup(
                    Uri.fromFile(file),
                    BackupOptions(
                        libraryEntries = false,
                        categories = false,
                        readEntries = false,
                        appSettings = false,
                        privateSettings = includePrivate,
                    ),
                )
                val decoded = BackupDecoder(context).decode(Uri.fromFile(file))
                assertEquals(profiles.map { it.id }.toSet(), decoded.backupConnections.map { it.id }.toSet())
                profiles.forEach { profile ->
                    val keys = decoded.backupSourcePreferences.single { it.sourceKey == "source_${profile.id}" }
                        .prefs.map { it.key }.toSet()
                    assertTrue("Public settings must remain", "fixture_public_setting" in keys)
                    credentials.getValue(profile.providerId).forEach { key ->
                        assertEquals("${profile.providerId}: sensitive field $key", includePrivate, key in keys)
                    }
                }
            }
            assertEquals(existing, Injekt.get<ConnectionPreferences>().getProfiles())
        } finally {
            profiles.forEach { context.deleteSharedPreferences("source_${it.id}") }
            context.deleteSharedPreferences(inventory)
            file.delete()
        }
    }
}
