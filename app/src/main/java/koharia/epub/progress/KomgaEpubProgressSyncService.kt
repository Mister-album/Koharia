package koharia.epub.progress

import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import koharia.source.komga.KomgaSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.service.SourceManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

class KomgaEpubProgressSyncService(
    private val basePreferences: BasePreferences,
    private val sourceManager: SourceManager,
) {

    suspend fun pullProgression(
        sourceId: Long,
        bookUrl: String,
    ): RemoteProgression? = withIOContext {
        val source = sourceManager.get(sourceId) as? KomgaSource ?: return@withIOContext null
        val normalizedBookUrl = normalizeBookUrl(bookUrl)
        source.client.newCall(GET("$normalizedBookUrl/progression", source.currentReadiumHeaders()))
            .awaitSuccess()
            .use { response ->
                val body = response.body.string().trim()
                if (body.isBlank() || body == "\"\"") {
                    return@withIOContext null
                }

                val json = JSONObject(body)
                val locator = json.optJSONObject("locator")?.let(Locator::fromJSON) ?: return@withIOContext null
                val modifiedAt = json.optString("modified")
                    .takeIf(String::isNotBlank)
                    ?.let(::parseDate)
                    ?: return@withIOContext null

                RemoteProgression(
                    locator = locator,
                    modifiedAt = modifiedAt,
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
            put("locator", locator.toJSON())
            put("modified", modifiedAt.toInstant().toString())
        }
        val request = Request.Builder()
            .url("$normalizedBookUrl/progression")
            .headers(source.currentReadiumHeaders())
            .put(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        source.client.newCall(request).awaitSuccess().close()
    }

    private fun normalizeBookUrl(bookUrl: String): String = bookUrl.substringBefore('#').removeSuffix("/")

    private fun parseDate(value: String): Date? {
        return runCatching { Date.from(Instant.parse(value)) }
            .recoverCatching { Date.from(OffsetDateTime.parse(value).toInstant()) }
            .getOrNull()
    }

    private fun buildDeviceName(): String {
        return listOfNotNull(
            Build.MANUFACTURER?.trim().takeIf { !it.isNullOrBlank() },
            Build.MODEL?.trim().takeIf { !it.isNullOrBlank() },
        ).joinToString(" ").ifBlank { "Android" }
    }

    data class RemoteProgression(
        val locator: Locator,
        val modifiedAt: Date,
    )
}
