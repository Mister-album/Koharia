package koharia.pdf.reflow

internal data class PdfReflowHandoff(val chapterId: Long, val initialPage: Int) {
    fun pageFor(currentChapterId: Long?, visiblePage: Int): Int? {
        if (currentChapterId != chapterId) return null
        return if (visiblePage > 0) visiblePage - 1 else initialPage.coerceAtLeast(0)
    }
}
