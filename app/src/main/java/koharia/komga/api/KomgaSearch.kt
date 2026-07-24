package koharia.komga.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class KomgaSearchRequest(
    val condition: JsonObject,
    val fullTextSearch: String? = null,
)

class KomgaSearchCapabilities {
    private val legacyTypes = ConcurrentHashMap.newKeySet<KomgaApiClient.SearchType>()

    @Volatile
    private var serverKey: String? = null

    @Synchronized
    internal fun prepareFor(serverKey: String) {
        if (this.serverKey == serverKey) return
        this.serverKey = serverKey
        legacyTypes.clear()
    }

    internal fun usesLegacy(type: KomgaApiClient.SearchType): Boolean = type in legacyTypes

    internal fun markLegacy(type: KomgaApiClient.SearchType) {
        legacyTypes += type
    }

    internal fun clear() {
        legacyTypes.clear()
    }
}

internal fun buildSearchCondition(
    type: KomgaApiClient.SearchType,
    libraries: Set<String>,
    collectionId: String?,
    readStatuses: Set<String>,
    statuses: Set<String>,
    genres: Set<String>,
    tags: Set<String>,
    publishers: Set<String>,
    authors: List<Pair<String, String>>,
    oneshot: Boolean?,
): JsonObject {
    val conditions = buildList {
        add(booleanCondition("deleted", false))
        addAnyOf("libraryId", libraries)
        addAnyOf("readStatus", readStatuses)
        addAnyOf("tag", tags)
        addAnyOfAuthors(authors)
        oneshot?.let { add(booleanCondition("oneShot", it)) }

        if (type == KomgaApiClient.SearchType.SERIES) {
            collectionId?.let { add(equalityCondition("collectionId", it)) }
            addAnyOf("seriesStatus", statuses)
            addAnyOf("genre", genres)
            addAnyOf("publisher", publishers)
        }
    }
    return buildJsonObject { put("allOf", JsonArray(conditions)) }
}

private fun MutableList<JsonObject>.addAnyOf(field: String, values: Collection<String>) {
    if (values.isEmpty()) return
    add(
        buildJsonObject {
            put("anyOf", JsonArray(values.map { equalityCondition(field, it) }))
        },
    )
}

private fun MutableList<JsonObject>.addAnyOfAuthors(authors: List<Pair<String, String>>) {
    if (authors.isEmpty()) return
    add(
        buildJsonObject {
            put(
                "anyOf",
                JsonArray(
                    authors.map { (name, role) ->
                        buildJsonObject {
                            put(
                                "author",
                                buildJsonObject {
                                    put("operator", "is")
                                    put(
                                        "value",
                                        buildJsonObject {
                                            put("name", name)
                                            put("role", role)
                                        },
                                    )
                                },
                            )
                        }
                    },
                ),
            )
        },
    )
}

private fun equalityCondition(field: String, value: String): JsonObject = buildJsonObject {
    put(
        field,
        buildJsonObject {
            put("operator", "is")
            put("value", value)
        },
    )
}

private fun booleanCondition(field: String, value: Boolean): JsonObject = buildJsonObject {
    put(
        field,
        buildJsonObject {
            put("operator", JsonPrimitive(if (value) "isTrue" else "isFalse"))
        },
    )
}
