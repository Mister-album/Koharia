package eu.kanade.tachiyomi.ui.reader.setting

enum class PageLayout(val value: Int) {
    SINGLE_PAGE(0),
    DOUBLE_PAGES(1),
    AUTOMATIC_DOUBLE_PAGES(2),
    AUTOMATIC_SINGLE_PAGE(3),
    ;

    val usesDoublePages: Boolean
        get() = this == DOUBLE_PAGES || this == AUTOMATIC_DOUBLE_PAGES

    val automaticallySplitsWidePages: Boolean
        get() = this == AUTOMATIC_SINGLE_PAGE

    val usesContentAwarePairing: Boolean
        get() = this == AUTOMATIC_DOUBLE_PAGES

    companion object {
        // Value 2 was the original automatic landscape mode, so it remains the double-preferring option.
        val selectableEntries = listOf(SINGLE_PAGE, DOUBLE_PAGES, AUTOMATIC_SINGLE_PAGE, AUTOMATIC_DOUBLE_PAGES)

        fun fromPreference(value: Int): PageLayout = entries.find { it.value == value } ?: SINGLE_PAGE
    }
}
