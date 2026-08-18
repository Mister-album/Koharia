package koharia.connection

import kotlinx.serialization.Serializable

@Serializable
data class LibraryConnectionProfile(
    val id: Long,
    val providerId: String,
    val name: String,
)

enum class ConnectionConfigMode {
    Shared,
    Separate,
}

const val NO_ACTIVE_CONNECTION = -1L
