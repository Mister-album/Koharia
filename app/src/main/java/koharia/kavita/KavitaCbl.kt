package koharia.kavita

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class KavitaCbl(private val catalog: KavitaCatalog, private val checkSession: () -> Unit) {
    private val api get() = catalog.api
    private suspend fun requireWritable() {
        checkSession()
        if (!api.capabilities(api.getAccount(true)).writable) throw KavitaException(KavitaException.Reason.PERMISSION)
        checkSession()
    }

    suspend fun upload(bytes: ByteArray, json: Boolean): KavitaCblImport {
        requireWritable()
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        // Separate uploads cannot overwrite a user's existing staged file on the server.
        val filename = "koharia-${UUID.randomUUID()}." + if (json) "json" else "cbl"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "cblFile",
                filename,
                bytes.toRequestBody(
                    (if (json) "application/json" else "application/xml").toMediaType(),
                ),
            ).build()
        val saved = api.client.newCall(api.request("Cbl/file-import").newBuilder().post(body).build()).await().use {
            KavitaApiClient.checkResponse(it)
            api.decode<JsonObject>(withContext(Dispatchers.IO) { it.body.string() })
        }
        checkSession()
        return KavitaCblImport(saved, validate(saved.textValue("fileName")))
    }

    suspend fun validate(filename: String): KavitaCblSummary = api.post(
        "Cbl/re-validate",
        buildJsonObject {
            put("fileName", filename)
        },
    )

    suspend fun finish(import: KavitaCblImport, decisions: Map<Int, KavitaCblDecision>): KavitaCblSummary {
        requireWritable()
        require(import.summary.results.none { it.reason in listOf(5, 9) })
        val payload = JsonObject(
            import.file + mapOf(
                "decisions" to buildJsonObject {
                    put("itemResolutions", api.json.parseToJsonElement(api.json.encodeToString(decisions)))
                    put("saveAsRemapRules", false)
                },
            ),
        )
        val summary: KavitaCblSummary = try {
            api.post("Cbl/finalize-import", payload)
        } catch (failure: java.io.IOException) {
            if (failure is KavitaException && failure.status != null && failure.status < 500) throw failure
            // Kavita commits members before generating a cover. A failed cover can return HTTP 500
            // after the import is saved. Verify the entire ordered membership without replaying the POST.
            reconcile(import, decisions)?.copy(verifiedAfterError = true) ?: throw failure
        }
        checkSession()
        catalog.invalidateGroups("resource/ReadingList")
        return summary
    }

    private suspend fun reconcile(
        import: KavitaCblImport,
        decisions: Map<Int, KavitaCblDecision>,
    ): KavitaCblSummary? = try {
        val expected = import.summary.items.sortedBy { it.order }.map { item ->
            val decision = decisions[item.order]
            (decision?.seriesId ?: item.seriesId) to (decision?.chapterId ?: item.chapterId)
        }
        if (expected.isEmpty() || expected.any { it.first <= 0 || it.second <= 0 }) {
            null
        } else {
            val list = api.allReadingLists().singleOrNull { it.title == import.summary.cblName }
            val actual = list?.let {
                api.get<List<KavitaListItem>>("ReadingList/items?readingListId=${it.id}").sortedBy { it.order }
            }
            if (actual?.map { it.seriesId to it.chapterId } == expected) {
                import.summary.copy(readingListId = requireNotNull(list).id)
            } else {
                null
            }
        }
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        null
    }

    suspend fun export(id: Long): ByteArray {
        checkSession()
        val request = api.request("ReadingList/export-as-cbl", "readingListId" to id, "asV2" to false)
            .newBuilder().post("{}".toRequestBody(KavitaApiClient.JSON)).build()
        return api.client.newCall(request).await().use {
            KavitaApiClient.checkResponse(it)
            withContext(Dispatchers.IO) {
                it.body.byteStream().use { input -> input.readBytesBounded(MAX_BYTES) }
            }
        }.also { checkSession() }
    }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        require(output.size().toLong() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

data class KavitaCblImport(val file: JsonObject, val summary: KavitaCblSummary)

@Serializable
data class KavitaCblSummary(
    val cblName: String = "",
    val fileName: String = "",
    val results: List<KavitaCblResult> = emptyList(),
    val successfulInserts: List<KavitaCblResult> = emptyList(),
    val success: Int = 0,
    val isUpdate: Boolean = false,
    val readingListId: Long = 0,
    val verifiedAfterError: Boolean = false,
) {
    val items get() = (results + successfulInserts).distinctBy { it.order }.sortedBy { it.order }
}

@Serializable
data class KavitaCblResult(
    val order: Int = 0,
    val series: String = "",
    val volume: String = "",
    val number: String = "",
    val seriesId: Long = 0,
    val chapterId: Long = 0,
    val chapterTitle: String = "",
    val matchedSeriesName: String = "",
    val reason: Int = 0,
)

@Serializable
data class KavitaCblDecision(val seriesId: Long, val volumeId: Long, val chapterId: Long)
