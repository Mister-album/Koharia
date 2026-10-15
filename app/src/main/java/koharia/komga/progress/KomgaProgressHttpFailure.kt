package koharia.komga.progress

import eu.kanade.tachiyomi.network.HttpException
import koharia.domain.komga.KomgaReadStatusPhase

internal fun HttpException.progressFailurePhase(): KomgaReadStatusPhase? = when (code) {
    401, 403 -> KomgaReadStatusPhase.DENIED
    404, 410 -> KomgaReadStatusPhase.HELD
    else -> null
}

internal fun KomgaReadStatusPhase.failureResult(): KomgaProgressDispatchResult = when (this) {
    KomgaReadStatusPhase.DENIED -> KomgaProgressDispatchResult.DENIED
    KomgaReadStatusPhase.HELD -> KomgaProgressDispatchResult.HELD
    else -> error("Not a terminal HTTP failure")
}
