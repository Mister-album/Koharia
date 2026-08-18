package eu.kanade.tachiyomi.ui.reader

internal enum class RemoteProgressDecision {
    KEEP_LOCAL,
    KEEP_REMOTE,
    SAME_LOCATION,
}

internal object RemoteProgressConflictPolicy {

    fun decide(
        localUpdatedAtMillis: Long?,
        remoteUpdatedAtMillis: Long?,
        sameLocation: Boolean,
        localChangedDuringCheck: Boolean,
    ): RemoteProgressDecision = when {
        sameLocation -> RemoteProgressDecision.SAME_LOCATION
        localChangedDuringCheck -> RemoteProgressDecision.KEEP_LOCAL
        localUpdatedAtMillis != null &&
            remoteUpdatedAtMillis != null &&
            localUpdatedAtMillis > remoteUpdatedAtMillis -> RemoteProgressDecision.KEEP_LOCAL
        else -> RemoteProgressDecision.KEEP_REMOTE
    }
}
