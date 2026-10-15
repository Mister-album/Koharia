package tachiyomi.domain.chapter.model

/** Ownership captured when a reader opens a chapter, independently of mutable reading flags. */
data class ChapterProgressOwner(val sourceId: Long, val mangaId: Long, val chapterId: Long, val url: String)
