package koharia.smanga

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Configuration is injected into this isolated application's private files by the test operator. */
class SmangaLiveFixture {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    init {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        assumeTrue(InstrumentationRegistry.getArguments().getString("runSmangaLive") == "true")
    }

    val config: Config = readConfig()

    fun createApi(namespace: String): SmangaApi = SmangaApi(
        networkClient = Injekt.get<NetworkHelper>().client,
        json = Injekt.get<Json>(),
        address = config.address,
        username = config.username,
        password = config.password,
        namespace = namespace,
    )

    private fun readConfig(): Config {
        try {
            val file = File(context.filesDir, "smanga-live.json")
            check(file.isFile && file.length() in 1L..16_384L)
            val value = Injekt.get<Json>().parseToJsonElement(file.readText()).jsonObject
            val address = value.getValue("address").jsonPrimitive.content
            val username = value.getValue("username").jsonPrimitive.content
            val password = value.getValue("password").jsonPrimitive.content
            check(address.isNotBlank() && username.isNotBlank() && password.isNotEmpty())
            SmangaApi.normalizeBase(address)
            return Config(address, username, password)
        } catch (_: Exception) {
            // Parser diagnostics can contain the original JSON, including credentials.
            throw AssertionError("Missing or invalid private Smanga live fixture configuration")
        }
    }

    class Config(val address: String, val username: String, val password: String)
}
