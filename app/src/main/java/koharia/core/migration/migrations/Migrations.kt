package koharia.core.migration.migrations

import koharia.core.migration.Migration

val migrations: List<Migration>
    get() = listOf(
        SetupBackupCreateMigration(),
        SetupLibraryUpdateMigration(),
        TrustExtensionRepositoryMigration(),
        CategoryPreferencesCleanupMigration(),
        InstallationIdMigration(),
    )
