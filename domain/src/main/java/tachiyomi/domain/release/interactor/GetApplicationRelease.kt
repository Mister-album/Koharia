package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        return if (isPreview) {
            // Preview builds: based on releases in "kohariaapp/koharia-preview" repo
            // tagged as something like "r1234"
            versionTag.filter(Char::isDigit).toIntOrNull()?.let { it > commitCount } == true
        } else {
            // Release builds: based on releases in "kohariaapp/koharia" repo
            // tagged as something like "v0.1.2"
            val newSemVer = versionTag.toVersionComponents()
            val oldSemVer = versionName.toVersionComponents()
            if (newSemVer.isEmpty() || oldSemVer.isEmpty()) return false

            repeat(maxOf(newSemVer.size, oldSemVer.size)) { index ->
                val newPart = newSemVer.getOrElse(index) { 0 }
                val oldPart = oldSemVer.getOrElse(index) { 0 }
                when {
                    newPart > oldPart -> return true
                    newPart < oldPart -> return false
                }
            }

            false
        }
    }

    private fun String.toVersionComponents(): List<Int> {
        return substringBefore('-')
            .split('.')
            .mapNotNull { component -> component.filter(Char::isDigit).toIntOrNull() }
    }

    data class Arguments(
        val isFoss: Boolean,
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
