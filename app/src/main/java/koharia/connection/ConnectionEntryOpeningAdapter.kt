package koharia.connection

import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.Preference

/** Settings refer to the same persisted values used by the provider's entry router. */
interface ConnectionEntryOpeningAdapter {
    fun entryOpeningSettings(): List<ConnectionEntryOpeningSetting>
}

data class ConnectionEntryOpeningSetting(
    val title: StringResource,
    val preference: Preference<String>,
    val modes: List<EntryOpenMode>,
) {
    init {
        require(modes.isNotEmpty() && modes.distinct().size == modes.size)
    }
}
