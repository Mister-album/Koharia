package koharia.kavita

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Includes file membership and order; a same-page-count replacement must also change identity. */
internal fun kavitaContentVersion(chapter: KavitaChapter): String =
    ("${chapter.lastModifiedUtc}/${chapter.pages}/" + Json.encodeToString(chapter.files)).encodeUtf8().sha256().hex()
