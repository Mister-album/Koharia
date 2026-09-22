package koharia.smanga

import koharia.domain.smanga.SmangaReadState

/** Aggregate history identifies manga only; an exact reading state must identify the resume chapter. */
internal fun selectSmangaHistoryState(mangaId: Long, states: List<SmangaReadState>): SmangaReadState? {
    val candidates = states.filter { state ->
        state.mangaId == mangaId && state.chapterId > 0 && state.readAt > 0 && !state.explicitUnread &&
            (
                state.completed ||
                    (
                        state.pageIndex >= 0 && state.totalPages >= 0 &&
                            (state.totalPages == 0 || state.pageIndex < state.totalPages)
                        )
                )
    }
    val latestReadAt = candidates.maxOfOrNull { it.readAt } ?: return null
    return candidates.singleOrNull { it.readAt == latestReadAt }
}
