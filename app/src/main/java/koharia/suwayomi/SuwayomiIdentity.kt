package koharia.suwayomi

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8

data class SuwayomiIdentity(val connectionId: Long, val account: String) {
    val prefix = "/suwayomi/$connectionId/$account/"
    fun mangaUrl(id: Int) = "${prefix}manga/$id"
    fun chapterUrl(mangaId: Int, chapterId: Int) = "${prefix}chapter/$mangaId/$chapterId"
    fun imageUrl(url: String, version: String? = null): String = url.toHttpUrl().newBuilder()
        .setQueryParameter("kohariaScope", "$connectionId/$account")
        .apply { version?.let { setQueryParameter("kohariaPages", it) } }
        .build().toString()
    fun mangaId(url: String): Int {
        require(url.startsWith("${prefix}manga/")) { "Manga belongs to another Suwayomi account" }
        return url.removePrefix("${prefix}manga/").toInt().also { require(it > 0) }
    }
    fun chapter(url: String): Pair<Int, Int> {
        require(url.startsWith("${prefix}chapter/")) { "Chapter belongs to another Suwayomi account" }
        val parts = url.removePrefix("${prefix}chapter/").split('/')
        require(parts.size == 2)
        return parts[0].toInt().also { require(it > 0) } to parts[1].toInt().also { require(it > 0) }
    }

    companion object {
        fun account(address: String, mode: SuwayomiAuthMode, username: String): String =
            "${SuwayomiApi.normalizeBase(address)}\n${mode.name}\n${username.trim()}".encodeUtf8().sha256().hex()

        fun fromMangaUrl(url: String): SuwayomiIdentity? = runCatching {
            val parts = url.split('/')
            require(parts.size == 6 && parts[1] == "suwayomi" && parts[4] == "manga")
            require(parts[3].matches(Regex("[a-f0-9]{64}")))
            SuwayomiIdentity(parts[2].toLong(), parts[3]).also { it.mangaId(url) }
        }.getOrNull()
    }
}
