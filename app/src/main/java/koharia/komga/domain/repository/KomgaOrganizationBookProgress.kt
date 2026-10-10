package koharia.komga.domain.repository

import koharia.komga.api.dto.BookReadProgressDto

/**
 * Existing database rows may predate an account change. Only new local changes override this
 * account's snapshot.
 */
internal class KomgaOrganizationBookProgress(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private val previous = mutableMapOf<String, Map<Long, Boolean>>()

    fun observe(bookId: String, remote: BookReadProgressDto?, local: Map<Long, Boolean>): Boolean {
        val signature = remote?.let { "${it.lastModified}|${it.page}|${it.completed}" } ?: "none"
        val key = "organization-book-read-$bookId"
        val baseline = previous.put(bookId, local)
        val changed =
            local.entries.lastOrNull {
                baseline?.containsKey(it.key) == true && baseline[it.key] != it.value
            }
        if (changed != null) write(key, "$signature|${changed.value}")
        return when (read(key)) {
            "$signature|true" -> true
            "$signature|false" -> false
            else -> remote?.completed == true
        }
    }
}
