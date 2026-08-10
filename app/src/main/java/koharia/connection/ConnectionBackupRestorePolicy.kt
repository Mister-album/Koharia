package koharia.connection

class ConnectionBackupRestorePolicy(
    private val genericKeyPrefix: String,
    private val legacyAppKeys: Set<String>,
    private val legacySourceKeys: Set<String>,
) {
    private var restoredGenericConnectionState = false
    private var restoredLegacyConnectionState = false

    fun recordAppRestore(keys: Collection<String>): Boolean {
        restoredGenericConnectionState = keys.any { it.startsWith(genericKeyPrefix) }
        restoredLegacyConnectionState = keys.any { it in legacyAppKeys }
        return restoredLegacyConnectionState && !restoredGenericConnectionState
    }

    fun shouldForceLegacyInventoryAfterSourceRestore(
        sourceKeys: Collection<String>,
        hasConnectionProfiles: Boolean,
    ): Boolean {
        return sourceKeys.any { it in legacySourceKeys } &&
            !restoredGenericConnectionState &&
            (restoredLegacyConnectionState || !hasConnectionProfiles)
    }
}
