package koharia.epub

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePreferences
import koharia.source.komga.KomgaServerSettingsScreen
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class KomgaEpubSettingsCacheDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun settingsReconnectUsesCurrentClientAndKeepsOpenedBook(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        recoverInterruptedFixtureIfRequested()
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val previousActive = connections.activeConnectionId.get()
        val hadActive = connections.activeConnectionId.isSet()
        val cachedOnly = Injekt.get<BasePreferences>().downloadedOnly
        val hadCachedOnly = cachedOnly.isSet()
        val previousCachedOnly = cachedOnly.get()
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putLong("fixture_previous_active", previousActive)
                putBoolean("fixture_had_active", hadActive)
                putBoolean("fixture_previous_cached_only", previousCachedOnly)
                putBoolean("fixture_had_cached_only", hadCachedOnly)
            },
        )
        val profile = profiles.add("komga", "EPUB settings ${UUID.randomUUID()}")
        val owned = File(context.cacheDir, "epub-settings-${UUID.randomUUID()}").canonicalFile
        check(owned.parentFile == context.cacheDir.canonicalFile && !owned.exists())
        val application = object : Application() {
            override fun getExternalFilesDir(type: String?) = File(owned, "external/${type.orEmpty()}")
            override fun getFilesDir() = File(owned, "files")
            override fun getCacheDir() = File(owned, "cache")
        }
        val cache = EpubCacheManager(application, EpubCachePreferences(InMemoryPreferenceStore()))
        try {
            cachedOnly.set(false)
            val seed = File(owned, "seed.epub").apply {
                check(parentFile!!.isDirectory || parentFile!!.mkdirs())
            }
            writeEpub(seed)
            Server(seed.readBytes()).use { server ->
                profiles.commitConfiguration(profile.id) { draft ->
                    check(
                        draft.edit().putString("Address", server.address.trimEnd('/'))
                            .putString("AuthMode", "Credentials").putString("Username", "fixture")
                            .putString("Password", "fixture-first").commit(),
                    )
                }
                connections.activeConnectionId.set(profile.id)
                val firstSource = sources.get(profile.id) as KomgaSource
                val firstUrl = server.address + "api/v1/books/4"
                val first = checkNotNull(cache.cacheCompleteBook(firstSource, firstUrl, "first", acquireLease = true))
                val original = first.readBytes()
                val http = DefaultHttpClient()
                val retriever = AssetRetriever(context.contentResolver, http)
                val opener = PublicationOpener(
                    DefaultPublicationParser(context.applicationContext as Application, http, retriever, null),
                )
                val asset = retriever.retrieve(checkNotNull(Uri.fromFile(first).toAbsoluteUrl()), MediaType.EPUB)
                    .getOrElse { error(it.message) }
                val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
                    asset.close()
                    error(it.message)
                }
                try {
                    val navigation = AtomicReference<Navigator>()
                    FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.setContent {
                                TachiyomiTheme {
                                    Navigator(listOf(Landing(), KomgaServerSettingsScreen(profile.id))) {
                                        navigation.set(it)
                                        CurrentScreen()
                                    }
                                }
                            }
                        }
                        click(context.stringResource(MR.strings.komga_pref_password_title))
                        val field = awaitNode { it.isEditable }
                        assertTrue(
                            field.performAction(
                                AccessibilityNodeInfo.ACTION_SET_TEXT,
                                Bundle().apply {
                                    putCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                        "fixture-second",
                                    )
                                },
                            ),
                        )
                        click(context.stringResource(MR.strings.action_ok))
                        assertTrue(
                            sourcePreferences("source_${profile.id}").getString("Password", "") == "fixture-first",
                        )
                        val before = profiles.profiles().map { it.id }.toSet()
                        server.useSecond = true
                        click(context.stringResource(MR.strings.action_save))
                        withTimeout(20_000) { while (navigation.get().lastItem !is Landing) delay(50) }
                        val replacement = profiles.profiles().single { it.id !in before }
                        check(replacement.providerId == "komga" && replacement.name.startsWith(profile.name))
                        val secondSource = sources.get(replacement.id) as KomgaSource
                        assertNotSame(firstSource, secondSource)
                        assertTrue(secondSource.id != profile.id)
                        assertEquals(profile, profiles.profiles().single { it.id == profile.id })
                        assertTrue(profiles.requiresIdentityReplacement(profile.id))
                        assertEquals(replacement.id, connections.activeConnectionId.get())
                        assertTrue(
                            sourcePreferences("source_${replacement.id}").getString("Password", "") == "fixture-second",
                        )
                        val secondUrl = server.address + "api/v1/books/5"
                        val second = checkNotNull(cache.cacheCompleteBook(secondSource, secondUrl, "second"))
                        assertTrue(second.isFile && second != first)
                        assertTrue(original.contentEquals(first.readBytes()))
                        val resource = checkNotNull(publication.get(publication.readingOrder.single()))
                        try {
                            val text = resource.read().getOrElse { error(it.message) }.decodeToString()
                            assertTrue(text.contains("Book 4"))
                        } finally {
                            resource.close()
                        }
                        assertEquals(listOf("4:first", "5:second"), server.bookPages.toList())
                        assertTrue(server.bookAuthorizationErrors.isEmpty())
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "settings_cache",
                                    "komga;settings-ui-save;new-identity-old-profile-retained;" +
                                        "book-4-first;book-5-second;opened-book-retained",
                                )
                            },
                        )
                    }
                } finally {
                    publication.close()
                    cache.release(first)
                }
            }
        } finally {
            if (hadCachedOnly) cachedOnly.set(previousCachedOnly) else cachedOnly.delete()
            if (hadActive) {
                connections.activeConnectionId.set(previousActive)
            } else {
                connections.activeConnectionId.delete()
            }
            val created = profiles.profiles().filter { it.providerId == "komga" && it.name.startsWith(profile.name) }
            created.asReversed().forEach { profiles.remove(it.id).getOrThrow() }
            withTimeout(10_000) { while (created.any { sources.get(it.id) != null }) delay(25) }
            val scope = EpubCacheManager::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
                .get(cache) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
            check(owned.parentFile == context.cacheDir.canonicalFile)
            check(!owned.exists() || owned.deleteRecursively())
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("settings_cache_cleanup", "owned-profile-cache-removed")
                },
            )
        }
    }

    private suspend fun recoverInterruptedFixtureIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        val id = arguments.getString("recoverEpubSettingsProfile")?.toLong() ?: return
        val name = checkNotNull(arguments.getString("recoverEpubSettingsName"))
        val rootName = checkNotNull(arguments.getString("recoverEpubSettingsCache"))
        check(name.startsWith("EPUB settings "))
        UUID.fromString(name.removePrefix("EPUB settings "))
        check(rootName.startsWith("epub-settings-"))
        UUID.fromString(rootName.removePrefix("epub-settings-"))
        val profiles = Injekt.get<ConnectionProfileManager>()
        val profile = profiles.profiles().single { it.id == id }
        check(profile.providerId == "komga" && profile.name == name)
        val stored = sourcePreferences("source_$id")
        check(stored.getString("Username", "") == "fixture")
        check(stored.getString("Password", "") == "fixture-first")
        check(stored.getString("Address", "")!!.toHttpUrl().host == "127.0.0.1")
        val root = File(context.cacheDir, rootName).canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && root.isDirectory)
        profiles.remove(id).getOrThrow()
        check(root.deleteRecursively())
        // The interrupted run's cache-only diagnostic established this pre-existing value.
        Injekt.get<BasePreferences>().downloadedOnly.set(true)
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("interrupted_fixture_cleanup", "owned-profile-cache-removed;cached-only-restored")
                putString("interrupted_selection", "original-selection-unrecorded;normal-removal-fallback-retained")
            },
        )
    }

    private suspend fun click(label: String) {
        var target: AccessibilityNodeInfo? = awaitNode {
            it.text?.toString() == label || it.contentDescription?.toString() == label
        }
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
        delay(200)
    }

    private suspend fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        withTimeout(15_000) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                find(root, predicate)?.let { return@withTimeout it }
                find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(100)
            }
            error("Unreachable")
        }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private class Landing : Screen() {
        @Composable override fun Content() {
            Text("EPUB settings landing")
        }
    }

    private class Server(private val epub: ByteArray) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${listener.localPort}/"

        @Volatile var useSecond = false
        val bookPages = CopyOnWriteArrayList<String>()
        val bookAuthorizationErrors = CopyOnWriteArrayList<String>()
        private val worker = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val b = input.read()
                                if (b == -1 || b == 10) break
                                if (b != 13) append(b.toChar())
                            }
                        }
                        val request = line()
                        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.associate {
                            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                        }
                        repeat(headers["content-length"]?.toInt() ?: 0) { check(input.read() >= 0) }
                        val path = (address.trimEnd('/') + request.substringAfter(' ').substringBefore(' '))
                            .toHttpUrl().encodedPath
                        val phase = if (useSecond) "second" else "first"
                        val valid = headers["authorization"] == Credentials.basic("fixture", "fixture-$phase")
                        val book = path.startsWith("/api/v1/books/") && path.endsWith("/file")
                        val status = if (valid) 200 else 401
                        if (book) {
                            if (valid) bookPages += "${path.split('/')[4]}:$phase" else bookAuthorizationErrors += path
                        }
                        val body = when {
                            !valid -> "{}".toByteArray()
                            book -> epub
                            path == "/api/v1/users/me" ->
                                """{"id":"fixture","email":"fixture@example.test","roles":["ADMIN","FILE_DOWNLOAD"]}"""
                                    .toByteArray()
                            path == "/api/v1/libraries" -> "[]".toByteArray()
                            else -> "{}".toByteArray()
                        }
                        val media = if (book) "application/epub+zip" else "application/json"
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Fixture\r\nContent-Type: $media\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(body)
                            flush()
                        }
                    }
                } catch (error: java.net.SocketException) {
                    if (!listener.isClosed && error.message?.let {
                            it.contains("Broken pipe", true) || it.contains("Connection reset", true)
                        } != true
                    ) {
                        throw error
                    }
                } catch (error: Exception) {
                    if (!listener.isClosed) throw error
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        override fun close() {
            listener.close()
            worker.join(5_000)
        }
    }

    private fun writeEpub(file: File) {
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent(),
            "book.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">lease</dc:identifier>
                <dc:title>Lease fixture</dc:title><dc:language>en</dc:language></metadata><manifest>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>
            """.trimIndent(),
            "chapter.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Lease</title></head>
                <body><p>Book 4 remains readable</p></body></html>
            """.trimIndent(),
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                val bytes = text.encodeToByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        if (name == "mimetype") {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
