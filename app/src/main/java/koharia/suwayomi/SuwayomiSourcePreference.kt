package koharia.suwayomi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One setting a source extension exposes. The server models these as an Android preference screen
 * and identifies each entry by its index in `SourceType.preferences`, which is the `position` a
 * change is applied at.
 */
@Serializable
sealed interface SuwayomiSourcePreference {
    val position: Int
    val key: String?
    val title: String?
    val summary: String?
    val enabled: Boolean
    val visible: Boolean

    /** Label shown when the extension provides no title. */
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: key.orEmpty()

    /**
     * Summary with the platform's `%s` placeholder resolved against the current value, which is how
     * a source normally shows its selection. An unresolved placeholder is dropped rather than shown.
     */
    fun resolvedSummary(current: String): String? {
        val raw = summary?.takeIf { it.isNotBlank() } ?: return null
        if (!raw.contains("%s")) return raw
        val filled = if (current.isBlank()) raw.replace("%s", "") else raw.replace("%s", current)
        return filled.lines().joinToString("\n") { it.trim() }.trim().takeIf { it.isNotBlank() }
    }
}

@Serializable
@SerialName("SwitchPreference")
data class SuwayomiSwitchPreference(
    override val position: Int = 0,
    override val key: String? = null,
    override val title: String? = null,
    override val summary: String? = null,
    override val enabled: Boolean = true,
    override val visible: Boolean = true,
    val currentValue: Boolean? = null,
    val default: Boolean = false,
) : SuwayomiSourcePreference

@Serializable
@SerialName("CheckBoxPreference")
data class SuwayomiCheckBoxPreference(
    override val position: Int = 0,
    override val key: String? = null,
    override val title: String? = null,
    override val summary: String? = null,
    override val enabled: Boolean = true,
    override val visible: Boolean = true,
    val currentValue: Boolean? = null,
    val default: Boolean = false,
) : SuwayomiSourcePreference

@Serializable
@SerialName("EditTextPreference")
data class SuwayomiEditTextPreference(
    override val position: Int = 0,
    override val key: String? = null,
    override val title: String? = null,
    override val summary: String? = null,
    override val enabled: Boolean = true,
    override val visible: Boolean = true,
    val currentValue: String? = null,
    val default: String? = null,
    val dialogTitle: String? = null,
    val dialogMessage: String? = null,
    val text: String? = null,
) : SuwayomiSourcePreference

@Serializable
@SerialName("ListPreference")
data class SuwayomiListPreference(
    override val position: Int = 0,
    override val key: String? = null,
    override val title: String? = null,
    override val summary: String? = null,
    override val enabled: Boolean = true,
    override val visible: Boolean = true,
    val currentValue: String? = null,
    val default: String? = null,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
) : SuwayomiSourcePreference {
    /** Index of the current value inside [entryValues], or 0 when it cannot be resolved. */
    val selectedIndex: Int
        get() = entryValues.indexOf(currentValue ?: default).takeIf { it >= 0 } ?: 0
}

@Serializable
@SerialName("MultiSelectListPreference")
data class SuwayomiMultiSelectPreference(
    override val position: Int = 0,
    override val key: String? = null,
    override val title: String? = null,
    override val summary: String? = null,
    override val enabled: Boolean = true,
    override val visible: Boolean = true,
    val currentValue: List<String>? = null,
    val default: List<String>? = null,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
    val dialogTitle: String? = null,
    val dialogMessage: String? = null,
) : SuwayomiSourcePreference {
    val selected: Set<String> get() = currentValue?.toSet() ?: default?.toSet().orEmpty()
}

/** One pending preference edit, sent to the server as a `SourcePreferenceChangeInput`. */
data class SuwayomiPreferenceChange(
    val switchState: Boolean? = null,
    val checkBoxState: Boolean? = null,
    val editTextState: String? = null,
    val listState: String? = null,
    val multiSelectState: List<String>? = null,
) {
    fun toJson(position: Int): JsonObject = buildJsonObject {
        put("position", position)
        switchState?.let { put("switchState", it) }
        checkBoxState?.let { put("checkBoxState", it) }
        editTextState?.let { put("editTextState", it) }
        listState?.let { put("listState", it) }
        multiSelectState?.let { values ->
            putJsonArray("multiSelectState") { values.forEach { add(JsonPrimitive(it)) } }
        }
    }

    companion object {
        /** Rejects a value the preference cannot carry, so a caller bug cannot send a wrong type. */
        fun of(preference: SuwayomiSourcePreference, value: Any): SuwayomiPreferenceChange = when (preference) {
            is SuwayomiSwitchPreference -> SuwayomiPreferenceChange(switchState = value as Boolean)
            is SuwayomiCheckBoxPreference -> SuwayomiPreferenceChange(checkBoxState = value as Boolean)
            is SuwayomiEditTextPreference -> SuwayomiPreferenceChange(editTextState = value as String)
            is SuwayomiListPreference -> SuwayomiPreferenceChange(listState = value as String)
            is SuwayomiMultiSelectPreference -> {
                @Suppress("UNCHECKED_CAST")
                SuwayomiPreferenceChange(multiSelectState = value as List<String>)
            }
        }
    }
}
