package app.stopdash.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The saved favorites as read from storage: either the current list, or **unavailable** because a
 * stored list exists that this build can't read (written by a newer schema version). Kept distinct
 * so a surface never treats a newer-version list as empty — an empty [Loaded] is "no favorites
 * yet", [Unavailable] is "there are favorites, but not ones this build understands" (SPEC principle
 * 2). The store never overwrites an [Unavailable] file, so the favorites survive a downgrade or
 * rollback (SPEC *never lose the user's work*). Mirrors [StarredRowSet].
 */
sealed interface FavoritePlacesSet {
    /** The current favorites, in display order — possibly empty. */
    data class Loaded(val places: List<FavoritePlace>) : FavoritePlacesSet

    /** A stored list exists but was written by a newer build; it is preserved untouched. */
    data object Unavailable : FavoritePlacesSet

    /**
     * A stored file existed but was **unreadable (corrupt)** and has been discarded, so the user's
     * prior favorites are lost. The list is empty and **writable** — the user can add places again —
     * which is why this is distinct from [Unavailable] (a newer-schema file, preserved and *not*
     * overwritten) and from an empty [Loaded] (a genuinely new user): the surface can say the data was
     * lost rather than silently showing "nothing saved" (SPEC principle 2 / *never lose work silently*).
     */
    data object Discarded : FavoritePlacesSet
}

/**
 * Reads and writes the user's **favorite places** — Home, Work, School and custom (SPEC D9). A seam
 * (interface) so a ViewModel depends on the capability, not on DataStore, and a JVM test can supply
 * a fake without Android. The concrete DataStore-backed implementation lives in the `data` layer.
 *
 * [places] is a cold [Flow] the caller collects: it emits the current [FavoritePlacesSet] at once
 * and again on every change. [save] and [remove] are suspending, meant to run off the main thread,
 * and best-effort; against a list this build can't read ([FavoritePlacesSet.Unavailable]) they
 * preserve the stored file untouched rather than overwriting it with a downgraded one.
 */
interface FavoritePlacesStore {
    /** The current favorites, re-emitted on every change; [FavoritePlacesSet.Unavailable] when a
     *  stored list was written by a newer build. */
    fun places(): Flow<FavoritePlacesSet>

    /** Add or replace [place] ([FavoritePlaces.upsert]). A no-op if the stored list is
     *  [FavoritePlacesSet.Unavailable] (preserved, never overwritten). */
    suspend fun save(place: FavoritePlace)

    /** Remove the favorite with [id] ([FavoritePlaces.remove]). A no-op if the stored list is
     *  [FavoritePlacesSet.Unavailable]. */
    suspend fun remove(id: String)

    companion object {
        /** A store that persists nothing and always reads an empty list — the default for tests and
         *  a build with no wired DataStore, so the app runs identically minus favorites. */
        val NONE: FavoritePlacesStore = object : FavoritePlacesStore {
            override fun places(): Flow<FavoritePlacesSet> = flowOf(FavoritePlacesSet.Loaded(emptyList()))
            override suspend fun save(place: FavoritePlace) {}
            override suspend fun remove(id: String) {}
        }
    }
}
