package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStopsStore
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
 * [RouteStopsStore] in one JSON [file] — the app's cache directory, which the OS never includes in
 * a backup, so which routes and stop areas were looked up stays on the device (`docs/PRIVACY.md`).
 * An unreadable, unparseable, or other-version file loads as empty and is deleted; a failed write is
 * logged (a bare reason) and the in-memory cache carries on.
 */
internal class FileRouteStopsStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) : RouteStopsStore {
    // The write's staging copy. Only ever a leftover here (a process killed mid-save), so load deletes it.
    private val tmp = File(file.path + ".tmp")

    override fun load(): RouteStopsStore.Contents {
        if (tmp.exists() && !tmp.delete()) warn("route cache: stale temp file not deleted")
        if (!file.exists()) return RouteStopsStore.Contents()
        return try {
            val persisted = json.decodeFromString<PersistedRouteStops>(file.readText())
            if (persisted.version != VERSION) return discard("version ${persisted.version}", null)
            RouteStopsStore.Contents(
                persisted.sequences.associate { it.key to RouteStopsStore.Timed(Instant.parse(it.at), it.toSequence()) },
                persisted.poles.associate { area ->
                    area.areaId to RouteStopsStore.Timed(Instant.parse(area.at), area.stops.map { it.toStop() })
                },
                persisted.unknown.associate { it.key to Instant.parse(it.at) },
            )
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        } catch (e: DateTimeParseException) {
            // A stored timestamp that no longer parses.
            discard("invalid", e)
        }
    }

    // A file that can't be read is deleted, not left behind: nothing loaded from it would expire.
    private fun discard(why: String, e: Exception?): RouteStopsStore.Contents {
        val deleted = file.delete()
        val cause = e?.let { " (${it::class.simpleName})" }.orEmpty()
        warn("route cache $why$cause, ${if (deleted) "deleted" else "not deleted"}")
        return RouteStopsStore.Contents()
    }

    override fun save(contents: RouteStopsStore.Contents) {
        try {
            val persisted = PersistedRouteStops(
                VERSION,
                contents.sequences.map { (key, entry) -> entry.value.toPersisted(key, entry.at) },
                contents.poles.map { (areaId, entry) ->
                    RouteCacheAreaPoles(areaId, entry.at.toString(), entry.value.map { it.toPersisted() })
                },
                contents.unknown.map { (key, at) -> RouteCacheUnknown(key, at.toString()) },
            )
            // Its directory is named, not checked, when the store is built ([AppDirs]), so made here.
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(persisted))
            // Replace in one step, so a reader never sees a half-written file.
            if (!tmp.renameTo(file)) warn("route cache not saved: rename failed")
        } catch (e: IOException) {
            warn("route cache not saved: ${e::class.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        // Bumped when the shape changes, so a newer build never half-reads an older file.
        const val VERSION = 2
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedRouteStops(
    val version: Int = 0,
    val sequences: List<RouteCacheSequence> = emptyList(),
    val poles: List<RouteCacheAreaPoles> = emptyList(),
    // Absent from a file an older build wrote, which reads as none: no version change needed.
    val unknown: List<RouteCacheUnknown> = emptyList(),
)

// A line+direction TfL has no route for, and when it said so.
@Serializable
private data class RouteCacheUnknown(val key: String, val at: String)

@Serializable
private data class RouteCacheSequence(
    val key: String,
    val at: String,
    val routes: List<RouteCacheRoute> = emptyList(),
    val stopNames: Map<String, String> = emptyMap(),
    val stopLines: Map<String, List<RouteCacheLine>> = emptyMap(),
    val stopPositions: Map<String, RouteCachePosition> = emptyMap(),
    val stopAreas: Map<String, String> = emptyMap(),
    val stopHubs: Map<String, String> = emptyMap(),
)

@Serializable
// [direction] is blank in a sequence cached before it was kept: the route still resolves, it just
// can't be picked by a train's direction until it is fetched again.
private data class RouteCacheRoute(val name: String, val stopIds: List<String>, val direction: String = "")

@Serializable
private data class RouteCachePosition(val latitude: Double, val longitude: Double)

@Serializable
private data class RouteCacheLine(val id: String, val name: String, val mode: String)

@Serializable
private data class RouteCacheAreaPoles(val areaId: String, val at: String, val stops: List<RouteCacheStop> = emptyList())

@Serializable
private data class RouteCacheStop(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lines: List<RouteCacheLine> = emptyList(),
    val clusterId: String = "",
    val hubId: String = "",
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
)

private fun LineRef.toPersisted() = RouteCacheLine(id, name, mode)

private fun RouteCacheLine.toLine() = LineRef(id, riderLineName(name, mode), mode)

private fun LineSequence.toPersisted(key: String, at: Instant) = RouteCacheSequence(
    key,
    at.toString(),
    routes.map { RouteCacheRoute(it.name, it.stopIds, it.direction) },
    stopNames,
    stopLines.mapValues { (_, lines) -> lines.map { it.toPersisted() } },
    stopPositions.mapValues { (_, p) -> RouteCachePosition(p.first, p.second) },
    stopAreas,
    stopHubs,
)

private fun RouteCacheSequence.toSequence() = LineSequence(
    routes.map { LineRoute(it.name, it.stopIds, it.direction) },
    stopNames,
    stopLines.mapValues { (_, lines) -> lines.map { it.toLine() } },
    stopPositions.mapValues { (_, p) -> p.latitude to p.longitude },
    stopAreas,
    stopHubs,
)

private fun StopLocation.toPersisted() = RouteCacheStop(
    id, name, latitude, longitude, lines.map { it.toPersisted() }, clusterId, hubId, stopLetter, bearing, towards,
)

private fun RouteCacheStop.toStop() = StopLocation(
    id, name, latitude, longitude, lines.map { it.toLine() }, clusterId, hubId, stopLetter, bearing, towards,
)
