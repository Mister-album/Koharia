package eu.kanade.domain.track.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID

class DelayedTrackingStore internal constructor(private val preferences: SharedPreferences) {

    /**
     * Preference file where queued tracking updates are stored.
     */
    constructor(context: Context) : this(context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE))

    fun add(trackId: Long, lastChapterRead: Double) = synchronized(preferences) {
        val previousLastChapterRead = preferences.getFloat(trackId.toString(), 0f)
        if (lastChapterRead > previousLastChapterRead) {
            logcat(LogPriority.DEBUG) { "Queuing track item: $trackId, last chapter read: $lastChapterRead" }
            preferences.edit {
                putFloat(trackId.toString(), lastChapterRead.toFloat())
            }
        }
    }

    fun remove(trackId: Long) = synchronized(preferences) {
        preferences.edit {
            remove(trackId.toString())
        }
    }

    fun getItems(): List<DelayedTrackingItem> = synchronized(preferences) {
        preferences.all.mapNotNull {
            DelayedTrackingItem(
                trackId = it.key.toLongOrNull() ?: return@mapNotNull null,
                lastChapterRead = it.value.toString().toFloatOrNull() ?: return@mapNotNull null,
            )
        }
    }

    /** Legacy payloads have no account/resource revision and must never acquire current credentials. */
    fun holdLegacyItems(): Boolean = synchronized(preferences) {
        val editor = preferences.edit()
        for ((key, value) in preferences.all) {
            if (key.startsWith(HELD_PREFIX)) continue
            val heldKey = "$HELD_PREFIX${UUID.randomUUID()}::$key"
            when (value) {
                is String -> editor.putString(heldKey, value)
                is Float -> editor.putFloat(heldKey, value)
                is Int -> editor.putInt(heldKey, value)
                is Long -> editor.putLong(heldKey, value)
                is Boolean -> editor.putBoolean(heldKey, value)
                is Set<*> -> {
                    if (value.any { it !is String }) return@synchronized false
                    editor.putStringSet(heldKey, value.filterIsInstance<String>().toSet())
                }
                else -> return@synchronized false
            }
            editor.remove(key)
        }
        // Also retries a prior failed disk commit when its in-memory migration already took effect.
        editor.commit()
    }

    fun getHeldItems(): List<HeldTrackingItem> = synchronized(preferences) {
        preferences.all.filterKeys { it.startsWith(HELD_PREFIX) }.map { (key, value) ->
            HeldTrackingItem(key, key.removePrefix(HELD_PREFIX).substringAfter("::"), value, "unknown_owner")
        }
    }

    data class HeldTrackingItem(
        val recordKey: String,
        val originalKey: String,
        val originalValue: Any?,
        val reason: String,
    )

    data class DelayedTrackingItem(
        val trackId: Long,
        val lastChapterRead: Float,
    )

    private companion object {
        const val HELD_PREFIX = "held::unknown_owner::"
    }
}
