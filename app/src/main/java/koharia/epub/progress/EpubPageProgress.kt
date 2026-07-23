package koharia.epub.progress

import kotlin.math.roundToInt

internal object EpubPageProgress {

    fun pageIndex(progression: Double?, totalPages: Int): Int? {
        if (progression == null || totalPages <= 0) return null
        if (totalPages == 1) return 0
        return (progression.coerceIn(0.0, 1.0) * (totalPages - 1)).roundToInt()
    }
}
