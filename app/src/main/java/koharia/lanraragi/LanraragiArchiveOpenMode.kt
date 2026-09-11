package koharia.lanraragi

import koharia.domain.lanraragi.LanraragiEntry

enum class LanraragiArchiveOpenMode { READER, PAGE_PREVIEW }

enum class LanraragiEntryDestination { READER, PAGE_PREVIEW, DETAILS }

fun lanraragiEntryDestination(
    url: String,
    sourceId: Long,
    mode: LanraragiArchiveOpenMode,
    longClick: Boolean = false,
): LanraragiEntryDestination {
    val archivePrefix = "/lanraragi/$sourceId/${LanraragiEntry.Kind.ARCHIVE.name.lowercase()}/"
    if (!url.startsWith(archivePrefix)) return LanraragiEntryDestination.DETAILS
    return if (longClick || mode == LanraragiArchiveOpenMode.PAGE_PREVIEW) {
        LanraragiEntryDestination.PAGE_PREVIEW
    } else {
        LanraragiEntryDestination.READER
    }
}
