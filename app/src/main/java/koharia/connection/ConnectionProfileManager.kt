package koharia.connection

class ConnectionProfileManager(
    private val preferences: ConnectionPreferences,
    private val registry: ConnectionRegistry,
    private val configManager: ConnectionConfigManager,
) {
    fun profiles(): List<LibraryConnectionProfile> = preferences.getProfiles()

    fun add(providerId: String, name: String): LibraryConnectionProfile {
        requireNotNull(registry.provider(providerId)) { "Connection provider $providerId is unavailable" }
        val profile = LibraryConnectionProfile(
            id = preferences.allocateConnectionId(),
            providerId = providerId,
            name = name.trim(),
        )
        require(profile.name.isNotEmpty()) { "Connection name cannot be empty" }
        preferences.setProfiles(preferences.getProfiles() + profile)
        configManager.initializeScopeForNewConnection(profile.id)
        return profile
    }

    fun update(profile: LibraryConnectionProfile) {
        val profiles = preferences.getProfiles()
        require(profiles.any { it.id == profile.id }) { "Connection profile no longer exists" }
        preferences.setProfiles(profiles.map { if (it.id == profile.id) profile else it })
    }

    suspend fun remove(connectionId: Long): Result<Unit> {
        val profile = preferences.getProfiles().firstOrNull { it.id == connectionId }
            ?: return Result.success(Unit)
        return registry.provider(profile.providerId)
            ?.removeConnection(profile)
            ?.mapCatching { handled ->
                if (!handled) removeGenericProfile(profile)
            }
            ?: runCatching { removeGenericProfile(profile) }
    }

    private fun removeGenericProfile(profile: LibraryConnectionProfile) {
        eu.kanade.tachiyomi.source.sourcePreferences("source_${profile.id}")
            .edit()
            .clear()
            .apply()
        configManager.clearScopeForConnection(profile.id)
        preferences.setProfiles(preferences.getProfiles().filterNot { it.id == profile.id })
    }
}
