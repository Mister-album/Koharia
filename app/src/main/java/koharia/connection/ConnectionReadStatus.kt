package koharia.connection

import eu.kanade.tachiyomi.network.HttpException

/** PENDING is returned only after the provider has durably stored the explicit operation. */
enum class ConnectionReadStatus(val allowsDownloadDeletion: Boolean) {
    SYNCED(true),
    LOCAL_ONLY(true),
    PENDING(true),
    HELD(false),
    CONFLICT(false),
    DENIED(false),
    UNAVAILABLE(false),
}

class ConnectionReadStatusException(val status: ConnectionReadStatus) :
    IllegalStateException("Read status: ${status.name}")

internal fun Throwable.readStatusFailure(): ConnectionReadStatus {
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    causes.filterIsInstance<ConnectionReadStatusException>().firstOrNull()?.let { return it.status }
    if (causes.any {
            (it as? HttpException)?.code in setOf(401, 403) ||
                (it as? ConnectionValidationError)?.validationReason in setOf(
                    ConnectionAddressVerification.Reason.AUTHENTICATION,
                    ConnectionAddressVerification.Reason.PERMISSION,
                )
        }
    ) {
        return ConnectionReadStatus.DENIED
    }
    return ConnectionReadStatus.UNAVAILABLE
}
