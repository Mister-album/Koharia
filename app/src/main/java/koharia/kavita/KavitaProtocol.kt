package koharia.kavita

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

class KavitaException(val reason: Reason, val status: Int? = null) :
    IOException("Kavita ${reason.name}${status?.let { " ($it)" }.orEmpty()}") {
    enum class Reason {
        ADDRESS,
        AUTHENTICATION,
        PERMISSION,
        VERSION,
        PROTOCOL,
        NETWORK,
        ACCOUNT_CHANGED,
        UNSUPPORTED,
        CONFLICT,
        FILTER,
    }
}

data class KavitaEndpoint(val base: HttpUrl, val importedKey: String?) {
    companion object {
        fun parse(value: String): KavitaEndpoint {
            val url = runCatching {
                value.trim().toHttpUrl()
            }.getOrElse { throw KavitaException(KavitaException.Reason.ADDRESS) }
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.query != null) {
                throw KavitaException(KavitaException.Reason.ADDRESS)
            }
            val parts = url.pathSegments.filter(String::isNotBlank)
            val opds = parts.indices.firstOrNull {
                it + 2 < parts.size && parts[it].equals("api", true) &&
                    parts[it + 1].equals("opds", true)
            }
            val path = if (opds != null) {
                if (parts.size != opds + 3) throw KavitaException(KavitaException.Reason.ADDRESS)
                parts.take(opds)
            } else {
                parts
            }
            val base = url.newBuilder().encodedPath("/").apply {
                path.forEach(::addPathSegment)
                addPathSegment("")
            }.build()
            return KavitaEndpoint(base, opds?.let { parts[it + 2] })
        }
    }
}

data class KavitaVersion(val major: Int, val minor: Int, val patch: Int = 0) : Comparable<KavitaVersion> {
    override fun compareTo(
        other: KavitaVersion,
    ) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): KavitaVersion? {
            val parts = value.trim().removePrefix("v").substringBefore('-').split('.')
            if (parts.size < 2) return null
            return KavitaVersion(
                parts[0].toIntOrNull() ?: return null,
                parts[1].toIntOrNull() ?: return null,
                parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
        }
    }
}

data class KavitaCapabilities(val version: KavitaVersion, val roles: Set<String>) {
    val downloads get() = roles.any { it.equals("Admin", true) || it.equals("Download", true) }
    val writable get() = roles.isNotEmpty() && roles.none { it.replace(" ", "").equals("ReadOnly", true) }
    val authKeys get() = version >= KavitaVersion(0, 9)
    val annotations get() = version >= KavitaVersion(0, 8, 8)
}

class KavitaIdentity(val connectionId: Long, val account: String) {
    init {
        require(connectionId > 0 && account.matches(Regex("[a-zA-Z0-9-]+")))
    }
    val prefix = "/kavita/$connectionId/$account/"
    fun series(id: Long): String {
        require(id > 0)
        return "${prefix}series/$id"
    }
    fun seriesId(url: String): Long {
        require(url.startsWith("${prefix}series/"))
        return url.removePrefix("${prefix}series/").toLong().also { require(it > 0) }
    }
    fun chapter(ref: KavitaChapterRef): String =
        "${prefix}chapter/${ref.libraryId}/${ref.seriesId}/${ref.volumeId}/${ref.chapterId}.${ref.extension}"
    fun chapter(url: String): KavitaChapterRef {
        require(url.startsWith("${prefix}chapter/"))
        val parts = url.removePrefix("${prefix}chapter/").split('/')
        require(parts.size == 4)
        val format = when (parts[3].substringAfterLast('.')) {
            "epub" -> 3
            "pdf" -> 4
            "pages" -> 1
            else -> error("Invalid chapter format")
        }
        val ids = parts.take(3).map(String::toLong) + parts[3].substringBefore('.').toLong()
        require(ids.all { it > 0 })
        return KavitaChapterRef(ids[0], ids[1], ids[2], ids[3], format)
    }
}
