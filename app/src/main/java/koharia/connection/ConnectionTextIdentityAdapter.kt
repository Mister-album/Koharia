package koharia.connection

/** Offline identity for user-authored text rules, independent of transient session configuration. */
interface ConnectionTextIdentityAdapter {
    suspend fun textReplacementOwnerKey(): String
}
