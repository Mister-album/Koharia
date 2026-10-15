package koharia.connection

/** Publish new source instances only after all restored connection preferences have been written. */
interface ConnectionSourceRestorer {
    suspend fun <T> withSourceUpdatesDeferred(block: suspend () -> T): T

    suspend fun <T> withSourceConfiguration(id: Long, block: suspend () -> T): T
}
