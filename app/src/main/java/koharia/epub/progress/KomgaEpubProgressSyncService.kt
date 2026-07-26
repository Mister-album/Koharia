package koharia.epub.progress

import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import koharia.source.komga.KomgaSource
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong

class KomgaEpubProgressSyncService(
    private val basePreferences: BasePreferences,
    private val sourceManager: SourceManager,
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory,
) {

    private val recentPushes = ConcurrentHashMap<ProgressionKey, RecentPush>()

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
                val rawModifiedAt = json.optString("modified")
                    .takeIf(String::isNotBlank)
                    ?.let(::parseDate)
                val modifiedAt = rawModifiedAt?.let {
                    correctRemoteModifiedAt(
                        sourceId = sourceId,
                        normalizedBookUrl = normalizedBookUrl,
                        rawModifiedAt = it,
                        serverDate = serverDate,
                    )
                }
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
        positions: List<Locator>,
        modifiedAt: Date,
    ) = withIOContext {
        val source = sourceManager.get(sourceId) as? KomgaSource ?: return@withIOContext
        val normalizedBookUrl = normalizeBookUrl(bookUrl)
        val alignedLocator = locator.alignToKomgaPositions(positions)
        if (alignedLocator !== locator) {
            logcat(LogPriority.DEBUG) {
                "Aligned Komga EPUB progression href=${locator.href} " +
                    "from=${locator.locations.progression} " +
                    "to=${alignedLocator.locations.progression}"
            }
        }
        val payload = JSONObject().apply {
            put(
                "device",
                JSONObject().apply {
                    put("id", basePreferences.installationId.get().ifBlank { "koharia-${Build.DEVICE}" })
                    put("name", buildDeviceName())
                },
            )
            put("locator", alignedLocator.toKomgaJSON())
            put("modified", modifiedAt.toInstant().toString())
        }
        val request = Request.Builder()
            .url("$normalizedBookUrl/progression")
            .headers(source.currentReadiumHeaders())
            .put(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        source.client.newCall(request).await().use { response ->
            response.requireSuccess("push")
            if (scopedPreferenceStoreFactory.epubReaderPreferences(sourceId)
                    .correctKomgaServerTimestamps.get()
            ) {
                recentPushes[ProgressionKey(sourceId, normalizedBookUrl)] = RecentPush(
                    modifiedAtMillis = modifiedAt.time,
                    recordedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun correctRemoteModifiedAt(
        sourceId: Long,
        normalizedBookUrl: String,
        rawModifiedAt: Date,
        serverDate: Date?,
    ): Date {
        val preferences = scopedPreferenceStoreFactory.epubReaderPreferences(sourceId)
        if (!preferences.correctKomgaServerTimestamps.get()) return rawModifiedAt

        val offsetPreference = preferences.komgaServerTimestampOffsetMinutes(sourceId)
        val storedOffsetMinutes = offsetPreference.get()
        val key = ProgressionKey(sourceId, normalizedBookUrl)
        val now = System.currentTimeMillis()
        val observedRecentPush = recentPushes[key]
        val recentPush = observedRecentPush
            ?.takeIf { now - it.recordedAtMillis <= RECENT_PUSH_EVIDENCE_TTL_MS }
        if (observedRecentPush != null && recentPush == null) {
            recentPushes.remove(key, observedRecentPush)
        }

        val pushedOffsetMinutes = recentPush?.let {
            inferWholeHourOffsetMinutes(rawModifiedAt.time - it.modifiedAtMillis)
        }
        val serverOffsetMinutes = serverDate
            ?.takeIf { rawModifiedAt.time - it.time >= MIN_FUTURE_DRIFT_MS }
            ?.let { inferWholeHourOffsetMinutes(rawModifiedAt.time - it.time) }
        val inferredOffsetMinutes = pushedOffsetMinutes ?: serverOffsetMinutes

        val remoteMatchesRecentPush = recentPush != null &&
            abs(rawModifiedAt.time - recentPush.modifiedAtMillis) <= OFFSET_EVIDENCE_TOLERANCE_MS
        val appliedOffsetMinutes = when {
            inferredOffsetMinutes != null -> inferredOffsetMinutes
            remoteMatchesRecentPush -> 0L
            else -> storedOffsetMinutes
        }

        if (appliedOffsetMinutes != storedOffsetMinutes) {
            offsetPreference.set(appliedOffsetMinutes)
        }
        if (appliedOffsetMinutes == 0L) {
            if (storedOffsetMinutes != 0L && remoteMatchesRecentPush) {
                logcat(LogPriority.DEBUG) {
                    "Cleared stale Komga timestamp correction sourceId=$sourceId " +
                        "storedOffsetMinutes=$storedOffsetMinutes"
                }
            }
            return rawModifiedAt
        }

        val corrected = Date(rawModifiedAt.time - appliedOffsetMinutes * MINUTE_MS)
        logcat(LogPriority.DEBUG) {
            "Corrected Komga progression timestamp sourceId=$sourceId " +
                "raw=${rawModifiedAt.time} serverDate=${serverDate?.time} " +
                "recentPush=${recentPush?.modifiedAtMillis} " +
                "offsetMinutes=$appliedOffsetMinutes corrected=${corrected.time}"
        }
        return corrected
    }

    private fun inferWholeHourOffsetMinutes(differenceMillis: Long): Long? {
        val roundedHours = (differenceMillis.toDouble() / HOUR_MS).roundToLong()
        if (roundedHours == 0L || abs(roundedHours) > MAX_CORRECTION_HOURS) return null
        val roundedDifference = roundedHours * HOUR_MS
        if (abs(differenceMillis - roundedDifference) > OFFSET_EVIDENCE_TOLERANCE_MS) return null
        return roundedHours * MINUTES_PER_HOUR
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

        val details = runCatching { peekBody(MAX_ERROR_BODY_LENGTH.toLong()).string() }
            .getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
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

    private data class ProgressionKey(
        val sourceId: Long,
        val bookUrl: String,
    )

    private data class RecentPush(
        val modifiedAtMillis: Long,
        val recordedAtMillis: Long,
    )

    private companion object {
        const val MAX_ERROR_BODY_LENGTH = 512
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val MINUTES_PER_HOUR = 60L
        const val MIN_FUTURE_DRIFT_MS = 30 * MINUTE_MS
        const val OFFSET_EVIDENCE_TOLERANCE_MS = 5 * MINUTE_MS
        const val RECENT_PUSH_EVIDENCE_TTL_MS = 5 * MINUTE_MS
        const val MAX_CORRECTION_HOURS = 24L
    }
}

internal fun Locator.alignToKomgaPositions(positions: List<Locator>): Locator {
    val targetProgression = locations.progression ?: return this
    val targetHref = href.toString()
    val resourcePositions = positions.mapNotNull { position ->
        if (!position.href.toString().isSameEpubResource(targetHref)) return@mapNotNull null
        val progression = position.locations.progression ?: return@mapNotNull null
        position to progression
    }
    if (resourcePositions.isEmpty()) return this

    val alignedPosition = resourcePositions
        .filter { (_, progression) -> progression <= targetProgression }
        .maxByOrNull { (_, progression) -> progression }
        ?: resourcePositions.minBy { (_, progression) -> progression }
    val alignedProgression = alignedPosition.second
    if (alignedProgression == targetProgression) return this

    return copy(
        locations = locations.copy(
            progression = alignedProgression,
            position = alignedPosition.first.locations.position ?: locations.position,
            totalProgression = alignedPosition.first.locations.totalProgression
                ?: locations.totalProgression,
        ),
    )
}

private fun String.isSameEpubResource(other: String): Boolean {
    val first = resourceKey()
    val second = other.resourceKey()
    if (first.isBlank() || second.isBlank()) return false
    return first == second || first.endsWith("/$second") || second.endsWith("/$first")
}

private fun String.resourceKey(): String =
    substringBefore('#')
        .substringBefore('?')
        .trimStart('/')
