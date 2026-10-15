package koharia.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Keeps scoped observations below SQLite's parameter limit without subscribing once per item. */
internal inline fun <K, reified T> observeBatched(
    keys: Collection<K>,
    query: (List<K>) -> Flow<List<T>>,
): Flow<List<T>> {
    val batches = keys.distinct().chunked(200).map(query)
    return if (batches.isEmpty()) flowOf(emptyList()) else combine(batches) { it.flatMap { rows -> rows } }
}
