package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.NearbyStopsCache
import app.stopdash.domain.NearbyStopsStore
import app.stopdash.domain.StopLocation
import app.stopdash.domain.riderLineName
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [NearbyStopsStore] in one JSON [file] — the app's cache directory, which the OS never includes in
 * a backup, so the lookup positions it holds stay on the device (`docs/PRIVACY.md`). An unreadable
 * or unparseable file loads as empty and is deleted; a failed write is logged
 * (a bare reason, never a coordinate) and the in-memory cache carries on.
 */
internal class FileNearbyStopsStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) : NearbyStopsStore {
    // The write's staging copy. Only ever a leftover here (a process killed mid-save), so load deletes it.
    private val tmp = File(file.path + ".tmp")

    override fun load(): List<NearbyStopsCache.Entry> {
        if (tmp.exists() && !tmp.delete()) warn("nearby stops cache: stale temp file not deleted")
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<PersistedNearbyStops>(file.readText()).entries.map { it.toEntry() }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        } catch (e: DateTimeParseException) {
            // A stored timestamp that no longer parses.
            discard("invalid", e)
        }
    }

    // A file that can't be read is deleted, not left behind: it may hold lookup positions, and with
    // nothing loaded from it the normal day-old expiry would never reach them (docs/PRIVACY.md).
    private fun discard(why: String, e: Exception): List<NearbyStopsCache.Entry> {
        val deleted = file.delete()
        warn("nearby stops cache $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return emptyList()
    }

    override fun save(entries: List<NearbyStopsCache.Entry>) {
        try {
            tmp.writeText(json.encodeToString(PersistedNearbyStops(entries.map { it.toPersisted() })))
            // Replace in one step, so a reader never sees a half-written file.
            if (!tmp.renameTo(file)) warn("nearby stops cache not saved: rename failed")
        } catch (e: IOException) {
            warn("nearby stops cache not saved: ${e::class.simpleName}")
        } finally {
            // Whatever happened, no staging copy (it may hold positions) is left behind.
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedNearbyStops(val entries: List<PersistedNearbyEntry> = emptyList())

@Serializable
private data class PersistedNearbyEntry(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val stopTypes: List<String>,
    val at: String,
    val stops: List<PersistedNearbyStop>,
)

@Serializable
private data class PersistedNearbyStop(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lines: List<PersistedNearbyLine> = emptyList(),
    val clusterId: String = "",
    val hubId: String = "",
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
)

@Serializable
private data class PersistedNearbyLine(val id: String, val name: String, val mode: String)

private fun NearbyStopsCache.Entry.toPersisted() = PersistedNearbyEntry(
    latitude, longitude, radiusMeters, stopTypes, at.toString(),
    stops.map { s ->
        PersistedNearbyStop(
            s.id, s.name, s.latitude, s.longitude, s.lines.map { PersistedNearbyLine(it.id, it.name, it.mode) },
            s.clusterId, s.hubId, s.stopLetter, s.bearing, s.towards,
        )
    },
)

private fun PersistedNearbyEntry.toEntry() = NearbyStopsCache.Entry(
    latitude, longitude, radiusMeters, stopTypes, Instant.parse(at),
    stops.map { s ->
        StopLocation(
            s.id, s.name, s.latitude, s.longitude, s.lines.map { LineRef(it.id, riderLineName(it.name, it.mode), it.mode) },
            s.clusterId, s.hubId, s.stopLetter, s.bearing, s.towards,
        )
    },
)
