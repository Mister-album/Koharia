package koharia.epub.progress

import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import koharia.source.komga.KomgaSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.service.SourceManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

class KomgaEpubProgressSyncService(
    private val basePreferences: BasePreferences,
    private val sourceManager: SourceManager,
) {

    suspend fun pullProgression(
        sourceId: Long,
        bookUrl: String,
    ): PullResult = withIOContext {
        val source = sourceManager.get(sourceId) as? KomgaSource ?: return@withIOContext PullResult()
        val normalizedBookUrl = normalizeBookUrl(bookUrl)
        source.client.newCall(GET("$normalizedBookUrl/progression", source.currentReadiumHeaders()))
            .await()
            .use { response ->
                response.requireSuccess("pull")
                val serverDate = response.header("Date")?.let(::parseHttpDate)
                val body = response.body.string().trim()
                if (body.isBlank() || body == "\"\"") {
                    return@withIOContext PullResult(serverDate = serverDate)
                }

                val json = JSONObject(body)
                val locator = json.optJSONObject("locator")?.let(Locator::fromJSON)
                val modifiedAt = json.optString("modified")
                    .takeIf(String::isNotBlank)
                    ?.let(::parseDate)
                val progression = if (locator != null && modifiedAt != null) {
                    RemoteProgression(locator = locator, modifiedAt = modifiedAt)
                } else {
                    null
                }

                PullResult(
                    progression = progression,
                    serverDate = serverDate,
                )
            }
    }

    suspend fun pushProgression(
        sourceId: Long,
        bookUrl: String,
        locator: Locator,
        modifiedAt: Date,
    ) = withIOContext {
        val source = sourceManager.get(sourceId) as? KomgaSource ?: return@withIOContext
        val normalizedBookUrl = normalizeBookUrl(bookUrl)
        val payload = JSONObject().apply {
            put(
                "device",
                JSONObject().apply {
                    put("id", basePreferences.installationId.get().ifBlank { "koharia-${Build.DEVICE}" })
                    put("name", buildDeviceName())
                },
            )
            put("locator", locator.toKomgaJSON())
            put("modified", modifiedAt.toInstant().toString())
        }
        val request = Request.Builder()
            .url("$normalizedBookUrl/progression")
            .headers(source.currentReadiumHeaders())
            .put(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        source.client.newCall(request).await().use { response ->
            response.requireSuccess("push")
        }
    }

    private fun normalizeBookUrl(bookUrl: String): String = bookUrl.substringBefore('#').removeSuffix("/")

    private fun parseDate(value: String): Date? {
        return runCatching { Date.from(Instant.parse(value)) }
            .recoverCatching { Date.from(OffsetDateTime.parse(value).toInstant()) }
            .getOrNull()
    }

    private fun parseHttpDate(value: String): Date? {
        return runCatching {
            Date.from(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
        }.getOrNull()
    }

    private fun buildDeviceName(): String {
        return listOfNotNull(
            Build.MANUFACTURER?.trim().takeIf { !it.isNullOrBlank() },
            Build.MODEL?.trim().takeIf { !it.isNullOrBlank() },
        ).joinToString(" ").ifBlank { "Android" }
    }

    private fun Locator.toKomgaJSON(): JSONObject = toJSON().apply {
        val locations = optJSONObject("locations") ?: return@apply
        if (!locations.has("fragments")) {
            locations.put("fragments", JSONArray())
        }
    }

    private fun Response.requireSuccess(operation: String) {
        if (isSuccessful) return

        val details = body.string()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_ERROR_BODY_LENGTH)
            .takeIf(String::isNotBlank)
        throw IllegalStateException(
            buildString {
                append("Komga progression ")
                append(operation)
                append(" failed (HTTP ")
                append(code)
                append(')')
                if (details != null) {
                    append(": ")
                    append(details)
                }
            },
        )
    }

    data class PullResult(
        val progression: RemoteProgression? = null,
        val serverDate: Date? = null,
    )

    data class RemoteProgression(
        val locator: Locator,
        val modifiedAt: Date,
    )

    private companion object {
        const val MAX_ERROR_BODY_LENGTH = 512
    }
}
