package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.RecentStations
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
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
 * simply not remembered.
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
    fun loadPicks(): List<SearchEntry> {
        if (tmp.exists() && !tmp.delete()) warn("recent stations: stale temp file not deleted")
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<PersistedRecentStations>(file.readText()).stations.map { it.toDomain() }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        }
    }

    /** Put [opened] at the front of the list ([RecentStations.add]) and save it. */
    @Synchronized
    fun add(opened: StationMatch) = save(RecentStations.add(loadPicks(), SearchEntry.Stop(opened)))

    /** Put the geocoded place [picked] at the front of the list, as a station opened is, and save it. */
    @Synchronized
    fun addPlace(picked: PlaceHit) = save(RecentStations.add(loadPicks(), SearchEntry.Place(picked)))

    private fun save(picks: List<SearchEntry>) {
        try {
            // Its directory is named, not checked, when the store is built ([AppDirs]), so made here.
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(PersistedRecentStations(picks.map { it.toPersisted() })))
            // Replace in one step, so a reader never sees a half-written file.
            if (!tmp.renameTo(file)) warn("recent stations not saved: rename failed")
        } catch (e: IOException) {
            warn("recent stations not saved: ${e::class.simpleName}")
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
private data class PersistedRecentStations(val stations: List<PersistedRecentStation> = emptyList())

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
    // An interchange's station name's modes ([StationMatch.lead]); empty for any other stop, and in a
    // list written before names were listed.
    val lead: List<String> = emptyList(),
)

private fun PersistedRecentStation.toDomain(): SearchEntry =
    if (latitude != null && longitude != null) {
        val kind = PlaceKind.entries.firstOrNull { it.name == placeKind } ?: PlaceKind.PLACE
        SearchEntry.Place(PlaceHit(name, Coordinates(latitude, longitude), kind))
    } else {
        SearchEntry.Stop(StationMatch(id, name, modes, lead = lead))
    }

private fun SearchEntry.toPersisted(): PersistedRecentStation = when (this) {
    is SearchEntry.Stop -> PersistedRecentStation(match.id, match.name, match.modes, lead = match.lead)
    is SearchEntry.Place -> PersistedRecentStation(
        id = "", name = hit.name, latitude = hit.coordinate.latitude, longitude = hit.coordinate.longitude, placeKind = hit.kind.name,
    )
}
