package koharia.komga.api.dto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class KomgaOfflineAuthor(
    val name: String,
    val role: String,
)

internal data class KomgaOfflineFilterMetadata(
    val status: String? = null,
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val publisher: String? = null,
    val authors: Set<KomgaOfflineAuthor> = emptySet(),
    val titleSort: String? = null,
    val createdDate: String? = null,
    val lastModifiedDate: String? = null,
    val oneShot: Boolean? = null,
)

internal fun SeriesDto.offlineFilterMetadata() = KomgaOfflineFilterMetadata(
    status = metadata.status.takeIf(String::isNotBlank),
    genres = metadata.genres,
    tags = metadata.tags + booksMetadata.tags,
    publisher = metadata.publisher.takeIf(String::isNotBlank),
    authors = booksMetadata.authors.toOfflineAuthors(),
    titleSort = metadata.titleSort.takeIf(String::isNotBlank),
    createdDate = created ?: metadata.created,
    lastModifiedDate = lastModified ?: metadata.lastModified,
    oneShot = booksCount == 1,
)

internal fun BookDto.offlineFilterMetadata() = KomgaOfflineFilterMetadata(
    tags = metadata.tags,
    authors = metadata.authors.toOfflineAuthors(),
    titleSort = metadata.title.takeIf(String::isNotBlank),
    createdDate = created,
    lastModifiedDate = lastModified,
)

internal fun ReadListDto.offlineFilterMetadata() = KomgaOfflineFilterMetadata(
    titleSort = name.takeIf(String::isNotBlank),
    createdDate = createdDate,
    lastModifiedDate = lastModifiedDate,
)

internal fun JsonObject.withOfflineFilterMetadata(metadata: KomgaOfflineFilterMetadata): JsonObject {
    val offlineMetadata = JsonObject(
        buildMap {
            put(VERSION_FIELD, JsonPrimitive(CURRENT_VERSION))
            metadata.status?.let { put(STATUS_FIELD, JsonPrimitive(it)) }
            metadata.genres.takeIf(Set<String>::isNotEmpty)?.let { put(GENRES_FIELD, it.toJsonArray()) }
            metadata.tags.takeIf(Set<String>::isNotEmpty)?.let { put(TAGS_FIELD, it.toJsonArray()) }
            metadata.publisher?.let { put(PUBLISHER_FIELD, JsonPrimitive(it)) }
            metadata.authors.takeIf(Set<KomgaOfflineAuthor>::isNotEmpty)?.let { authors ->
                put(
                    AUTHORS_FIELD,
                    JsonArray(
                        authors.sortedWith(
                            compareBy(KomgaOfflineAuthor::role, KomgaOfflineAuthor::name),
                        ).map { author ->
                            JsonObject(
                                mapOf(
                                    AUTHOR_NAME_FIELD to JsonPrimitive(author.name),
                                    AUTHOR_ROLE_FIELD to JsonPrimitive(author.role),
                                ),
                            )
                        },
                    ),
                )
            }
            metadata.titleSort?.let { put(TITLE_SORT_FIELD, JsonPrimitive(it)) }
            metadata.createdDate?.let { put(CREATED_DATE_FIELD, JsonPrimitive(it)) }
            metadata.lastModifiedDate?.let { put(LAST_MODIFIED_DATE_FIELD, JsonPrimitive(it)) }
            metadata.oneShot?.let { put(ONE_SHOT_FIELD, JsonPrimitive(it)) }
        },
    )
    return JsonObject(this + (KOMGA_OFFLINE_FILTERS_MEMO_KEY to offlineMetadata))
}

internal fun JsonObject.offlineFilterMetadata(): KomgaOfflineFilterMetadata? {
    val metadata = this[KOMGA_OFFLINE_FILTERS_MEMO_KEY] as? JsonObject ?: return null
    if (metadata[VERSION_FIELD]?.jsonPrimitive?.intOrNull != CURRENT_VERSION) return null

    return KomgaOfflineFilterMetadata(
        status = metadata.string(STATUS_FIELD),
        genres = metadata.stringSet(GENRES_FIELD),
        tags = metadata.stringSet(TAGS_FIELD),
        publisher = metadata.string(PUBLISHER_FIELD),
        authors = (metadata[AUTHORS_FIELD] as? JsonArray)
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { element ->
                runCatching {
                    val author = element.jsonObject
                    val name = author.string(AUTHOR_NAME_FIELD) ?: return@runCatching null
                    val role = author.string(AUTHOR_ROLE_FIELD) ?: return@runCatching null
                    KomgaOfflineAuthor(name, role)
                }.getOrNull()
            },
        titleSort = metadata.string(TITLE_SORT_FIELD),
        createdDate = metadata.string(CREATED_DATE_FIELD),
        lastModifiedDate = metadata.string(LAST_MODIFIED_DATE_FIELD),
        oneShot = metadata[ONE_SHOT_FIELD]?.jsonPrimitive?.booleanOrNull,
    )
}

internal fun mergeKomgaOfflineMemo(localMemo: JsonObject, remoteMemo: JsonObject): JsonObject? {
    if (remoteMemo.isEmpty()) return null
    return if (KOMGA_OFFLINE_FILTERS_MEMO_KEY in remoteMemo) {
        val mergedMemo = localMemo + remoteMemo
        val remoteLibraryId = remoteMemo[KOMGA_LIBRARY_ID_MEMO_KEY]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
        JsonObject(
            if (remoteLibraryId != null) {
                mergedMemo + (KOMGA_LIBRARY_IDS_MEMO_KEY to JsonArray(listOf(JsonPrimitive(remoteLibraryId))))
            } else {
                mergedMemo
            },
        )
    } else {
        remoteMemo
    }
}

private fun List<AuthorDto>.toOfflineAuthors(): Set<KomgaOfflineAuthor> =
    mapNotNullTo(linkedSetOf()) { author ->
        val name = author.name.takeIf(String::isNotBlank) ?: return@mapNotNullTo null
        val role = author.role.takeIf(String::isNotBlank) ?: return@mapNotNullTo null
        KomgaOfflineAuthor(name, role)
    }

private fun Set<String>.toJsonArray(): JsonArray =
    JsonArray(filter(String::isNotBlank).sorted().map(::JsonPrimitive))

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.stringSet(key: String): Set<String> =
    (this[key] as? JsonArray)
        .orEmpty()
        .mapNotNullTo(linkedSetOf()) { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

const val KOMGA_OFFLINE_FILTERS_MEMO_KEY = "komgaOfflineFilters"

private const val CURRENT_VERSION = 1
private const val VERSION_FIELD = "version"
private const val STATUS_FIELD = "status"
private const val GENRES_FIELD = "genres"
private const val TAGS_FIELD = "tags"
private const val PUBLISHER_FIELD = "publisher"
private const val AUTHORS_FIELD = "authors"
private const val AUTHOR_NAME_FIELD = "name"
private const val AUTHOR_ROLE_FIELD = "role"
private const val TITLE_SORT_FIELD = "titleSort"
private const val CREATED_DATE_FIELD = "createdDate"
private const val LAST_MODIFIED_DATE_FIELD = "lastModifiedDate"
private const val ONE_SHOT_FIELD = "oneShot"
