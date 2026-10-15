package koharia.connection

import androidx.paging.PagingConfig

/** Use only with sources that can reload dropped pages from the persistent catalogue. */
internal fun connectionShelfPagingConfig(pageSize: Int = 50, prefetchDistance: Int = 5) = PagingConfig(
    pageSize = pageSize,
    initialLoadSize = pageSize,
    prefetchDistance = prefetchDistance,
    enablePlaceholders = false,
    maxSize = 300,
)
