package koharia.smanga

import kotlinx.serialization.Serializable

@Serializable
data class SmangaAccount(val id: Long, val userName: String, val nickname: String = "", val role: String = "")

@Serializable
data class SmangaMedia(val id: Long, val name: String, val mangaCount: Int = 0)

@Serializable
data class SmangaManga(
    val id: Long,
    val mediaId: Long,
    val name: String,
    val description: String = "",
    val author: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
    val chapterCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val sortName: String = name,
)

@Serializable
data class SmangaMangaPage(
    val data: List<SmangaManga>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
) {
    val hasNext: Boolean get() = page.toLong() * pageSize < total
}

@Serializable
data class SmangaChapter(
    val id: Long,
    val mangaId: Long,
    val mediaId: Long,
    val name: String,
    val number: Float = -1f,
    val pageCount: Int = 0,
    val format: String = "",
    val latest: SmangaProgress? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class SmangaProgress(
    val pageIndex: Int = -1,
    val totalPages: Int = 0,
    val completed: Boolean = false,
    val updatedAt: Long = 0,
)

/** The server returns one aggregate per manga, not every individual history event. */
@Serializable
data class SmangaHistory(
    val mangaId: Long,
    val chapterId: Long,
    val mediaId: Long,
    val mangaName: String,
    val chapterName: String,
    val createdAt: Long = 0,
    val latest: SmangaProgress? = null,
)

@Serializable
data class SmangaHistoryPage(
    val data: List<SmangaHistory>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
) {
    val hasNext: Boolean get() = page.toLong() * pageSize < total
}
