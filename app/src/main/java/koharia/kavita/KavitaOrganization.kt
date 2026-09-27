package koharia.kavita

import koharia.connection.ConnectionShelfUpdates
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Personal organization mutations invalidate only the affected cached views. */
class KavitaOrganization(
    private val connectionId: Long,
    private val catalog: KavitaCatalog,
    private val checkSession: () -> Unit,
) {
    private val api get() = catalog.api

    private suspend fun writable(): KavitaAccount {
        checkSession()
        val account = api.getAccount(refresh = true)
        if (!api.capabilities(account).writable) throw KavitaException(KavitaException.Reason.PERMISSION)
        checkSession()
        return account
    }

    private suspend fun changed(vararg groups: String) {
        checkSession()
        catalog.invalidateGroups(*groups)
        ConnectionShelfUpdates.notify(connectionId)
    }

    suspend fun wantToRead(seriesId: Long, enabled: Boolean) {
        writable()
        api.mutate(
            "want-to-read/" + if (enabled) "add-series" else "remove-series",
            buildJsonObject {
                put("seriesIds", JsonArray(listOf(JsonPrimitive(seriesId))))
            },
        )
        changed("shelf/", "resource/want-to-read")
    }

    suspend fun collection(id: Long, refresh: Boolean = false): JsonObject =
        catalog.resource("Collection/single?collectionId=$id", refresh).jsonObject

    suspend fun readingList(id: Long, refresh: Boolean = false): JsonObject =
        catalog.resource("ReadingList?readingListId=$id", refresh).jsonObject

    private fun requireOwner(value: JsonObject, user: KavitaAccount, field: String) {
        if (!value.textValue(field).equals(user.username, true)) {
            throw KavitaException(KavitaException.Reason.PERMISSION)
        }
    }

    suspend fun addCollection(seriesId: Long, id: Long = 0, title: String = "") {
        val user = writable()
        if (id > 0) requireOwner(collection(id, true), user, "owner")
        require(id > 0 || title.isNotBlank())
        api.mutate(
            "Collection/update-for-series",
            buildJsonObject {
                put("collectionTagId", id)
                put("collectionTagTitle", title.trim())
                put("seriesIds", JsonArray(listOf(JsonPrimitive(seriesId))))
            },
        )
        changed("resource/Collection", "shelf/")
    }

    suspend fun removeCollectionMember(id: Long, seriesId: Long) {
        val user = writable()
        val current = collection(id, true)
        requireOwner(current, user, "owner")
        api.mutate(
            "Collection/update-series",
            buildJsonObject {
                put("tag", current)
                put("seriesIdsToRemove", JsonArray(listOf(JsonPrimitive(seriesId))))
            },
        )
        changed("resource/Collection", "shelf/")
    }

    suspend fun editCollection(id: Long, title: String, summary: String) {
        val user = writable()
        val current = collection(id, true)
        requireOwner(current, user, "owner")
        require(title.isNotBlank())
        api.mutate(
            "Collection/update",
            JsonObject(
                current + mapOf(
                    "title" to JsonPrimitive(title.trim()),
                    "summary" to JsonPrimitive(summary),
                ),
            ),
        )
        changed("resource/Collection")
    }

    suspend fun deleteCollection(id: Long) {
        val user = writable()
        requireOwner(collection(id, true), user, "owner")
        api.mutate("Collection?tagId=$id", method = "DELETE")
        changed("resource/Collection", "shelf/")
    }

    suspend fun createList(title: String) {
        writable()
        require(title.isNotBlank())
        api.mutate("ReadingList/create", buildJsonObject { put("title", title.trim()) })
        changed("resource/ReadingList")
    }

    suspend fun editList(id: Long, title: String, summary: String) {
        val user = writable()
        val current = readingList(id, true)
        requireOwner(current, user, "ownerUserName")
        require(title.isNotBlank())
        api.mutate("ReadingList/update", readingListUpdate(current, title, summary))
        changed("resource/ReadingList")
    }

    suspend fun deleteList(id: Long) {
        val user = writable()
        requireOwner(readingList(id, true), user, "ownerUserName")
        api.mutate("ReadingList?readingListId=$id", method = "DELETE")
        changed("resource/ReadingList")
    }

    suspend fun addListMember(id: Long, seriesId: Long, chapterId: Long? = null) {
        val user = writable()
        requireOwner(readingList(id, true), user, "ownerUserName")
        api.mutate(
            "ReadingList/update-by-" + if (chapterId == null) "series" else "chapter",
            buildJsonObject {
                put("readingListId", id)
                put("seriesId", seriesId)
                chapterId?.let { put("chapterId", it) }
            },
        )
        changed("resource/ReadingList")
    }

    suspend fun moveListMember(id: Long, itemId: Long, delta: Int) {
        require(delta in -1..1)
        val user = writable()
        requireOwner(readingList(id, true), user, "ownerUserName")
        // Re-read before computing positions; a stale UI must not move a different item.
        val items = catalog.resource("ReadingList/items?readingListId=$id", true).jsonArray.map { it.jsonObject }
            .sortedBy { it.longValue("order") }
        val index = items.indexOfFirst { it.longValue("id") == itemId }
        require(index >= 0)
        if (delta != 0 && index + delta !in items.indices) return
        api.mutate(
            if (delta == 0) "ReadingList/delete-item" else "ReadingList/update-position",
            buildJsonObject {
                put("readingListId", id)
                put("readingListItemId", itemId)
                put("fromPosition", items[index].longValue("order"))
                put("toPosition", items[if (delta == 0) index else index + delta].longValue("order"))
            },
        )
        changed("resource/ReadingList")
    }

    private suspend fun compatibleMutation(modern: String, legacy: String, body: JsonElement) {
        try {
            api.mutate(modern, body)
        } catch (error: KavitaException) {
            if (error.status !in listOf(404, 405)) throw error
            api.mutate(legacy, body)
        }
    }

    suspend fun rate(seriesId: Long, rating: Float) {
        writable()
        require(rating.isFinite() && rating in 0f..5f)
        compatibleMutation(
            "Rating/series",
            "Series/update-rating",
            buildJsonObject {
                put("seriesId", seriesId)
                put("userRating", rating)
            },
        )
        changed("series/$seriesId", "resource/Series/$seriesId", "resource/Rating", "shelf/")
    }

    suspend fun review(seriesId: Long, body: String) {
        writable()
        compatibleMutation(
            "Review/series",
            "Review",
            buildJsonObject {
                put("seriesId", seriesId)
                put("body", body)
            },
        )
        changed("resource/Review", "resource/Series/$seriesId")
    }

    suspend fun deleteReview(seriesId: Long) {
        writable()
        try {
            api.mutate("Review/series?seriesId=$seriesId", method = "DELETE")
        } catch (failure: KavitaException) {
            if (failure.status !in listOf(404, 405)) throw failure
            api.mutate("Review?seriesId=$seriesId", method = "DELETE")
        }
        changed("resource/Review", "resource/Series/$seriesId")
    }

    suspend fun sendToDevice(seriesId: Long, deviceId: Long) {
        val user = writable()
        if (!api.capabilities(user).downloads) throw KavitaException(KavitaException.Reason.PERMISSION)
        api.mutate(
            "Device/send-series-to",
            buildJsonObject {
                put("seriesId", seriesId)
                put("deviceId", deviceId)
            },
        )
    }

    suspend fun addDashboardFilter(filterId: Long) {
        writable()
        val current = catalog.resource("Stream/dashboard?visibleOnly=false", true).jsonArray
        if (current.any { it.jsonObject.longValue("smartFilterId") == filterId }) return
        api.mutate("Stream/add-dashboard-stream?smartFilterId=$filterId")
        changed("resource/Stream")
    }

    suspend fun annotationSharing(field: String, enabled: Boolean) {
        require(field in listOf("shareAnnotations", "viewOtherAnnotations"))
        writable()
        val current = api.get<JsonObject>("Users/get-preferences")
        val social = current["socialPreferences"] as? JsonObject
            ?: throw KavitaException(KavitaException.Reason.UNSUPPORTED)
        api.mutate(
            "Users/update-preferences",
            JsonObject(current + ("socialPreferences" to JsonObject(social + (field to JsonPrimitive(enabled))))),
        )
        changed("resource/Users/get-preferences", "annotations/", "annotation-browser/")
    }

    suspend fun updateDashboard(id: Long, visible: Boolean? = null, delta: Int = 0, remove: Boolean = false) {
        writable()
        require(delta in -1..1)
        val current = catalog.resource("Stream/dashboard?visibleOnly=false", true).jsonArray
            .map { it.jsonObject }.sortedBy { it.longValue("order") }
        val index = current.indexOfFirst { it.longValue("id") == id }
        require(index >= 0)
        val stream = current[index]
        when {
            remove -> {
                require(stream.longValue("smartFilterId") > 0)
                api.mutate("Stream/smart-filter-dashboard-stream?dashboardStreamId=$id", method = "DELETE")
            }
            visible != null -> api.mutate(
                "Stream/update-dashboard-stream",
                JsonObject(stream + ("visible" to JsonPrimitive(visible))),
            )
            delta != 0 && index + delta in current.indices -> api.mutate(
                "Stream/update-dashboard-position",
                buildJsonObject {
                    put("id", id)
                    put("streamName", stream.textValue("name"))
                    put("fromPosition", stream.longValue("order"))
                    put("toPosition", current[index + delta].longValue("order"))
                    put("positionIncludesInvisible", true)
                },
            )
        }
        changed("resource/Stream")
    }

    suspend fun saveFilter(id: Long, name: String, filter: KavitaFilter) {
        writable()
        require(name.isNotBlank())
        val saved = api.get<List<JsonObject>>("Filter")
        if (id > 0 && saved.none { it.longValue("id") == id && it.textValue("name") == name.trim() }) {
            throw KavitaException(KavitaException.Reason.CONFLICT)
        }
        if (saved.any { it.textValue("name").equals(name.trim(), true) && it.longValue("id") != id }) {
            throw KavitaException(KavitaException.Reason.CONFLICT)
        }
        val payload = JsonObject(
            api.json.parseToJsonElement(api.json.encodeToString(filter)).jsonObject + mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name.trim()),
                "entityType" to JsonPrimitive(0),
            ),
        )
        compatibleMutation("Filter/update/series", "Filter/update", payload)
        changed("resource/Filter", "shelf/")
    }

    suspend fun deleteFilter(id: Long) {
        writable()
        api.mutate("Filter?filterId=$id", method = "DELETE")
        changed("resource/Filter")
    }

    suspend fun renameFilter(id: Long, name: String) {
        writable()
        require(id > 0 && name.isNotBlank())
        val saved = api.get<List<JsonObject>>("Filter")
        if (saved.none { it.longValue("id") == id } ||
            saved.any { it.longValue("id") != id && it.textValue("name").equals(name.trim(), true) }
        ) {
            throw KavitaException(KavitaException.Reason.CONFLICT)
        }
        val url = api.url("Filter/rename", "filterId" to id, "name" to name.trim())
        api.mutate("Filter/rename?" + url.encodedQuery)
        changed("resource/Filter", "resource/Stream")
    }
}

internal fun JsonObject.textValue(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.longValue(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0

internal fun readingListUpdate(current: JsonObject, title: String, summary: String): JsonObject = buildJsonObject {
    for (key in listOf("promoted", "coverImageLocked", "startingMonth", "startingYear", "endingMonth", "endingYear")) {
        current[key]?.let { put(key, it) }
    }
    put("readingListId", current.getValue("id"))
    put("title", title.trim())
    put("summary", summary)
    put(
        "tags",
        JsonArray(
            (current["tags"] as? JsonArray).orEmpty().map {
                if (it is JsonObject) JsonPrimitive(it.textValue("title")) else it
            },
        ),
    )
}
