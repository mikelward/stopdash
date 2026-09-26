package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.cleanStopName
import kotlinx.serialization.Serializable

/**
 * TfL's `/Line/{id}/Route/Sequence/{direction}` response, trimmed to what the route detail's stop
 * list needs: each end-to-end route's ordered stop ids, a name for every stop, and the lines at
 * each stop and its interchange (its `topMostParentId`, a hub listed under `stations`) for the
 * connection chips, and the [direction] it was asked for (so a train's own direction can pick
 * its routes). The geometry (`lineStrings`) is ignored ([Json] `ignoreUnknownKeys`).
 */
@Serializable
data class TflRouteSequenceDto(
    val direction: String = "",
    val orderedLineRoutes: List<TflOrderedRouteDto> = emptyList(),
    val stopPointSequences: List<TflStopPointSequenceDto> = emptyList(),
    val stations: List<TflMatchedStopDto> = emptyList(),
) {
    fun toLineSequence(requested: String = ""): LineSequence {
        // TfL echoes the direction asked for; the request's own wins where it's one TfL knows.
        val routeDirection = requested.lowercase().takeIf { it == "inbound" || it == "outbound" } ?: direction.lowercase()
        val names = HashMap<String, String>()
        for (stop in stations + stopPointSequences.flatMap { it.stopPoint }) {
            if (stop.id.isNotBlank() && stop.name.isNotBlank()) names.putIfAbsent(stop.id, cleanStopName(stop.name))
        }
        // A stop's own lines, then its interchange's: a line's mode is its stop's when that stop
        // has exactly one mode (a tube station), blank for a mixed-mode hub (see Connections).
        val byId = (stations + stopPointSequences.flatMap { it.stopPoint }).associateBy { it.id }
        val lines = HashMap<String, List<LineRef>>()
        for (stop in stopPointSequences.flatMap { it.stopPoint }) {
            if (stop.id.isBlank() || stop.id in lines) continue
            val hub = byId[stop.topMostParentId]?.takeIf { it.id != stop.id }
            lines[stop.id] = stop.lineRefs() + hub?.lineRefs().orEmpty()
        }
        val positions = HashMap<String, Pair<Double, Double>>()
        for (stop in stopPointSequences.flatMap { it.stopPoint } + stations) {
            val lat = stop.lat ?: continue
            val lon = stop.lon ?: continue
            if (stop.id.isNotBlank()) positions.putIfAbsent(stop.id, lat to lon)
        }
        val areas = HashMap<String, String>()
        for (stop in stopPointSequences.flatMap { it.stopPoint }) {
            if (stop.id.isNotBlank() && stop.stationId.isNotBlank()) areas.putIfAbsent(stop.id, stop.stationId)
        }
        val hubs = HashMap<String, String>()
        for (stop in stopPointSequences.flatMap { it.stopPoint }) {
            val hub = stop.topMostParentId
            if (stop.id.isNotBlank() && hub.isNotBlank() && hub != stop.id) hubs.putIfAbsent(stop.id, hub)
        }
        return LineSequence(
            routes = orderedLineRoutes.filter { it.naptanIds.size >= 2 }.map { LineRoute(it.name, it.naptanIds, routeDirection) },
            stopNames = names,
            stopLines = lines,
            stopPositions = positions,
            stopAreas = areas,
            stopHubs = hubs,
        )
    }
}

@Serializable
data class TflOrderedRouteDto(val name: String = "", val naptanIds: List<String> = emptyList())

@Serializable
data class TflStopPointSequenceDto(val stopPoint: List<TflMatchedStopDto> = emptyList())

@Serializable
data class TflMatchedStopDto(
    val id: String = "",
    val name: String = "",
    val topMostParentId: String = "",
    // The stop's stop area (a bus stop's `490G…` group, often holding the pole across the road too),
    // so a starred bus journey finds its way-back stop (SPEC *Journeys*).
    val stationId: String = "",
    val modes: List<String> = emptyList(),
    val lines: List<TflLineIdentifierDto> = emptyList(),
    // The stop's published position — lets a starred journey pick its nearer end (SPEC *Journeys*).
    val lat: Double? = null,
    val lon: Double? = null,
) {
    fun lineRefs(): List<LineRef> {
        val mode = modes.singleOrNull().orEmpty()
        return lines.filter { it.id.isNotBlank() }.map { LineRef(it.id, it.name.ifBlank { it.id }, mode) }
    }
}

@Serializable
data class TflLineIdentifierDto(val id: String = "", val name: String = "")
