package koharia.storage

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Splits the WebDAV server endpoint from the library folder below it, so the connection wizard can
 * authenticate against the server and choose the folder in a later step. Mirrors [SmbAddress].
 */
internal data class WebDavAddress(val endpoint: String, val root: String) {
    /** Applies [path] to the endpoint, replacing any folder the address already carried. */
    fun at(path: String): String {
        val segments = StoragePath.normalize(path).split('/').filter(String::isNotEmpty)
        return endpoint.toHttpUrl().newBuilder().apply {
            segments.forEach(::addPathSegment)
        }.build().toString()
    }

    companion object {
        fun parse(value: String): WebDavAddress {
            val url = value.trim().toHttpUrl()
            require(url.scheme == "http" || url.scheme == "https")
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            val root = StoragePath.normalize(url.pathSegments.joinToString("/"))
            return WebDavAddress(url.newBuilder().encodedPath("/").build().toString(), root)
        }

        fun of(value: String): WebDavAddress? = runCatching { parse(value) }.getOrNull()
    }
}

private fun webDavBackend(address: String, username: String, password: String) = WebDavStorageBackend(
    Injekt.get<NetworkHelper>().nonCloudflareClient,
    address,
    username,
    password,
)

/**
 * Server-level credential check. Mirrors [authenticateSmbServer]: it deliberately does not require
 * the endpoint root to be the DAV collection, because the library folder is chosen in a later step.
 */
internal suspend fun authenticateWebDavServer(address: String, username: String, password: String) =
    withContext(Dispatchers.IO) {
        val endpoint = WebDavAddress.parse(address).endpoint
        WebDavStorageBackend(
            koharia.connection.ConnectionValidation.client(Injekt.get<NetworkHelper>().nonCloudflareClient),
            endpoint,
            username,
            password,
        ).use { it.checkEndpointReachable() }
    }

/** Lists folders below the server endpoint, so the DAV root is reachable without typing its path first. */
internal suspend fun browseWebDavServer(
    address: String,
    username: String,
    password: String,
    path: String,
): List<StorageEntry> = withContext(Dispatchers.IO) {
    val endpoint = WebDavAddress.parse(address).endpoint
    webDavBackend(endpoint, username, password).use { browseStorageDirectories(it, path) }
}
