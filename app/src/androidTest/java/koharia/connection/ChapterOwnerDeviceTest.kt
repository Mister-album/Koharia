package koharia.connection

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.kavita.KavitaAccount
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.suwayomi.SuwayomiAuthMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ChapterOwnerDeviceTest {
    @Test
    fun productionOwnersRejectConfigurationChangesAndSessionReloads(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val providers = Injekt.get<ConnectionRegistry>().availableProviders()
        assertEquals(
            setOf("komga", "kavita", "lanraragi", "smanga", "suwayomi", "local-folder"),
            providers.map { it.id }.toSet(),
        )
        var scenarios = 0
        for (provider in providers) {
            val modes = if (provider.id == "local-folder") LibraryStorageMode.entries else listOf(null)
            for (mode in modes) {
                val id = System.nanoTime()
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
                val prefs = sourcePreferences("source_$id")
                check(prefs.all.isEmpty())
                var source: ConnectionSource? = null
                try {
                    val mutate: () -> Unit = when (provider.id) {
                        "komga" -> {
                            check(prefs.edit().putString("Address", "http://127.0.0.1:1").commit())
                            fun() {
                                check(prefs.edit().putString("Password", "replacement").commit())
                            }
                        }
                        "kavita" -> {
                            val configuration = KavitaPreferences(id)
                            configuration.save(
                                "http://127.0.0.1:1",
                                "fixture",
                                KavitaAccount(id = 1, username = "fixture", roles = listOf("Admin")),
                            )
                            fun() {
                                configuration.internalAddress = "http://127.0.0.1:2"
                            }
                        }
                        "lanraragi" -> {
                            val configuration = LanraragiPreferences(id)
                            configuration.save("http://127.0.0.1:1", "fixture")
                            fun() {
                                configuration.save(configuration.address, "replacement")
                            }
                        }
                        "smanga" -> {
                            val configuration = SmangaPreferences(id)
                            configuration.save("http://127.0.0.1:1", "fixture", "fixture", 1)
                            fun() {
                                configuration.save(configuration.address, "fixture", "replacement", 1)
                            }
                        }
                        "suwayomi" -> {
                            val configuration = SuwayomiPreferences(id)
                            configuration.save("http://127.0.0.1:1", "", SuwayomiAuthMode.NONE, "fixture", "fixture")
                            fun() {
                                configuration.save(
                                    "http://127.0.0.1:1",
                                    "",
                                    SuwayomiAuthMode.BASIC_AUTH,
                                    "fixture",
                                    "fixture",
                                )
                            }
                        }
                        "local-folder" -> {
                            val local = LocalLibraryPreferences(id, Json)
                            local.setConfig(LocalLibraryConfig(libraryId = "fixture-$id"))
                            val network = NetworkStoragePreferences(id)
                            network.save(
                                NetworkStorageConfiguration(
                                    mode = checkNotNull(mode),
                                    address = if (mode == LibraryStorageMode.SMB) {
                                        "smb://127.0.0.1/share"
                                    } else {
                                        "http://127.0.0.1:1"
                                    },
                                    rootIdentity = "fixture-$id",
                                    needsValidation = true,
                                ),
                                "fixture",
                                "fixture",
                            )
                            fun() {
                                if (mode == LibraryStorageMode.LOCAL) {
                                    local.setConfig(local.getConfig().copy(libraryId = "replacement"))
                                } else {
                                    network.save(network.configuration, "fixture", "replacement")
                                }
                            }
                        }
                        else -> error("Add owner verification for ${provider.id}")
                    }
                    val created = provider.createSource(LibraryConnectionProfile(id, provider.id, "Chapter fixture"))
                    source = created
                    val adapter = created as ConnectionChapterSessionAdapter
                    val before = adapter.captureChapterOwner()
                    before.checkCurrent()
                    when (created) {
                        is KavitaSource -> created.reload()
                        is SmangaSource -> created.reload()
                        is SuwayomiSource -> created.reload()
                        is LanraragiSource -> created.reload()
                    }
                    if (created is KavitaSource || created is SmangaSource ||
                        created is SuwayomiSource || created is LanraragiSource
                    ) {
                        rejected(before, "${provider.id}: reloaded session")
                    }
                    val afterReload = adapter.captureChapterOwner()
                    afterReload.checkCurrent()
                    mutate()
                    rejected(afterReload, "${provider.id}/$mode: configuration change")
                    if (mode != null) {
                        val field = NetworkStorageRuntime::class.java.getDeclaredField("sessions").apply {
                            isAccessible = true
                        }
                        assertFalse((field.get(null) as Map<*, *>).containsKey(id))
                    }
                    scenarios++
                } finally {
                    (source as? AutoCloseable)?.close()
                    check(prefs.edit().clear().commit())
                }
            }
        }
        assertEquals(8, scenarios)
    }

    private fun rejected(owner: ConnectionChapterOwner, scenario: String) {
        val failure = runCatching { owner.checkCurrent() }.exceptionOrNull()
        assertTrue("$scenario returned ${failure?.javaClass?.simpleName}", failure is CancellationException)
    }
}
