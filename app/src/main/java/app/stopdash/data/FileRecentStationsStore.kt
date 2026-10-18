package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.Pick
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.PlaceType
import app.stopdash.domain.RecentStations
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The stations recently opened from "Find a station", newest first, in one JSON [file] in the app's
 * no-backup directory: it survives a restart but not a device transfer, and never leaves the device
 * (`docs/PRIVACY.md`) — the places a rider looks up can say where they go. A To… search keeps the
 * geocoded places it picked among them, each with its name and coordinate, in the order picked.
 * Blocking; call it off the main thread. An unreadable or unparseable file loads as empty and is
 * deleted; a failed write is logged (a bare reason, never a station or a place) and the list is
 * simply not remembered. A file a newer build wrote ([PersistedRecentStations.CURRENT_VERSION]) loads
 * as empty and is kept as it is: never written over, so going back to that build loses nothing.
 */
internal class FileRecentStationsStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")

    /** The stations alone, newest first. */
    @Synchronized
    fun load(): List<StationMatch> = loadPicks().filterIsInstance<SearchEntry.Stop>().map { it.match }

    /** Every pick, stations and places, newest first. */
    @Synchronized
    fun loadPicks(): List<SearchEntry> = read().orEmpty()

    // The picks, or null for a file a newer build wrote, kept unread.
    private fun read(): List<SearchEntry>? {
        if (tmp.exists() && !tmp.delete()) warn("recent stations: stale temp file not deleted")
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if ((savedVersion(text) ?: 1) > PersistedRecentStations.CURRENT_VERSION) {
                warn("recent stations file is a newer schema version; preserving it, not overwriting")
                null
            } else {
                json.decodeFromString<PersistedRecentStations>(text).stations.map { it.toDomain() }
            }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        }
    }

    /** Put [opened] at the front of the list ([RecentStations.add]) and save it. */
    @Synchronized
    fun add(opened: StationMatch) { edit { RecentStations.add(it, SearchEntry.Stop(opened)) } }

    /** Put the geocoded place [picked] at the front of the list, as a station opened is, and save it. */
    @Synchronized
    fun addPlace(picked: PlaceHit) { edit { RecentStations.add(it, SearchEntry.Place(picked)) } }

    /**
     * Take [removed], a pick as the search listed it, off the list ([RecentStations.remove]) and save it;
     * false when the save failed (logged), or the file is a newer build's, so the search can say the row stayed.
     */
    @Synchronized
    fun remove(removed: SearchEntry): Boolean = edit { RecentStations.remove(it, removed) }

    // Whether [change] was saved: never over a newer build's file.
    private fun edit(change: (List<SearchEntry>) -> List<SearchEntry>): Boolean = read()?.let { save(change(it)) } ?: false

    // Whether the list was saved; a failure is logged and the old file left as it was.
    private fun save(picks: List<SearchEntry>): Boolean {
        return try {
            // Its directory is named, not checked, when the store is built ([AppDirs]), so made here.
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(PersistedRecentStations(version = PersistedRecentStations.CURRENT_VERSION, stations = picks.map { it.toPersisted() })))
            // Replace in one step, so a reader never sees a half-written file.
            tmp.renameTo(file).also { if (!it) warn("recent stations not saved: rename failed") }
        } catch (e: IOException) {
            warn("recent stations not saved: ${e::class.simpleName}")
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun discard(why: String, e: Exception): List<SearchEntry> {
        val deleted = file.delete()
        warn("recent stations $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return emptyList()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedRecentStations(
    // A file without one is version 1, whatever this build's is; written even then, so every file
    // says its version. Each write names this build's ([CURRENT_VERSION]).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val version: Int = 1,
    val stations: List<PersistedRecentStation> = emptyList(),
) {
    companion object {
        /** The current on-disk format. Bump when a field an older build would drop or misread is added. */
        // 2: a stop's level ([PersistedRecentStation.level]), which an older build would drop.
        const val CURRENT_VERSION = 2
    }
}

// A station by its id; a geocoded place by its coordinate, with no id. Its coordinate and kind are
// defaulted, so a list written before places were kept reads back as stations.
@Serializable
private data class PersistedRecentStation(
    val id: String,
    val name: String,
    val modes: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    // A place's [PlaceKind] by name; one this build doesn't know reads as a place.
    val placeKind: String? = null,
    // A place's [PlaceType] by code; none (an older list) or one this build doesn't know reads as a place.
    val placeType: String? = null,
    // An interchange's station name's modes ([StationMatch.lead]); empty for any other stop, and in a
    // list written before names were listed.
    val lead: List<String> = emptyList(),
    // The level the stop was picked at ([StationMatch.level]) by its code; absent where the row didn't
    // say, in a list written before levels were, or for a code this build doesn't know.
    val level: String? = null,
)

private fun PersistedRecentStation.toDomain(): SearchEntry =
    if (latitude != null && longitude != null) {
        val kind = PlaceKind.entries.firstOrNull { it.name == placeKind } ?: PlaceKind.PLACE
        val type = placeType?.let { PlaceType.ofCode(it) } ?: PlaceType.PLACE
        SearchEntry.Place(PlaceHit(name, Coordinates(latitude, longitude), kind, type = type))
    } else {
        SearchEntry.Stop(StationMatch(id, name, modes, lead = lead, level = Pick.Level.ofCode(level)))
    }

private fun SearchEntry.toPersisted(): PersistedRecentStation = when (this) {
    is SearchEntry.Stop -> PersistedRecentStation(match.id, match.name, match.modes, lead = match.lead, level = match.level?.code)
    is SearchEntry.Place -> PersistedRecentStation(
        id = "", name = hit.name, latitude = hit.coordinate.latitude, longitude = hit.coordinate.longitude, placeKind = hit.kind.name,
        placeType = hit.type.code,
    )
}
