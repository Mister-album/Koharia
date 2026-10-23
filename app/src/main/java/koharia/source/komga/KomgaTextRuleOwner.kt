package koharia.source.komga

import android.content.SharedPreferences
import java.util.UUID

internal fun localKomgaTextRuleOwner(preferences: SharedPreferences, binding: String): String =
    synchronized(preferences) {
        val key = "text_rule_owner_$binding"
        preferences.getString(key, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(key, it).apply()
        }
    }
