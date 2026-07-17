package koharia.epub.cache

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class EpubCachePreferences(
    preferenceStore: PreferenceStore,
) {
    val cacheWholeBook: Preference<Boolean> =
        preferenceStore.getBoolean("epub_cache_whole_book", true)

    val cacheSizeMb: Preference<Int> =
        preferenceStore.getInt("epub_book_cache_size_mb", DEFAULT_CACHE_SIZE_MB)

    companion object {
        const val DEFAULT_CACHE_SIZE_MB = 512
        const val MIN_CACHE_SIZE_MB = 128
        const val MAX_CACHE_SIZE_MB = 4096
        const val CACHE_SIZE_STEP_MB = 128
    }
}
