package koharia.smanga

import java.io.IOException

class SmangaException(val reason: Reason, val status: Int? = null) : IOException(reason.name) {
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
