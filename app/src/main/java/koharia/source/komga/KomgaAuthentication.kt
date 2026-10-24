package koharia.source.komga

import okhttp3.Credentials
import okhttp3.Request

internal fun Request.withKomgaAuthentication(
    mode: String,
    username: String,
    password: String,
    apiKey: String,
): Request = newBuilder().apply {
    // Readium already supplies authentication. Replace it instead of emitting duplicate headers,
    // which reverse proxies can reject before the request reaches Komga.
    if (mode == "ApiKey" && apiKey.isNotBlank()) {
        header("X-Komga-Api-Key", apiKey)
    } else if (mode == "Credentials" && username.isNotBlank() && password.isNotBlank()) {
        header("Authorization", Credentials.basic(username, password))
    }
}.build()
