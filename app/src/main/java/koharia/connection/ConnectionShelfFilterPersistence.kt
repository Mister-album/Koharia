package koharia.connection

/**
 * The persistence rule every shelf shares: an opted-in shelf restores and stores its filters and
 * sort, an opted-out shelf opens on the defaults and keeps nothing behind. Provider preference
 * stores delegate here so the behaviour cannot drift between them.
 */
object ConnectionShelfFilterPersistence {
    /**
     * The sort to open a shelf with. A stored value only applies while the reader opted into
     * remembering, so a stale order cannot appear on a shelf they expect to be untouched.
     */
    fun initialOrder(persistent: Boolean, storedOrder: String, defaultOrder: String): String =
        if (persistent && storedOrder.isNotBlank()) storedOrder else defaultOrder

    /** True when a confirmed filter submission should be written to the store. */
    fun shouldStore(persistent: Boolean): Boolean = persistent

    /** True when the stored filters and sort must be dropped, i.e. persistence was switched off. */
    fun shouldClear(persistent: Boolean): Boolean = !persistent
}
