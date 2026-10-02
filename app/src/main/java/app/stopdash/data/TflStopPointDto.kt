package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineRef
import app.stopdash.domain.StationPlaces
import app.stopdash.domain.StopLocation
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.riderLineName
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * TfL's `/StopPoint?lat=&lon=&radius=` response — the nearby-stops search behind the
 * in-app "near me now" list (SPEC *Finding stops*). Only the fields LDN Go maps are
 * declared; the client's `Json { ignoreUnknownKeys = true }` drops the rest (TfL returns
 * ~20 per stop, plus a paging envelope), so the DTO stays small and tolerant of fields
 * TfL adds later.
 */
@Serializable
data class TflStopPointsResponseDto(
    val stopPoints: List<TflStopPointDto> = emptyList(),
)

/**
 * One stop from the nearby search. [id] and [naptanId] are the same value in practice;
 * [id] is preferred and [naptanId] is the fallback. [lineModeGroups] groups the served
 * [lines] by mode, which is how a line's mode is recovered — the [lines] entries
 * themselves carry no mode (SPEC *Departures*: mode colors the row).
 */
@Serializable
data class TflStopPointDto(
    val id: String = "",
    val naptanId: String = "",
    val commonName: String = "",
    // TfL's NaPTAN stop type ("NaptanMetroStation", "TransportInterchange", …). Read only to pick a
    // station tree's departure-bearing stops ([departureStops]).
    val stopType: String = "",
    // Nullable so an omitted axis reads as absent, never as 0.0: London sits on the prime meridian,
    // so a real longitude can be exactly 0.0 (as on a Planner point, [TflJourneyPointDto]).
    val lat: Double? = null,
    val lon: Double? = null,
    val modes: List<String> = emptyList(),
    val lines: List<TflStopLineDto> = emptyList(),
    val lineModeGroups: List<TflLineModeGroupDto> = emptyList(),
    // TfL's parent cluster for this stop: a bus junction's poles and a station's platforms share
    // it (e.g. `490G000804`, `940GZZLUKSX`). Often blank for a bus pole with no assigned StopArea;
    // then the grouping falls back to the display name. Used only for grouping (SPEC D8), never a
    // coordinate.
    val stationNaptan: String = "",
    // TfL's interchange for this stop, above `stationNaptan`: `HUBKGX` ties King's Cross and St
    // Pancras together. Blank for a stop in no hub. Used to fold an interchange's shared disruption
    // (SPEC *Disruptions*); a public id, never a coordinate.
    val hubNaptanCode: String = "",
    // The bus stop's pole letter — the "D" a rider reads on the physical stop ("Stop D") — for the
    // per-pole bus header (SPEC D8: "King's Cross Station (D)"). Blank for a stop with no letter (a
    // station, or a bus stop TfL gives none). A public fact about the stop, never a coordinate.
    val stopLetter: String = "",
    // TfL's per-stop key/value extras. LDN Go reads the **CompassPoint** (the pole's bearing,
    // "E"/"SW" — [compassBearing]) and **Towards** (the pole's direction description, "Farringdon Or
    // Holborn Circus" — [towards]), the bus header's direction cues.
    val additionalProperties: List<TflAdditionalPropertyDto> = emptyList(),
    // The member stop points nested under this one — populated when TfL returns a hub tree from
    // `/StopPoint/{hubId}` (the interchange's stations and their platforms). Walked to collect every
    // member station's name for the disruption strip's alias set ([hubStationNames]); empty in the
    // flat nearby-search response.
    val children: List<TflStopPointDto> = emptyList(),
)

/** One TfL `additionalProperties` entry — LDN Go reads the `CompassPoint` and `Towards` [key]s. */
@Serializable
data class TflAdditionalPropertyDto(
    val category: String = "",
    val key: String = "",
    val value: String = "",
)

private fun TflStopPointDto.additionalProperty(key: String): String =
    additionalProperties.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()

/** The pole's compass bearing ("E", "SW") from TfL's `CompassPoint` property, else blank. */
fun TflStopPointDto.compassBearing(): String = additionalProperty("CompassPoint")

/** The pole's direction description ("Farringdon Or Holborn Circus") from TfL's `Towards`, else
 *  blank — the human-readable "towards …" cue on a bus header. */
fun TflStopPointDto.towards(): String = additionalProperty("Towards")

/**
 * Every distinct station-name spelling in this hub tree — this node's cleaned [commonName] plus all
 * descendants' — for the disruption strip's alias set. TfL spells one interchange a dozen ways
 * ("King's Cross St. Pancras Underground Station", "London St Pancras International LL Rail Station",
 * "St Pancras Intern'l & King's X Stns"); the union lets the strip drop whichever spelling a notice
 * leads with. Blank names are dropped and the rest deduped. Public station names, safe to carry.
 */
fun TflStopPointDto.hubStationNames(): List<String> =
    (listOf(commonName) + children.flatMap { it.hubStationNames() })
        .map { cleanStopName(it) }
        .filter { it.isNotBlank() }
        .distinct()

/**
 * The stops in this station tree that carry departures, for "Find a station": each node whose
 * [stopType] is one of [stopTypes] (a hub's stations, a bus stop area's poles) and serves a line,
 * without descending below it — a station's own platforms and entrances are part of it, not stops
 * of their own. The root counts too, so a searched station or pole returns itself.
 */
fun TflStopPointDto.departureStops(stopTypes: Collection<String>): List<TflStopPointDto> =
    if (stopType in stopTypes) {
        if (lines.isNotEmpty()) listOf(this) else emptyList()
    } else {
        children.flatMap { it.departureStops(stopTypes) }
    }

@Serializable
data class TflStopLineDto(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class TflLineModeGroupDto(
    val modeName: String = "",
    val lineIdentifier: List<String> = emptyList(),
)

/**
 * Maps a nearby-search stop to the domain [StopLocation], or null when it lacks the
 * identity LDN Go needs (a blank id, or no usable name). The name is cleaned of TfL's
 * type suffix ([cleanStopName]); each line's mode is recovered from [lineModeGroups],
 * falling back to the stop's primary mode, so a line still colors correctly at a
 * single-mode stop where the groups are redundant.
 */
fun TflStopPointDto.toStopLocationOrNull(): StopLocation? {
    val stopId = id.ifBlank { naptanId }
    val stopName = cleanStopName(commonName)
    if (stopId.isBlank() || stopName.isBlank()) return null
    val modeByLineId = buildMap {
        for (group in lineModeGroups) {
            for (lineId in group.lineIdentifier) put(lineId, group.modeName)
        }
    }
    val primaryMode = modes.firstOrNull().orEmpty()
    // TfL is inconsistent about a bus pole's compass: some poles carry it in CompassPoint ("S"),
    // others jam it into stopLetter as an arrow ("->N") in place of a real pole letter ("K"). An
    // arrow-in-stopLetter is not a letter — drop it and take the compass as the bearing, so a
    // compass-only pole renders the one way (the bearing path, a direction word like "Northbound")
    // whichever field TfL used, rather than a raw "-> N" down the letter path beside a "Southbound"
    // down the bearing path. A genuine pole letter ("K") is alphanumeric and stays on the letter path
    // ("Stop K", SPEC D8).
    val rawStopLetter = stopLetter.trim()
    val isPoleLetter = rawStopLetter.isNotEmpty() && rawStopLetter.all { it.isLetterOrDigit() }
    val poleBearing = compassBearing().trim().uppercase(Locale.ROOT)
        .ifBlank { if (isPoleLetter) "" else rawStopLetter.filter { it.isLetter() }.uppercase(Locale.ROOT) }
    return StopLocation(
        id = stopId,
        name = stopName,
        latitude = lat ?: 0.0,
        longitude = lon ?: 0.0,
        lines = lines
            .filter { it.id.isNotBlank() }
            .map {
                val mode = modeByLineId[it.id] ?: primaryMode
                // Named as a rider knows it, here where TfL's name comes in (SPEC *Line pill colors*).
                LineRef(id = it.id, name = riderLineName(it.name, mode), mode = mode)
            },
        // Cluster by TfL's StopArea/parent where it gives one, else by the cleaned name so a
        // station's same-named poles still merge (SPEC *Finding stops*). Keying on the id when TfL
        // provides it is what keeps a station whose name it spells several ways together.
        clusterId = stationNaptan.ifBlank { stopName },
        hubId = hubNaptanCode,
        // The pole's letter, bearing, and "towards" for the per-pole bus header (SPEC D8). All blank
        // for a station or a bus stop TfL gives none; an arrow-in-stopLetter drops to the bearing above.
        stopLetter = if (isPoleLetter) rawStopLetter else "",
        bearing = poleBearing,
        towards = towards().trim(),
    )
}

/** The stop points at the bottom of this tree (a stop area's poles); this one when it has none. */
fun TflStopPointDto.leaves(): List<TflStopPointDto> =
    if (children.isEmpty()) listOf(this) else children.flatMap { it.leaves() }

/**
 * Where the station [id] can be walked into: its own position and every entrance TfL lists under it
 * (a NaPTAN `…Entrance`), found wherever it sits in this tree, since TfL answers a station with its
 * whole interchange. A big station spans far more than its one published point: the rider at an
 * entrance is there (SPEC *On the way*). Empty when [id] isn't in the tree; a position TfL left
 * unset is skipped.
 */
fun TflStopPointDto.placesOf(id: String): StationPlaces {
    val station = find(id) ?: return StationPlaces()
    return StationPlaces(
        point = station.position(),
        entrances = station.descendants().filter { it.stopType.endsWith(ENTRANCE) }.mapNotNull { it.position() }.distinct(),
    )
}

// Both axes given, or none is taken: one alone would place it on the equator or meridian.
private fun TflStopPointDto.position(): Coordinates? = lat?.let { lat -> lon?.let { lon -> Coordinates(lat, lon) } }

private const val ENTRANCE = "Entrance"

private fun TflStopPointDto.find(id: String): TflStopPointDto? =
    if (id == this.id || id == naptanId) this else children.firstNotNullOfOrNull { it.find(id) }

private fun TflStopPointDto.descendants(): List<TflStopPointDto> = children.flatMap { listOf(it) + it.descendants() }
