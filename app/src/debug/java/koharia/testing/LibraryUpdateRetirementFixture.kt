package koharia.testing

import android.content.Context
import androidx.annotation.Keep
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import koharia.connection.AndroidSharedConfigStorage
import koharia.connection.ConnectionPreferences
import koharia.connection.SharedConfigMigration
import koharia.core.migration.Migration
import koharia.core.migration.MigrationContext
import koharia.core.migration.migrations.SetupLibraryUpdateMigration
import koharia.source.komga.KomgaConnectionMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

object LibraryUpdateRetirementFixture {
    @Keep
    @JvmStatic
    fun run(context: Context, status: Consumer<String>): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val wm = WorkManager.getInstance(context)
        val names = listOf("LibraryUpdate-auto", "LibraryUpdate-manual")
        names.forEach { name -> check(wm.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished }) }
        val backupJobs = wm.getWorkInfosForUniqueWork("BackupCreator").get().map { it.id to it.state }
        val interval = Injekt.get<LibraryPreferences>().autoUpdateInterval
        val oldInterval = interval.get()
        val hadInterval = interval.isSet()
        val globalConnections = Injekt.get<ConnectionPreferences>()
        val inventory = globalConnections.getProfiles()
        val requests = mutableListOf<UUID>()
        val prefix = "retired-update-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(prefix, 0)
        check(prefs.all.isEmpty())
        val store = AndroidPreferenceStore(context, prefs)
        val json = Injekt.get<Json>()
        val connections = ConnectionPreferences(store, json) { prefs.edit().commit() }
        val key = "pref_library_update_interval_key"
        val migration =
            SharedConfigMigration(AndroidSharedConfigStorage(prefs), mapOf(key to 0), connections::getProfiles, json)
        assertTrue(migration.initialize())
        var backupSchedulingCalls = 0
        val restorer = PreferenceRestorer(
            context,
            preferenceStore = store,
            komgaConnectionMigration = KomgaConnectionMigration(context, store, connections, json),
            connectionPreferences = connections,
            sharedConfigMigration = migration,
            setupBackupTask = { backupSchedulingCalls++ },
        )
        suspend fun verifyRetirement(label: String, action: suspend () -> Unit) {
            val periodic = PeriodicWorkRequestBuilder<LibraryUpdateJob>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS).build()
            val manual = OneTimeWorkRequestBuilder<LibraryUpdateJob>().setInitialDelay(1, TimeUnit.DAYS).build()
            val unrelated = OneTimeWorkRequestBuilder<LibraryUpdateJob>().setInitialDelay(1, TimeUnit.DAYS).build()
            requests += listOf(periodic.id, manual.id, unrelated.id)
            wm.enqueueUniquePeriodicWork(names[0], ExistingPeriodicWorkPolicy.KEEP, periodic).result.get()
            wm.enqueueUniqueWork(names[1], ExistingWorkPolicy.KEEP, manual).result.get()
            wm.enqueue(unrelated).result.get()
            action()
            withTimeout(5_000) {
                while (listOf(periodic.id, manual.id).any {
                        wm.getWorkInfoById(it).get()!!.state != WorkInfo.State.CANCELLED
                    }
                ) {
                    delay(25)
                }
            }
            names.forEach { name -> assertTrue(wm.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished }) }
            assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(unrelated.id).get()!!.state)
            wm.cancelWorkById(unrelated.id).result.get()
            status.accept(label)
        }
        try {
            interval.set(12)
            globalConnections.flush()
            verifyRetirement("upgrade-legacy-interval-12") {
                val upgrade = SetupLibraryUpdateMigration()
                assertEquals(Migration.ALWAYS, upgrade.version)
                assertTrue(upgrade(MigrationContext(dryrun = false)))
                assertEquals(12, interval.get())
            }
            for ((inputKey, hours) in listOf(key to 24, "connection_shared::$key" to 48)) {
                val bytes = ProtoBuf.encodeToByteArray(
                    Backup.serializer(),
                    Backup(
                        emptyList(),
                        backupPreferences = listOf(BackupPreference(inputKey, IntPreferenceValue(hours))),
                    ),
                )
                val restored = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
                verifyRetirement("restore-$hours-hours") {
                    restorer.restoreApp(restored.backupPreferences, null, preserveConnectionInventory = true)
                    assertEquals(hours, store.getInt("connection_shared::$key").get())
                    assertTrue(prefs.edit().commit())
                    restorer.finishRestore()
                    assertFalse(migration.isPending())
                    // A repeated restore completion remains cancellation-only.
                    restorer.finishRestore()
                }
                assertEquals(hours, AndroidPreferenceStore(context, prefs).getInt("connection_shared::$key").get())
            }
            assertEquals(4, backupSchedulingCalls)
            assertTrue(inventory == globalConnections.getProfiles())
            assertEquals(backupJobs, wm.getWorkInfosForUniqueWork("BackupCreator").get().map { it.id to it.state })
        } finally {
            requests.forEach { wm.cancelWorkById(it).result.get() }
            if (hadInterval) interval.set(oldInterval) else interval.delete()
            globalConnections.flush()
            assertEquals(oldInterval, interval.get())
            assertEquals(hadInterval, interval.isSet())
            assertTrue(context.deleteSharedPreferences(prefix))
            status.accept("owned-work-cancelled;preferences-restored")
        }
    }
    private fun assertTrue(value: Boolean) = check(value)

    private fun assertFalse(value: Boolean) = check(!value)

    private fun assertEquals(expected: Any?, actual: Any?) = check(expected == actual) {
        "Expected $expected, actual $actual"
    }
}
