package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.StationIndex
import app.stopdash.domain.cleanStopName
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled station index (SPEC *Finding stops → Find a station*), built from TfL by
 * `scripts/build_station_index.py` and committed by the `station-index` workflow. Read once per
 * process, off the main thread, and cached.
 *
 * Fails safe to [StationIndex.EMPTY]: a missing, corrupt or newer-format asset leaves the search
 * on TfL's own matching, as before the index existed, rather than breaking it (SPEC principle 2).
 */
object StationIndexStore {
    private const val ASSET = "stations/station_index.json"
    private const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: StationIndex? = null

    /** The bundled index, parsed once and cached. Reads the asset: call it off the main thread. */
    fun load(context: Context): StationIndex {
        cached?.let { return it }
        val index = try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            // Static public data: a failure carries nothing about the user, so naming it is safe.
            parse(text) { Log.w("StopDash.Stations", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            Log.w("StopDash.Stations", "no bundled station index; searching TfL only")
            StationIndex.EMPTY
        } catch (e: Exception) {
            Log.w("StopDash.Stations", "station index load failed: ${e::class.simpleName}")
            StationIndex.EMPTY
        }
        cached = index
        return index
    }

    /**
     * Parse the asset text. Kept free of Android calls so the bundled asset is validated by a JVM
     * test. A wrong version is ignored whole; an entry without an id or a name is skipped. Names
     * arrive as TfL spells them and are cleaned here ([cleanStopName]), so the list and the rest of
     * the app trim "Underground Station" by the one rule.
     */
    internal fun parse(text: String, warn: (String) -> Unit = {}): StationIndex {
        val file = json.decodeFromString<StationIndexFile>(text)
        if (file.version != CURRENT_VERSION) {
            warn("station index version ${file.version} != $CURRENT_VERSION, ignoring")
            return StationIndex.EMPTY
        }
        return StationIndex(
            file.stations
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .map {
                    IndexedStation(
                        id = it.id,
                        name = cleanStopName(it.name),
                        modes = it.modes,
                        hubId = it.hub,
                        latitude = it.lat,
                        longitude = it.lon,
                        lines = it.modeLines,
                        routeEnds = it.routeEnds,
                        platforms = it.platforms,
                    )
                },
            lineNames = file.lineNames,
        )
    }

    @Serializable
    private data class StationIndexFile(
        val version: Int = 0,
        val stations: List<StationDto> = emptyList(),
        // Line id → name as TfL spells it; absent from an older index.
        val lineNames: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class StationDto(
        val id: String = "",
        val name: String = "",
        val modes: List<String> = emptyList(),
        val hub: String = "",
        // Added without a version bump: an older index simply lacks them (no "From …" buttons).
        val lat: Double? = null,
        val lon: Double? = null,
        // Mode → line ids. Not "lines": an earlier build wrote that as a tube-only list, which this
        // ignores (ignoreUnknownKeys) rather than failing the whole index on.
        val modeLines: Map<String, List<String>> = emptyMap(),
        val routeEnds: Map<String, List<String>> = emptyMap(),
        // Absent from an older index, whose trips then drop a train named by its platform alone.
        val platforms: List<String> = emptyList(),
    )
}
