package tachiyomi.domain.manga.model

/** Two bits per option: inherit, disabled, or enabled. All fit in backup's Int flags. */
enum class ChapterDisplayOption(private val shift: Int) {
    READ_PROGRESS(24),
    FILE_SIZE(26),
    HIDE_MISSING(28),
    ;

    fun get(flags: Long, default: Boolean): Boolean = when ((flags ushr shift) and 3L) {
        1L -> false
        2L -> true
        else -> default
    }

    fun set(flags: Long, value: Boolean?): Long {
        val encoded = when (value) {
            null -> 0L
            false -> 1L
            true -> 2L
        }
        return (flags and (3L shl shift).inv()) or (encoded shl shift)
    }
}
