package koharia.kavita

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class KavitaHistoryEntry(val libraryId: Long, val seriesId: Long, val chapterId: Long, val readAt: Long)

@Serializable
private data class KavitaHistorySession(
    val libraryId: Long,
    val seriesId: Long,
    val endTimeUtc: String = "",
    val chapters: List<KavitaHistoryChapter> = emptyList(),
)

@Serializable
private data class KavitaHistoryChapter(val chapterId: Long, val endTimeUtc: String = "")

@Serializable
private data class KavitaLegacyHistory(
    val userId: Long,
    val libraryId: Long,
    val seriesId: Long,
    val chapterId: Long,
    val readDate: String,
)

/** Fetch every page before publishing a snapshot; history never writes reading progress. */
suspend fun KavitaApiClient.readingHistory(): List<KavitaHistoryEntry> {
    // 0.8 may serve its HTML application with HTTP 200 for unknown API routes.
    if (capabilities(getAccount()).version < KavitaVersion(0, 9)) return legacyReadingHistory()
    val entries = mutableListOf<KavitaHistoryEntry>()
    val until = Instant.now().toString()
    var page = 1
    try {
        do {
            val request = request(
                "Stats/reading-history",
                "PageNumber" to page,
                "PageSize" to 100,
                "TimeZoneId" to "UTC",
                "EndDate" to until,
            )
            val more = client.newCall(request).await().use { response ->
                KavitaApiClient.checkResponse(response)
                val sessions =
                    decode<List<KavitaHistorySession>>(withContext(Dispatchers.IO) { response.body.string() })
                val pagination = response.header("Pagination")?.let { decode<KavitaPagination>(it) }
                    ?: throw KavitaException(KavitaException.Reason.PROTOCOL)
                if (pagination.currentPage != page || pagination.totalPages < 0) {
                    throw KavitaException(KavitaException.Reason.PROTOCOL)
                }
                for (session in sessions) {
                    for (chapter in session.chapters) {
                        entries += KavitaHistoryEntry(
                            session.libraryId,
                            session.seriesId,
                            chapter.chapterId,
                            kavitaTimestamp(chapter.endTimeUtc).takeIf {
                                it > 0
                            } ?: kavitaTimestamp(session.endTimeUtc),
                        )
                    }
                }
                pagination.currentPage < pagination.totalPages
            }
            page++
        } while (more)
    } catch (failure: KavitaException) {
        if (page != 1 || failure.status !in listOf(404, 405)) throw failure
        entries += legacyReadingHistory()
    }
    return entries.filter { it.libraryId > 0 && it.seriesId > 0 && it.chapterId > 0 && it.readAt > 0 }
        .groupBy { Triple(it.libraryId, it.seriesId, it.chapterId) }
        .map { (_, events) -> events.maxBy { it.readAt } }
}

private suspend fun KavitaApiClient.legacyReadingHistory(): List<KavitaHistoryEntry> {
    val userId = getAccount().id
    if (userId <= 0) throw KavitaException(KavitaException.Reason.PROTOCOL)
    return get<List<KavitaLegacyHistory>>("Stats/user/reading-history?userId=$userId")
        .filter { it.userId == userId && it.libraryId > 0 && it.seriesId > 0 && it.chapterId > 0 }
        .map { KavitaHistoryEntry(it.libraryId, it.seriesId, it.chapterId, kavitaTimestamp(it.readDate)) }
        .filter { it.readAt > 0 }
        .groupBy { Triple(it.libraryId, it.seriesId, it.chapterId) }
        .map { (_, events) -> events.maxBy { it.readAt } }
}
