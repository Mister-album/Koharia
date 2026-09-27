package koharia.kavita

import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException

internal data class KavitaEpubResource(val bytes: ByteArray, val mediaType: String, val url: HttpUrl)

internal suspend fun fetchKavitaEpubResource(api: KavitaApiClient, chapterId: Long, url: HttpUrl): KavitaEpubResource {
    require(ConnectionAddressRouter.owns(api.virtualBase, url))
    require(isKavitaEpubResourceUrl(api, chapterId, url))
    val file = requireNotNull(url.queryParameter("file"))
    // Some Kavita pages prefix CSS paths with the OPF directory while book-resources expects OPF-relative keys.
    val candidates = buildList {
        add(file)
        if (!file.startsWith("../")) {
            var suffix = file
            repeat(4) {
                if ('/' in suffix) {
                    suffix = suffix.substringAfter('/')
                    add(suffix)
                }
            }
        }
    }.distinct()
    for ((index, candidate) in candidates.withIndex()) {
        val target = api.url("Book/$chapterId/book-resources", "file" to candidate)
        api.client.newCall(Request.Builder().url(target).build()).await().use { response ->
            if (response.code in listOf(400, 404) && index < candidates.lastIndex) return@use
            KavitaApiClient.checkResponse(response)
            val bytes = response.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 32 * 1024 * 1024) throw IOException("EPUB resource too large")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val type = when (candidate.substringAfterLast('.', "").lowercase()) {
                "css" -> "text/css"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> response.body.contentType()?.toString() ?: "application/octet-stream"
            }
            return KavitaEpubResource(bytes, type, target)
        }
    }
    throw IOException("EPUB resource missing")
}

internal val kavitaCssUrls = Regex("""url\(\s*(['"]?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)

internal fun rewriteKavitaCss(css: String, api: KavitaApiClient, chapterId: Long, stylesheet: HttpUrl): String {
    fun resolve(value: String): String {
        if (value.startsWith("data:", true) || value.startsWith('#')) return value
        if (value.startsWith("//") || value.contains("://") || value.startsWith('/')) {
            val url = api.canonicalUrl(api.base.resolve(value) ?: return "")
            if (url.host != api.base.host ||
                !isKavitaEpubResourceUrl(api, chapterId, url)
            ) {
                return ""
            }
            return kavitaEpubResourceUrl(api, chapterId, url.queryParameter("file") ?: return "").toString()
        }
        val file = stylesheet.queryParameter("file") ?: return ""
        val base = okhttp3.HttpUrl.Builder().scheme("https").host("epub.invalid").addPathSegments(file).build()
        val path = base.resolve(value)?.encodedPath?.removePrefix("/") ?: return ""
        return kavitaEpubResourceUrl(
            api,
            chapterId,
            java.net.URI("https://epub.invalid/$path").path.removePrefix("/"),
        ).toString()
    }
    val urls = kavitaCssUrls.replace(css) { "url(\"${resolve(it.groupValues[2])}\")" }
    return Regex("""@import\s+(['"])(.*?)\1""", RegexOption.IGNORE_CASE).replace(urls) {
        "@import url(\"${resolve(it.groupValues[2])}\")"
    }
}

// Readium's WebView server infers resource MIME from the URL path, not the HTTP response.
internal fun kavitaEpubResourceUrl(api: KavitaApiClient, chapterId: Long, file: String): HttpUrl {
    val suffix = file.substringAfterLast('.', "").lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
    return api.url("Book/$chapterId/book-resources", "file" to file).newBuilder()
        .addPathSegment("resource.$suffix").build()
}

internal fun isKavitaEpubResourceUrl(api: KavitaApiClient, chapterId: Long, url: HttpUrl): Boolean {
    val path = api.url("Book/$chapterId/book-resources").encodedPath
    return url.encodedPath.equals(path, true) ||
        url.encodedPath.equals(
            kavitaEpubResourceUrl(api, chapterId, url.queryParameter("file") ?: "").encodedPath,
            true,
        )
}
