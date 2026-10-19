package app.stopdash.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The bundled station index's file format (`scripts/build_station_index.py`), read into a
 * [StationIndex]. The app's store reads the asset through it, and so does the build's station
 * bundle, so both see one reading of the file.
 */
object StationIndexFile {
    private const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse the bundled index (`assets/stations/station_index.json`). Kept free of Android calls, so a
     * JVM test and the build's station bundle ([StationBundle]) read it as the app does. A wrong version is ignored whole; an entry without an id or a name is skipped. Names
     * arrive as TfL spells them and are cleaned here ([cleanStopName]), so the list and the rest of
     * the app trim "Underground Station" by the one rule.
     */
    fun parse(text: String, warn: (String) -> Unit = {}): StationIndex {
        val file = json.decodeFromString<FileDto>(text)
        if (file.version != CURRENT_VERSION) {
            warn("station index version ${file.version} != $CURRENT_VERSION, ignoring")
            return StationIndex.EMPTY
        }
        val readable = file.stations.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        return StationIndex(
            readable
                .map {
                    IndexedStation(
                        id = it.id,
                        name = cleanStopName(it.name),
                        modes = it.modes,
                        interchangeId = it.hub,
                        latitude = it.lat,
                        longitude = it.lon,
                        lines = it.modeLines,
                        routeEnds = it.routeEnds,
                        terminusEnds = it.terminusEnds,
                        platforms = it.platforms,
                        icsCode = it.icsCode,
                        station = it.station,
                        modelHub = it.modelHub,
                    )
                },
            lineNames = file.lineNames,
            revision = file.revision,
            skippedEntries = file.stations.size - readable.size,
        )
    }

    @Serializable
    private data class FileDto(
        val version: Int = 0,
        // The content revision stops.tsv shares (build_stops.py); absent from an older index.
        val revision: String = "",
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
        // Absent from an older index, whose terminus lists its trains as TfL does.
        val terminusEnds: Map<String, List<String>> = emptyMap(),
        // Absent from an older index, whose trips then drop a train named by its platform alone.
        val platforms: List<String> = emptyList(),
        // The station model's membership (dev-docs/station-model.md); absent from an older index.
        val icsCode: String = "",
        val station: String = "",
        val modelHub: String = "",
    )
}
