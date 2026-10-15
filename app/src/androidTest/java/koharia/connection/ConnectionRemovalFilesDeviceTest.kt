package koharia.connection

import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import com.hippo.unifile.UniFile
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadProvider
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.storageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.file.Files

class ConnectionRemovalFilesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun registeredProviderDeletionPreservesFilesAndOtherOwnersAndRecreationIsIndependent(): Unit =
        runBlocking(Dispatchers.IO) {
            val context = instrumentation.targetContext
            assertEquals("app.koharia.dev.devicefixture", context.packageName)
            val registry = Injekt.get<ConnectionRegistry>()
            assertEquals(
                setOf("komga", "kavita", "lanraragi", "smanga", "suwayomi", "local-folder"),
                registry.availableProviders().map { it.id }.toSet(),
            )
            val profiles = Injekt.get<ConnectionProfileManager>()
            val preferences = Injekt.get<ConnectionPreferences>()
            val sources = Injekt.get<SourceManager>()
            val mangas = Injekt.get<MangaRepository>()
            val downloads = Injekt.get<DownloadProvider>()
            val inventory = profiles.profiles()
            val hadActive = preferences.activeConnectionId.isSet()
            val active = preferences.activeConnectionId.get()
            val prefix = "Removal ${System.nanoTime()}"
            val owned = mutableSetOf<Long>()
            val downloadDirectories = mutableListOf<UniFile>()
            val cacheDirectories = mutableListOf<File>()
            val root = Files.createTempDirectory(context.cacheDir.toPath(), "removal-files-").toFile().canonicalFile
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putLong("fixture_active_before", active)
                    putBoolean("fixture_active_was_set", hadActive)
                },
            )
            val bytes = "Owned manual file; preserve on connection removal".toByteArray()
            suspend fun source(id: Long): ConnectionSource = withTimeout(10_000) {
                while (sources.get(id) !is ConnectionSource) delay(25)
                sources.get(id) as ConnectionSource
            }
            suspend fun create(provider: String, suffix: String, mode: LibraryStorageMode): LibraryConnectionProfile {
                val profile = profiles.add(provider, "$prefix $suffix")
                owned += profile.id
                profiles.commitConfiguration(profile.id) { draft ->
                    check(draft.edit().putString("address", "http://127.0.0.1:1/").commit())
                    if (provider == "komga") check(draft.edit().putString("Address", "http://127.0.0.1:1/").commit())
                    if (provider == "kavita") {
                        koharia.source.kavita.KavitaPreferences(profile.id, draft).save(
                            "http://127.0.0.1:1/",
                            "fixture",
                            koharia.kavita.KavitaAccount(id = 1, username = "fixture", roles = listOf("Admin")),
                        )
                    }
                    if (provider == "local-folder") {
                        if (mode == LibraryStorageMode.LOCAL) {
                            val folder = File(root, profile.id.toString()).apply { mkdirs() }
                            File(folder, "book.bin").writeBytes(bytes)
                            LocalLibraryPreferences(profile.id, Injekt.get<Json>(), draft).setConfig(
                                LocalLibraryConfig(
                                    roots = listOf(
                                        LocalLibraryRootConfig(id = "owned", treeUri = Uri.fromFile(folder).toString()),
                                    ),
                                    libraryId = "owned-${profile.id}",
                                    setupCompleted = true,
                                ),
                            )
                        } else {
                            NetworkStoragePreferences(profile.id, draft).save(
                                NetworkStorageConfiguration(
                                    mode,
                                    if (mode ==
                                        LibraryStorageMode.SMB
                                    ) {
                                        "smb://127.0.0.1:1/owned/"
                                    } else {
                                        "http://127.0.0.1:1/owned/"
                                    },
                                    rootIdentity = "owned-${profile.id}",
                                    needsValidation = true,
                                ),
                                "fixture",
                                "fixture",
                            )
                        }
                    }
                }
                source(profile.id)
                return profile
            }
            fun cache(id: Long, provider: String): File? {
                val path = when (provider) {
                    "kavita" -> File(context.cacheDir, "kavita-pdf/$id")
                    "smanga" -> File(context.cacheDir, "smanga-pdf/$id")
                    "komga" -> File(context.filesDir, "komga-organization/$id")
                    "local-folder" -> {
                        val settings = NetworkStoragePreferences(id)
                        val namespace = storageDigest("$id/${settings.account}/${settings.configuration.rootIdentity}")
                        File(context.filesDir, "library-storage-cache/$namespace")
                    }
                    else -> return null
                }
                check(!path.exists())
                check(path.mkdirs())
                cacheDirectories += path.canonicalFile
                return File(path, "owned-cache.bin").apply { writeBytes(bytes) }
            }
            suspend fun manualFile(current: ConnectionSource): UniFile {
                check(downloads.findOwnedSourceDirs(current).isEmpty())
                val directory = downloads.getMangaDir("Owned manual book", current).getOrThrow()
                val sourceDirectory = checkNotNull(directory.parentFile)
                check(
                    sourceDirectory.name in (current as ConnectionDownloadStorageAdapter).ownedDownloadDirectoryNames(),
                )
                downloadDirectories += sourceDirectory
                return checkNotNull(directory.createFile("owned.bin")).also {
                    it.openOutputStream().use { stream -> stream.write(bytes) }
                }
            }
            fun unchanged(
                file: UniFile,
            ) = assertTrue(file.openInputStream().use { it.readBytes() }.contentEquals(bytes))
            try {
                val modes = registry.availableProviders().flatMap { provider ->
                    (
                        if (provider.id ==
                            "local-folder"
                        ) {
                            LibraryStorageMode.entries
                        } else {
                            listOf(LibraryStorageMode.LOCAL)
                        }
                        )
                        .map { provider.id to it }
                }
                for ((provider, mode) in modes) {
                    val original = create(provider, "$provider $mode old", mode)
                    val neighbor = create(provider, "$provider $mode keep", mode)
                    val oldSource = source(original.id)
                    val otherSource = source(neighbor.id)
                    val oldSession = ConnectionSourceSession.capture(oldSource)
                    val otherSession = ConnectionSourceSession.capture(otherSource)
                    val oldFile = manualFile(oldSource)
                    val otherFile = manualFile(otherSource)
                    val oldCache = cache(original.id, provider)
                    val otherCache = cache(neighbor.id, provider)
                    val otherManga = mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = neighbor.id,
                                url = "owned-book",
                                title = "Preserved owner",
                                favorite = true,
                            ),
                        ),
                    ).single()
                    val otherConfig = eu.kanade.tachiyomi.source.sourcePreferences("source_${neighbor.id}").all
                    preferences.activeConnectionId.set(neighbor.id)
                    oldSession.checkCurrent()
                    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                        scenario.onActivity {
                            it.setContent { TachiyomiTheme { Navigator(LibraryConnectionProfilesScreen()) } }
                        }
                        val label = awaitProfile(original.name)
                        var clickable: AccessibilityNodeInfo? = label
                        while (clickable != null && !clickable.isLongClickable) clickable = clickable.parent
                        assertTrue(checkNotNull(clickable).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                        click(awaitText(context.stringResource(MR.strings.action_delete)))
                        awaitText(context.stringResource(MR.strings.delete_connection))
                        click(awaitText(context.stringResource(MR.strings.action_delete)))
                        withTimeout(10_000) { while (profiles.profiles().any { it.id == original.id }) delay(25) }
                    }
                    withTimeout(10_000) { while (sources.get(original.id) === oldSource) delay(25) }
                    assertTrue(runCatching { oldSession.checkCurrent() }.exceptionOrNull() is CancellationException)
                    otherSession.checkCurrent()
                    assertEquals(otherManga, mangas.getMangaById(otherManga.id))
                    assertEquals(otherConfig, eu.kanade.tachiyomi.source.sourcePreferences("source_${neighbor.id}").all)
                    unchanged(oldFile)
                    unchanged(otherFile)
                    oldCache?.let { assertFalse(it.exists()) }
                    otherCache?.let { assertTrue(it.readBytes().contentEquals(bytes)) }
                    if (provider == "local-folder" && mode == LibraryStorageMode.LOCAL) {
                        for (id in listOf(original.id, neighbor.id)) {
                            assertTrue(File(root, "$id/book.bin").readBytes().contentEquals(bytes))
                        }
                    }
                    val replacement = profiles.add(provider, original.name)
                    owned += replacement.id
                    assertNotEquals(original.id, replacement.id)
                    assertNotEquals(original.name, replacement.name)
                    val replacementSource = source(replacement.id)
                    assertNotEquals(
                        (oldSource as ConnectionDownloadStorageAdapter).downloadDirectoryName(),
                        (replacementSource as ConnectionDownloadStorageAdapter).downloadDirectoryName(),
                    )
                    assertTrue(downloads.findOwnedSourceDirs(replacementSource).isEmpty())
                    unchanged(oldFile)
                    unchanged(otherFile)
                    assertEquals(neighbor.id, preferences.activeConnectionId.get())
                    assertTrue(profiles.profiles().containsAll(inventory))
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString(
                                "removal_contract",
                                "$provider/$mode: UI-delete;old-session-rejected;files-and-neighbor-preserved;new-id-isolated",
                            )
                        },
                    )
                    // Complete this owned pair before presenting the next pair in the production list.
                    profiles.remove(neighbor.id).getOrThrow()
                    profiles.remove(replacement.id).getOrThrow()
                    mangas.deleteMangaBySourceId(neighbor.id)
                }
            } finally {
                if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
                profiles.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
                for (id in owned) {
                    check(profiles.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                    profiles.remove(id).getOrThrow()
                    mangas.deleteMangaBySourceId(id)
                }
                // Raw-file directories must be emptied explicitly; delete() is not recursive there.
                downloadDirectories.forEach { directory ->
                    if (directory.exists()) {
                        val children = checkNotNull(directory.listFiles())
                        check(children.all { it.name == downloads.getMangaDirName("Owned manual book") })
                        for (child in children) {
                            val files = checkNotNull(child.listFiles())
                            check(files.all { it.name == "owned.bin" })
                            for (file in files) {
                                unchanged(file)
                                check(file.delete())
                            }
                            check(child.delete())
                        }
                        check(directory.delete())
                    }
                }
                for (directory in cacheDirectories) {
                    check(
                        directory.path.startsWith(context.cacheDir.canonicalPath + File.separator) ||
                            directory.path.startsWith(context.filesDir.canonicalPath + File.separator),
                    )
                    check(!directory.exists() || directory.deleteRecursively())
                }
                check(root.parentFile == context.cacheDir.canonicalFile)
                check(root.deleteRecursively())
            }
        }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private suspend fun awaitText(text: String): AccessibilityNodeInfo = withTimeout(10_000) {
        while (true) {
            find { it.text?.toString() == text }?.let { return@withTimeout it }
            delay(50)
        }
        error("unreachable")
    }

    private suspend fun awaitProfile(text: String): AccessibilityNodeInfo {
        delay(500)
        repeat(24) {
            find { it.text?.toString() == text }?.let { return it }
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(200)
        }
        return awaitText(text)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
