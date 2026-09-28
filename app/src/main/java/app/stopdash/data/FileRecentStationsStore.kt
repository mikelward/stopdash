package app.stopdash.data

import app.stopdash.domain.RecentStations
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
 * (`docs/PRIVACY.md`) — the places a rider looks up can say where they go. Blocking; call it off the
 * main thread. An unreadable or unparseable file loads as empty and is deleted; a failed write is
 * logged (a bare reason, never a station) and the list is simply not remembered.
 */
internal class FileRecentStationsStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun load(): List<StationMatch> {
        if (tmp.exists() && !tmp.delete()) warn("recent stations: stale temp file not deleted")
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<PersistedRecentStations>(file.readText()).stations.map { StationMatch(it.id, it.name, it.modes) }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        }
    }

    /** Put [opened] at the front of the list ([RecentStations.add]) and save it. */
    @Synchronized
    fun add(opened: StationMatch) {
        save(RecentStations.add(load(), opened))
    }

    /**
     * Take the station [id] off the list ([RecentStations.remove]) and save it. False when the write
     * failed, so the list still holds it and the caller can say so rather than let it reappear.
     */
    @Synchronized
    fun remove(id: String): Boolean = save(RecentStations.remove(load(), id))

    /** Write [stations] in one step; false (and a bare reason logged) when they couldn't be. */
    private fun save(stations: List<StationMatch>): Boolean {
        return try {
            tmp.writeText(json.encodeToString(PersistedRecentStations(stations.map { PersistedRecentStation(it.id, it.name, it.modes) })))
            // Replace in one step, so a reader never sees a half-written file.
            tmp.renameTo(file).also { if (!it) warn("recent stations not saved: rename failed") }
        } catch (e: IOException) {
            warn("recent stations not saved: ${e::class.simpleName}")
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun discard(why: String, e: Exception): List<StationMatch> {
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

@Serializable
private data class PersistedRecentStation(val id: String, val name: String, val modes: List<String> = emptyList())
