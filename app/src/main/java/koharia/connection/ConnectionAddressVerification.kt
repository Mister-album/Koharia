package koharia.connection

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.i18n.stringResource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Verifies addresses directly, without routing or falling back to another address. */
class ConnectionAddressVerification(networkClient: OkHttpClient) {
    enum class Provider { KOMGA, LANRARAGI }
    enum class Reason { UNAVAILABLE, AUTHENTICATION, MISMATCH, CLEANUP }
    class Failure(val reason: Reason, val marker: String = "") : IOException(reason.name) {
        fun userMessage(context: android.content.Context): String = context.stringResource(
            when (reason) {
                Reason.AUTHENTICATION -> tachiyomi.i18n.MR.strings.connection_verify_auth_failed
                Reason.UNAVAILABLE -> tachiyomi.i18n.MR.strings.connection_verify_unavailable
                Reason.MISMATCH -> tachiyomi.i18n.MR.strings.connection_verify_mismatch
                Reason.CLEANUP -> tachiyomi.i18n.MR.strings.connection_verify_cleanup
            },
            marker,
        )
    }

    private val client = networkClient.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .dns(Dns.SYSTEM)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun verify(provider: Provider, publicAddress: String, internalAddress: String, headers: Headers) {
        if (internalAddress.isBlank()) return
        val public = ConnectionAddressRouter.normalize(publicAddress) ?: throw Failure(Reason.UNAVAILABLE)
        val internal = ConnectionAddressRouter.normalize(internalAddress) ?: throw Failure(Reason.UNAVAILABLE)
        if (provider == Provider.LANRARAGI) {
            try {
                check(!headers["Authorization"].isNullOrBlank())
                for (address in listOf(public, internal).distinct()) {
                    check(send(address, "api/plugins/metadata", headers) is JsonArray)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw Failure(Reason.AUTHENTICATION)
            }
            return
        }
        if (public == internal) return
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val marker = "koharia.connection.verify.n$nonce"
        var writeAttempted = false
        var writeConfirmed = false
        try {
            send(public, "api/v1/client-settings/user/list", headers)
            // Let an in-flight write finish before cancellation starts cleanup.
            withContext(NonCancellable) {
                writeAttempted = true
                send(
                    public,
                    "api/v1/client-settings/user",
                    headers,
                    "PATCH",
                    buildJsonObject { put(marker, buildJsonObject { put("value", nonce) }) }.body(),
                )
                writeConfirmed = true
            }
            val settings = send(internal, "api/v1/client-settings/user/list", headers).jsonObject
            if (settings[marker]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull != nonce) {
                throw Failure(Reason.MISMATCH)
            }
        } catch (error: Exception) {
            if (error is CancellationException || error is Failure) throw error
            throw Failure(Reason.UNAVAILABLE)
        } finally {
            if (writeAttempted) {
                val cleaned = withContext(NonCancellable) {
                    runCatching {
                        withTimeout(20_000) {
                            try {
                                cleanup(public, headers, marker, nonce, writeConfirmed)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                cleanup(public, headers, marker, nonce, writeConfirmed)
                            }
                        }
                    }.isSuccess
                }
                if (!cleaned) throw Failure(Reason.CLEANUP, marker)
            }
        }
    }

    private suspend fun cleanup(
        base: HttpUrl,
        headers: Headers,
        marker: String,
        nonce: String,
        writeConfirmed: Boolean,
    ) {
        val path = "api/v1/client-settings/user"
        var current = send(base, "$path/list", headers).jsonObject[marker]
        if (!writeConfirmed) {
            // A timed-out write may still commit. Absence alone cannot confirm cleanup.
            repeat(8) {
                if (current == null) {
                    delay(500)
                    current = send(base, "$path/list", headers).jsonObject[marker]
                }
            }
            checkNotNull(current)
        }
        val value = current ?: return
        check(value.jsonObject["value"]?.jsonPrimitive?.contentOrNull == nonce)
        send(base, path, headers, "DELETE", JsonArray(listOf(JsonPrimitive(marker))).body())
        check(marker !in send(base, "$path/list", headers).jsonObject)
    }

    private suspend fun send(
        base: HttpUrl,
        path: String,
        headers: Headers,
        method: String = "GET",
        body: RequestBody? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(checkNotNull(base.resolve(path)))
            .headers(headers)
            .removeHeader("Cookie")
            .header("Cache-Control", "no-cache, no-store")
            .header("Accept", "application/json")
            .method(method, body)
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw Failure(Reason.UNAVAILABLE)
            val text = response.body.string()
            val value = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
            if ((value as? JsonObject)?.get("success")?.jsonPrimitive?.contentOrNull in listOf("false", "0")) {
                throw Failure(Reason.UNAVAILABLE)
            }
            value
        }
    }

    private fun JsonElement.body(): RequestBody = toString().toRequestBody("application/json".toMediaType())
}
