package koharia.epub.progress

import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.GET
import koharia.connection.SharedAppPreferences
import koharia.epub.alignToEpubPositions
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import koharia.source.komga.awaitKomgaProgressSuccess
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import koharia.source.komga.KomgaProgressSession as Session

class KomgaEpubProgressSyncService(
    private val basePreferences: BasePreferences,
    private val sourceManager: SourceManager,
    private val sharedAppPreferences: SharedAppPreferences,
) {

    private val recentPushes = ConcurrentHashMap<ProgressionKey, RecentPush>()

    internal fun captureSession(sourceId: Long): Session =
        captureSession(sourceManager.get(sourceId) as? KomgaSource ?: throw KomgaProgressOwnerChanged())

    internal fun captureSession(source: KomgaSource): Session =
        koharia.source.komga.captureKomgaProgressSession(source, sourceManager)

    suspend fun pullProgression(
        sourceId: Long,
        bookUrl: String,
    ): PullResult = pullProgression(captureSession(sourceId), bookUrl)

    internal suspend fun pullProgression(
        session: Session,
        bookUrl: String,
        checkCurrent: suspend () -> Unit = session.guard::requireCurrent,
    ): PullResult = withIOContext {
        checkCurrent()
        val normalizedBookUrl = normalizeBookUrl(bookUrl)
        session.source.client.newCall(
            session.request(
                GET("$normalizedBookUrl/progression").newBuilder().cacheControl(CacheControl.FORCE_NETWORK),
            ),
        )
            .awaitKomgaProgressSuccess()
            .use { response ->
                val serverDate = response.header("Date")?.let(::parseHttpDate)
                val body = response.body.string().trim()
                checkCurrent()
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
                        session = session,
                        normalizedBookUrl = normalizedBookUrl,
                        rawModifiedAt = it,
                        serverDate = serverDate,
                        checkCurrent = checkCurrent,
                    )
                }
                val progression = if (locator != null && modifiedAt != null) {
                    RemoteProgression(locator = locator, modifiedAt = modifiedAt)
                } else {
                    null
                }

                checkCurrent()
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
    ) = pushProgression(captureSession(sourceId), bookUrl, locator, positions, modifiedAt)

    internal suspend fun pushProgression(
        session: Session,
        bookUrl: String,
        locator: Locator,
        positions: List<Locator>,
        modifiedAt: Date,
        checkCurrent: suspend () -> Unit = session.guard::requireCurrent,
    ) = withIOContext {
        checkCurrent()
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
        val request = session.request(
            Request.Builder()
                .url("$normalizedBookUrl/progression")
                .put(payload.toString().toRequestBody("application/json".toMediaType())),
        )

        session.source.client.newCall(request).awaitKomgaProgressSuccess().use {
            checkCurrent()
            if (sharedAppPreferences.epubReaderPreferences()
                    .correctRemoteServerTimestamps.get()
            ) {
                recentPushes[ProgressionKey(session.sourceId, session.account.key, normalizedBookUrl)] = RecentPush(
                    modifiedAtMillis = modifiedAt.time,
                    recordedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun correctRemoteModifiedAt(
        session: Session,
        normalizedBookUrl: String,
        rawModifiedAt: Date,
        serverDate: Date?,
        checkCurrent: suspend () -> Unit,
    ): Date {
        checkCurrent()
        val sourceId = session.sourceId
        val preferences = sharedAppPreferences.epubReaderPreferences()
        if (!preferences.correctRemoteServerTimestamps.get()) return rawModifiedAt

        val offsetPreference = preferences.remoteServerTimestampOffsetMinutes(sourceId, session.account.key)
        val storedOffsetMinutes = offsetPreference.get()
        val key = ProgressionKey(sourceId, session.account.key, normalizedBookUrl)
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
            checkCurrent()
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
        val ownerKey: String,
        val bookUrl: String,
    )

    private data class RecentPush(
        val modifiedAtMillis: Long,
        val recordedAtMillis: Long,
    )

    private companion object {
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
    return alignToEpubPositions(positions)
}
