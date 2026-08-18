package koharia.epub.progress

internal enum class EpubRemoteProgressDecision {
    KEEP_LOCAL,
    KEEP_REMOTE,
    SAME_LOCATION,
}

internal object EpubRemoteProgressPolicy {

    fun isFreshResult(
        checkStartedAtMillis: Long,
        checkedAtMillis: Long,
    ): Boolean = checkedAtMillis >= checkStartedAtMillis

    fun decide(
        localUpdatedAtMillis: Long?,
        remoteModifiedAtMillis: Long,
        sameLocation: Boolean,
        localChangedDuringCheck: Boolean,
    ): EpubRemoteProgressDecision = when {
        sameLocation -> EpubRemoteProgressDecision.SAME_LOCATION
        localChangedDuringCheck -> EpubRemoteProgressDecision.KEEP_LOCAL
        localUpdatedAtMillis != null && localUpdatedAtMillis > remoteModifiedAtMillis ->
            EpubRemoteProgressDecision.KEEP_LOCAL
        else -> EpubRemoteProgressDecision.KEEP_REMOTE
    }
}
