package koharia.smanga

import java.io.IOException

class SmangaException(val reason: Reason, val status: Int? = null) :
    IOException(reason.name), koharia.connection.ConnectionValidationError {
    override val validationStatus get() = status
    override val validationReason get() = when (reason) {
        Reason.AUTH -> koharia.connection.ConnectionAddressVerification.Reason.AUTHENTICATION
        Reason.PERMISSION -> koharia.connection.ConnectionAddressVerification.Reason.PERMISSION
        Reason.SERVER -> koharia.connection.ConnectionAddressVerification.Reason.UNAVAILABLE
        Reason.PROTOCOL -> koharia.connection.ConnectionAddressVerification.Reason.RESPONSE
        else -> null
    }
    enum class Reason {
        ADDRESS,
        AUTH,
        PERMISSION,
        OPDS_DISABLED,
        NOT_FOUND,
        SERVER,
        PROTOCOL,
        EMPTY,
        PREPARING,
        INCOMPLETE,
    }
}
