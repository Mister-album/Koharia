package eu.kanade.tachiyomi.ui.reader.setting

enum class DoublePageSaveMode { MERGED, SEPARATE }

enum class MergedPageLayout { MATCH_HEIGHT, ORIGINAL_PIXELS }

enum class MergedPageFormat { LOSSLESS_AUTO, PNG, JPEG }

data class MergedPageExportOptions(
    val layout: MergedPageLayout = MergedPageLayout.MATCH_HEIGHT,
    val format: MergedPageFormat = MergedPageFormat.LOSSLESS_AUTO,
)
