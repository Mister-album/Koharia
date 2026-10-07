package koharia.connection

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/** Verifies addresses directly, without routing or falling back to another address. */
class ConnectionAddressVerification(networkClient: OkHttpClient) {
    enum class Provider { KOMGA, LANRARAGI }
    enum class Reason {
        UNAVAILABLE,
        AUTHENTICATION,
        PERMISSION,
        TIMEOUT,
        CERTIFICATE,
        RESPONSE,
        REDIRECT,
        MISMATCH,
        CLEANUP,
    }
    enum class Stage { AUTHENTICATION, WRITE, IDENTITY, CLEANUP }
    class Failure(
        val reason: Reason,
        val marker: String = "",
        val endpoint: ConnectionValidation.Endpoint? = null,
        val status: Int? = null,
        cause: Throwable? = null,
        val stage: Stage? = null,
    ) : IOException(reason.name, cause) {
        fun userMessage(context: android.content.Context): String {
            val target = context.stringResource(
                when (endpoint) {
                    ConnectionValidation.Endpoint.PUBLIC -> tachiyomi.i18n.MR.strings.connection_public_address
                    ConnectionValidation.Endpoint.INTERNAL -> tachiyomi.i18n.MR.strings.connection_internal_address
                    null -> tachiyomi.i18n.MR.strings.connection_verify_target
                },
            )
            return context.stringResource(
                when (reason) {
                    Reason.AUTHENTICATION -> tachiyomi.i18n.MR.strings.connection_verify_auth_failed
                    Reason.PERMISSION -> tachiyomi.i18n.MR.strings.connection_verify_permission
                    Reason.TIMEOUT -> tachiyomi.i18n.MR.strings.connection_verify_timeout
                    Reason.CERTIFICATE -> tachiyomi.i18n.MR.strings.connection_verify_certificate
                    Reason.RESPONSE -> tachiyomi.i18n.MR.strings.connection_verify_response
                    Reason.REDIRECT -> tachiyomi.i18n.MR.strings.connection_verify_redirect
                    Reason.UNAVAILABLE -> tachiyomi.i18n.MR.strings.connection_verify_unavailable
                    Reason.MISMATCH -> tachiyomi.i18n.MR.strings.connection_verify_mismatch
                    Reason.CLEANUP -> tachiyomi.i18n.MR.strings.connection_verify_cleanup
                },
                if (reason == Reason.CLEANUP) marker else target,
            )
        }
    }

    private val client = ConnectionValidation.client(networkClient)

    suspend fun verify(provider: Provider, publicAddress: String, internalAddress: String, headers: Headers) {
        try {
            withTimeout(30_000) { verifyDirect(provider, publicAddress, internalAddress, headers) }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw Failure(Reason.TIMEOUT, cause = error)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private suspend fun verifyDirect(
        provider: Provider,
        publicAddress: String,
        internalAddress: String,
        headers: Headers,
    ) {
        val public = ConnectionAddressRouter.normalize(publicAddress)
            ?: throw Failure(Reason.UNAVAILABLE, endpoint = ConnectionValidation.Endpoint.PUBLIC)
        val internal = internalAddress.takeIf { it.isNotBlank() }?.let {
            ConnectionAddressRouter.normalize(it)
                ?: throw Failure(Reason.UNAVAILABLE, endpoint = ConnectionValidation.Endpoint.INTERNAL)
        }
        if (provider == Provider.LANRARAGI) {
            if (internal == null && headers["Authorization"].isNullOrBlank()) {
                ConnectionValidation.at(ConnectionValidation.Endpoint.PUBLIC) {
                    if (send(public, "api/info", headers) !is JsonObject) throw Failure(Reason.RESPONSE)
                }
                return
            }
            for ((address, endpoint) in listOfNotNull(
                public to ConnectionValidation.Endpoint.PUBLIC,
                internal?.takeIf { it != public }?.let { it to ConnectionValidation.Endpoint.INTERNAL },
            )) {
                ConnectionValidation.at(endpoint) {
                    if (headers["Authorization"].isNullOrBlank()) throw Failure(Reason.AUTHENTICATION)
                    if (send(address, "api/plugins/metadata", headers) !is JsonArray) throw Failure(Reason.RESPONSE)
                }
            }
            return
        }
        ConnectionValidation.at(ConnectionValidation.Endpoint.PUBLIC) {
            settings(public, headers)
        }
        if (internal == null || public == internal) return
        ConnectionValidation.at(ConnectionValidation.Endpoint.INTERNAL) {
            settings(internal, headers)
        }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val marker = "koharia.connection.verify.n$nonce"
        var writeAttempted = false
        var writeConfirmed = false
        var failure: Exception? = null
        try {
            // Let an in-flight write finish before cancellation starts cleanup.
            withContext(NonCancellable) {
                writeAttempted = true
                try {
                    ConnectionValidation.at(ConnectionValidation.Endpoint.PUBLIC, Stage.WRITE) {
                        send(
                            public,
                            "api/v1/client-settings/user",
                            headers,
                            "PATCH",
                            buildJsonObject { put(marker, buildJsonObject { put("value", nonce) }) }.body(),
                        )
                    }
                } catch (error: Failure) {
                    if (error.status == 401 || error.status == 403) writeAttempted = false
                    throw error
                }
                writeConfirmed = true
            }
            val settings = ConnectionValidation.at(ConnectionValidation.Endpoint.INTERNAL, Stage.IDENTITY) {
                settings(internal, headers)
            }
            if (settings[marker]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull != nonce) {
                throw Failure(Reason.MISMATCH)
            }
        } catch (error: Exception) {
            failure = error
            if (error is CancellationException || error is Failure) throw error
            throw Failure(Reason.UNAVAILABLE)
        } finally {
            if (writeAttempted) {
                val cleanupResult = withContext(NonCancellable) {
                    runCatching {
                        withTimeout(20_000) {
                            try {
                                cleanup(public, headers, marker, nonce, writeConfirmed)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                cleanup(public, headers, marker, nonce, writeConfirmed)
                            }
                        }
                    }
                }
                cleanupResult.exceptionOrNull()?.let { cleanupError ->
                    throw Failure(
                        Reason.CLEANUP,
                        marker,
                        ConnectionValidation.Endpoint.PUBLIC,
                        cause = cleanupError,
                        stage = Stage.CLEANUP,
                    )
                        .also { cleanupFailure -> failure?.let(cleanupFailure::addSuppressed) }
                }
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
        var current = settings(base, headers)[marker]
        if (!writeConfirmed) {
            // A timed-out write may still commit. Absence alone cannot confirm cleanup.
            repeat(8) {
                if (current == null) {
                    delay(500)
                    current = settings(base, headers)[marker]
                }
            }
            checkNotNull(current)
        }
        val value = current ?: return
        check(value.jsonObject["value"]?.jsonPrimitive?.contentOrNull == nonce)
        send(base, path, headers, "DELETE", JsonArray(listOf(JsonPrimitive(marker))).body())
        check(marker !in settings(base, headers))
    }

    private suspend fun settings(base: HttpUrl, headers: Headers): JsonObject =
        send(base, "api/v1/client-settings/user/list", headers) as? JsonObject ?: throw Failure(Reason.RESPONSE)

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
            if (!response.isSuccessful) {
                throw Failure(
                    when (response.code) {
                        401 -> Reason.AUTHENTICATION
                        403 -> Reason.PERMISSION
                        in 300..399 -> Reason.REDIRECT
                        else -> Reason.UNAVAILABLE
                    },
                    status = response.code,
                )
            }
            val text = response.body.string()
            if (method == "GET" && text.isBlank()) throw Failure(Reason.RESPONSE)
            val value = if (text.isBlank()) {
                JsonObject(emptyMap())
            } else {
                try {
                    Json.parseToJsonElement(text)
                } catch (_: kotlinx.serialization.SerializationException) {
                    throw Failure(Reason.RESPONSE)
                }
            }
            if ((value as? JsonObject)?.get("success")?.jsonPrimitive?.contentOrNull in listOf("false", "0")) {
                throw Failure(Reason.UNAVAILABLE)
            }
            value
        }
    }

    private fun JsonElement.body(): RequestBody = toString().toRequestBody("application/json".toMediaType())
}
