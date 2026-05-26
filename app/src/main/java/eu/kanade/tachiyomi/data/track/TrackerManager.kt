package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.komga.Komga
import kotlinx.coroutines.flow.combine

class TrackerManager {

    companion object {
        const val ANILIST = 2L
        const val KITSU = 3L
        const val KOMGA = 6L
        const val KAVITA = 8L
    }

    val komga = Komga(KOMGA)

    val trackers = listOf(komga)

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = trackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = trackers.filter { it.id in ids }
}
