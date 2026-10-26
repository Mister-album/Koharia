package koharia.kavita

import koharia.domain.epub.model.EpubRemoteProgressCache
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

data class KavitaClockSample(val checkedAt: Long, val serverDate: Long?)

data class KavitaProgressResponse(val progress: KavitaProgress, val clock: KavitaClockSample)

internal fun kavitaClockSample(dateHeader: String?, checkedAt: Long): KavitaClockSample = KavitaClockSample(
    checkedAt = checkedAt,
    serverDate = dateHeader?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.takeIf { time -> time > 0 }
    },
)

internal fun KavitaReadingState.epubProgressCache(
    chapterId: Long,
    mangaId: Long,
    bookUrl: String,
    locatorJson: String,
): EpubRemoteProgressCache = EpubRemoteProgressCache(
    chapterId = chapterId,
    mangaId = mangaId,
    bookUrl = bookUrl,
    locatorJson = locatorJson,
    progression = progress.pageNum.toDouble() / totalPages.coerceAtLeast(1),
    positionIndex = null,
    modifiedAt = Date(kavitaTimestamp(progress.lastModifiedUtc)),
    // Old cached operations have no clock evidence or response acquisition time.
    checkedAt = Date(clockSample?.checkedAt ?: 0L),
    serverDate = clockSample?.serverDate?.let(::Date),
)
