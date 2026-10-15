package koharia.epub

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePreferences
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapterRef
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSettingsScreen
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

@RunWith(AndroidJUnit4::class)
class EpubSettingsCacheDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun kavitaSettingsSaveUsesCurrentClientAndKeepsOpenedBook(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val previousActive = connections.activeConnectionId.get()
        val hadActive = connections.activeConnectionId.isSet()
        val profile = profiles.add("kavita", "EPUB settings ${UUID.randomUUID()}")
        val owned = File(context.cacheDir, "epub-settings-${UUID.randomUUID()}").canonicalFile
        check(owned.parentFile == context.cacheDir.canonicalFile && !owned.exists())
        val application = object : Application() {
            override fun getExternalFilesDir(type: String?) = File(owned, "external/${type.orEmpty()}")
            override fun getFilesDir() = File(owned, "files")
            override fun getCacheDir() = File(owned, "cache")
        }
        val cache = EpubCacheManager(application, EpubCachePreferences(InMemoryPreferenceStore()))
        try {
            Server().use { server ->
                profiles.commitConfiguration(profile.id) { draft ->
                    KavitaPreferences(profile.id, draft).save(server.address, "fixture-first", server.account)
                }
                connections.activeConnectionId.set(profile.id)
                val firstSource = sources.get(profile.id) as KavitaSource
                val account = firstSource.preferences.accountKey
                val firstUrl = firstSource.session().identity.chapter(KavitaChapterRef(1, 2, 3, 4, 3))
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
                    FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.setContent {
                                TachiyomiTheme {
                                    Navigator(listOf(Landing(), KavitaSettingsScreen(profile.id))) {
                                        navigation.set(it)
                                        CurrentScreen()
                                    }
                                }
                            }
                        }
                        click(context.stringResource(MR.strings.kavita_key))
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
                        assertTrue(KavitaPreferences(profile.id).key == "fixture-first")
                        server.useSecond = true
                        click(context.stringResource(MR.strings.action_save))
                        withTimeout(20_000) { while (navigation.get().lastItem !is Landing) delay(50) }
                        val secondSource = sources.get(profile.id) as KavitaSource
                        assertNotSame(firstSource, secondSource)
                        assertEquals(account, secondSource.preferences.accountKey)
                        assertTrue(secondSource.preferences.key == "fixture-second")
                        assertEquals(
                            listOf(profile.id),
                            profiles.profiles().filter { it.name == profile.name }.map { it.id },
                        )
                        val secondUrl = secondSource.session().identity.chapter(KavitaChapterRef(1, 2, 3, 5, 3))
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
                                    "kavita;settings-ui-save;same-owner-new-source;" +
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
            if (hadActive) {
                connections.activeConnectionId.set(previousActive)
            } else {
                connections.activeConnectionId.delete()
            }
            profiles.remove(profile.id).getOrThrow()
            withTimeout(10_000) { while (sources.get(profile.id) != null) delay(25) }
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

    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${listener.localPort}/"
        val account = KavitaAccount(
            1,
            "fixture",
            "fixture-token-first",
            listOf("Admin"),
            "0.8.8",
            "fixture-created",
            "fixture-install",
        )

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
                        val url = (address.trimEnd('/') + request.substringAfter(' ').substringBefore(' ')).toHttpUrl()
                        val phase = if (useSecond) "second" else "first"
                        val path = url.encodedPath
                        val isAuth = path == "/api/Plugin/authenticate"
                        val isBook = path.startsWith("/api/Book/")
                        val valid = if (isAuth) {
                            url.queryParameter("apiKey") == "fixture-$phase"
                        } else {
                            headers["authorization"] == "Bearer fixture-token-$phase"
                        }
                        var status = if (valid) 200 else 401
                        val body = when {
                            !valid -> "{}"
                            isAuth || path == "/api/Account/refresh-account" ->
                                """{"id":1,"username":"fixture","token":"fixture-token-$phase",
                                    "roles":["Admin"],"kavitaVersion":"0.8.8","createdUtc":"fixture-created"}"""
                            path == "/api/Server/server-info-slim" -> """{"installId":"fixture-install"}"""
                            path.endsWith("/book-info") -> """{"pages":1,"bookTitle":"Settings fixture"}"""
                            path.endsWith("/chapters") -> "[]"
                            path.endsWith("/book-page") -> {
                                val chapter = path.split('/')[3]
                                bookPages += "$chapter:$phase"
                                "<div><p>Book $chapter remains readable</p></div>"
                            }
                            else -> {
                                status = 404
                                "{}"
                            }
                        }
                        if (isBook && !valid) bookAuthorizationErrors += path
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(bytes)
                            flush()
                        }
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
}
