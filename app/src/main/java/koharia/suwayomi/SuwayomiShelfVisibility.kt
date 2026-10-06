package koharia.suwayomi

/** Filters presentation only; the complete account-scoped shelf cache is retained. */
fun SuwayomiShelf.withVisibleCategories(ids: Set<Int>?): SuwayomiShelf {
    if (ids == null) return this
    return copy(
        categories = categories.filter { it.id in ids },
        mangas = mangas.filter { manga -> manga.categories.nodes.any { it.id in ids } },
    )
}
