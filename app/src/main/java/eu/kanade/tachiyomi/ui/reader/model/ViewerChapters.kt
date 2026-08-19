package eu.kanade.tachiyomi.ui.reader.model

data class ViewerChapters(
    val currChapter: ReaderChapter,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
) {

    fun ref() {
        currChapter.ref()
        prevChapter?.ref()
        nextChapter?.ref()
    }

    fun unref() {
        var firstError: Throwable? = null
        listOf(currChapter, prevChapter, nextChapter).forEach { chapter ->
            if (chapter == null) return@forEach
            try {
                chapter.unref()
            } catch (error: Throwable) {
                firstError = firstError ?: error
            }
        }
        firstError?.let { throw it }
    }
}
