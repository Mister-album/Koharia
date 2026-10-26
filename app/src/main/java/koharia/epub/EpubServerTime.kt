package koharia.epub

import koharia.domain.epub.model.EpubRemoteProgressCache
import kotlin.math.abs
import kotlin.math.roundToLong

internal fun EpubRemoteProgressCache.serverTimeOffsetMinutes(): Long? {
    // HTTP Date is a sample taken when checkedAt was recorded, not a live server clock.
    val serverTime = serverDate?.time?.takeIf { it > 0 } ?: return null
    if (checkedAt.time <= 0) return null
    val offsetMillis = serverTime - checkedAt.time
    if (abs(offsetMillis) < 5 * 60 * 1_000L) return null
    return (abs(offsetMillis) / 60_000.0).roundToLong().coerceAtLeast(1L)
}
