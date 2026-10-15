package koharia.connection

import eu.kanade.tachiyomi.BuildConfig

/** Build-scoped validation boundary: cannot be disabled by a preference or a process restart. */
object DevReadOnlyValidation {
    val enabled = BuildConfig.DEBUG && BuildConfig.DEV_READ_ONLY_VALIDATION
}
