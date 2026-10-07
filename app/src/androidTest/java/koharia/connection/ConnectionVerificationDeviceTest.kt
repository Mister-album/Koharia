package koharia.connection

import androidx.activity.ComponentActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.preference.DeferredSharedPreferencesDataStore
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.Closeable
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConnectionVerificationDeviceTest {
    @Test
    fun inferredPreferenceDefaultsAndLegacyApiKeyDoNotChangeTheConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
            for (legacyKey in listOf(false, true)) {
                val id = -System.nanoTime()
                val preferenceName = "source_$id"
                val preferences = sourcePreferences(preferenceName)
                check(preferences.all.isEmpty())
                try {
                    preferences.edit().apply {
                        if (legacyKey) {
                            putString("Api key", "fixture-api-key")
                        } else {
                            putString("Username", "fixture-user")
                            putString("Password", "fixture-password")
                        }
                    }.commit()
                    val source = KomgaSource(id)
                    val draft = DeferredSharedPreferencesDataStore(preferences)
                    scenario.onActivity { activity ->
                        val manager = PreferenceManager(activity)
                        manager.preferenceDataStore = draft
                        source.setupPreferenceScreen(manager.createPreferenceScreen(activity))
                    }
                    draft.putString(KomgaSource.PREF_SERVER_PROFILE_NAME, "Renamed legacy connection")
                    assertFalse(source.connectionSettingsChanged(draft))
                    if (legacyKey) assertEquals("fixture-api-key", draft.getString("API key", ""))
                    draft.applyChanges()
                    assertFalse(source.connectionSettingsChanged(draft))
                } finally {
                    context.deleteSharedPreferences(preferenceName)
                }
            }
        }
    }

    @Test
    fun draftAuthenticationRetryCleanupAndPreferenceSummaries(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = -System.nanoTime()
        val preferenceName = "source_$id"
        val preferences = sourcePreferences(preferenceName)
        check(preferences.all.isEmpty())
        val source = KomgaSource(id)
        val draft = DeferredSharedPreferencesDataStore(preferences)
        Fixture().use { fixture ->
            try {
                draft.putString("Address", fixture.base + "public/")
                draft.putString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, fixture.base + "internal/")
                draft.putString("AuthMode", "Credentials")
                draft.putString("Username", "fixture-user")
                draft.putString("Password", "incorrect-fixture-password")
                val failure = try {
                    source.verifyServerAddresses(draft)
                    error("Invalid fixture credentials must fail")
                } catch (failure: ConnectionAddressVerification.Failure) {
                    failure
                }
                assertEquals(ConnectionAddressVerification.Reason.AUTHENTICATION, failure.reason)
                assertEquals(ConnectionValidation.Endpoint.PUBLIC, failure.endpoint)
                assertEquals(401, failure.status)
                assertTrue(
                    failure.userMessage(context).contains(context.stringResource(MR.strings.connection_public_address)),
                )
                assertTrue(preferences.all.isEmpty())
                assertTrue(fixture.settings.isEmpty())
                assertEquals("fixture-user", draft.getString("Username", ""))

                draft.putString("Password", "fixture-password")
                fixture.dropInternal.set(true)
                source.verifyServerAddresses(draft)
                assertEquals(3, fixture.internalReads.get())
                assertEquals(1, fixture.writes.get())
                assertEquals(1, fixture.deletes.get())
                assertTrue(fixture.settings.isEmpty())

                FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val manager = PreferenceManager(activity)
                        manager.preferenceDataStore = draft
                        val screen = manager.createPreferenceScreen(activity)
                        source.setupPreferenceScreen(screen)
                        val username = screen.findPreference<EditTextPreference>("Username")!!
                        val password = screen.findPreference<EditTextPreference>("Password")!!
                        assertEquals("fixture-user", username.summary.toString())
                        assertEquals(
                            context.stringResource(MR.strings.connection_credential_configured),
                            password.summary,
                        )
                        draft.putString("Password", "")
                        assertEquals(
                            context.stringResource(MR.strings.connection_credential_not_configured),
                            password.summary,
                        )
                        draft.putString("Password", "fixture-password")
                    }
                }
                draft.applyChanges()
                assertEquals(fixture.base + "public/", preferences.getString("Address", ""))
                assertEquals(
                    fixture.base + "internal/",
                    preferences.getString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, ""),
                )
                draft.putString(KomgaSource.PREF_SERVER_PROFILE_NAME, "Fixture renamed offline")
                assertFalse(source.connectionSettingsChanged(draft))
                draft.putString("Password", "another-fixture-password")
                assertTrue(source.connectionSettingsChanged(draft))
                assertEquals("fixture-password", preferences.getString("Password", ""))
            } finally {
                context.deleteSharedPreferences(preferenceName)
            }
        }
    }

    private class Fixture : Closeable {
        private val server = ServerSocket(0)
        val base = "http://127.0.0.1:${server.localPort}/"
        val settings = ConcurrentHashMap<String, kotlinx.serialization.json.JsonElement>()
        val dropInternal = AtomicBoolean()
        val internalReads = AtomicInteger()
        val writes = AtomicInteger()
        val deletes = AtomicInteger()
        private val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine()?.split(' ') ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var read = 0
                        while (read < chars.size) {
                            val count = reader.read(chars, read, chars.size - read)
                            check(count > 0)
                            read += count
                        }
                        val method = request[0]
                        val internal = request[1].startsWith("/internal/")
                        if (internal && method == "GET") {
                            internalReads.incrementAndGet()
                            if (dropInternal.getAndSet(false)) return@use
                        }
                        val authenticated =
                            headers["authorization"] == Credentials.basic("fixture-user", "fixture-password")
                        val code = when {
                            !authenticated -> 401
                            method == "PATCH" -> {
                                writes.incrementAndGet()
                                settings.putAll(Json.parseToJsonElement(String(chars)).jsonObject)
                                204
                            }
                            method == "DELETE" -> {
                                deletes.incrementAndGet()
                                (Json.parseToJsonElement(String(chars)) as JsonArray).forEach {
                                    settings.remove(it.jsonPrimitive.content)
                                }
                                204
                            }
                            else -> 200
                        }
                        val body = if (code == 200) JsonObject(settings.toMap()).toString() else ""
                        val response = "HTTP/1.1 $code Fixture\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                            "Content-Type: application/json\r\nConnection: close\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray())
                    }
                } catch (error: SocketException) {
                    if (!server.isClosed) throw error
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
