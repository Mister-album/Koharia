package koharia.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Null means use the complete local cache; an empty list is an authoritative empty server result. */
internal suspend fun searchLanraragiWithFallback(
    online: Boolean,
    search: suspend () -> List<LanraragiEntry>,
): List<LanraragiEntry>? {
    if (!online) return null
    return try {
        withTimeoutOrNull(15_000) { search() }
    } catch (error: IOException) {
        if (!error.isLanraragiConnectivityFailure()) throw error
        null
    }
}

internal fun Throwable.isLanraragiConnectivityFailure(): Boolean = this is IOException &&
    (this !is LanraragiException || (reason == LanraragiException.Reason.SERVER && (status ?: 0) >= 500))
