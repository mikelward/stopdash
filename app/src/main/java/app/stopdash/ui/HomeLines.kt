package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.TripLeg
import java.time.Instant

/**
 * The home screen's disruptions row (maintainer, 2026-10-05): the trip's one-row summary ([TripRow])
 * over the lines a rider near here may take: every tube line, and each line with a departure (or the
 * list's status row for one with an alert and none) from a stop within the walking reach ([NEARBY_METERS], the list's eager radius). A tap opens the same lines
 * page as a trip's ([TripLinesPage]).
 */
object HomeLines {
    /** The tube's lines, by TfL id and name, in TfL's order. */
    val TUBE: List<LineRef> = listOf(
        "bakerloo" to "Bakerloo",
        "central" to "Central",
        "circle" to "Circle",
        "district" to "District",
        "hammersmith-city" to "Hammersmith & City",
        "jubilee" to "Jubilee",
        "metropolitan" to "Metropolitan",
        "northern" to "Northern",
        "piccadilly" to "Piccadilly",
        "victoria" to "Victoria",
        "waterloo-city" to "Waterloo & City",
    ).map { (id, name) -> LineRef(id, name, TUBE_MODE) }

    val TUBE_IDS: Set<String> = TUBE.mapTo(LinkedHashSet()) { it.id }

    /** How near a stop counts: the near-me list's walking reach (maintainer, 2026-10-05). */
    const val NEARBY_METERS: Int = NearbySelection.EAGER_RADIUS_METERS

    /**
     * The tube's lines as the list's last check found them: the [statuses] TfL gave (good or not; a line
     * missing went undetermined), as of [at], the oldest verdict's stamp (by the steady clock), for a
     * check asked at [asked].
     */
    class Tube(val statuses: Map<String, LineStatus>, val at: Instant, val asked: Instant = at)

    /**
     * The row for the list as it stands ([loaded], null while none is), the stops' distances ([distances],
     * empty for the watched list, whose every stop counts), the tube's check ([tube]), and what the rider
     * [dismissed]. A line both lists is judged by the list's own check where it has one. Walks every
     * stop's departures: on a worker only.
     */
    @WorkerThread
    internal fun row(
        loaded: DeparturesUiState.Loaded?,
        distances: Map<String, Double>,
        tube: Tube?,
        dismissed: Set<DismissedAlert>,
        now: Instant,
    ): TripRow {
        val refs = LinkedHashMap<String, LineRef>()
        TUBE.forEach { refs[it.id] = it }
        val near = loaded?.stops.orEmpty().filter { stop ->
            distances.isEmpty() || distances[stop.stopId]?.let { it <= NEARBY_METERS } == true
        }
        // A line with a departure; a declared one only with an alert, the status row the list shows for a
        // line with none (a suspended one), never every route a stop could serve (Codex, #577).
        val alerts = loaded?.lineStatuses.orEmpty()
        for (stop in near) {
            stop.departures.forEach { if (it.lineId.isNotBlank()) refs.putIfAbsent(it.lineId, LineRef(it.lineId, it.lineName, it.mode)) }
            stop.lines.forEach { if (it.id.isNotBlank() && alerts[it.id]?.hasAlerts == true) refs.putIfAbsent(it.id, it) }
        }
        val listChecked = loaded?.determinedLineIds.orEmpty()
        val tubeCurrent = tube?.takeIf { checkCurrent(it.at, now) }
        // Each line's status as checked (good or not), and whether it's checked at all.
        val raw = HashMap<String, LineStatus>()
        val known = HashSet<String>()
        val checking = HashSet<String>()
        for (id in refs.keys) {
            when {
                id in listChecked -> {
                    known += id
                    loaded?.lineStatuses?.get(id)?.let { raw[id] = it }
                }
                tubeCurrent != null && id in tubeCurrent.statuses -> {
                    known += id
                    tubeCurrent.statuses.getValue(id).takeIf { it.hasAlerts }?.let { raw[id] = it }
                }
                // Still being asked: a cold load's line not back yet, or the tube before its first check.
                loaded == null || (loaded.statusPending && !loaded.checkFailed && (id in loaded.pendingLineIds || id in TUBE_IDS && tube == null)) ->
                    checking += id
            }
        }
        val shown = shownStatuses(raw, dismissed)
        val every = refs.values.map { ref ->
            val id = ref.id
            val was = raw[id]
            TripLine(
                leg = pillNamed(TripLeg(ref.mode, id, ref.name, "", "", "", "", Instant.EPOCH, Instant.EPOCH)),
                status = shown[id] ?: was,
                dismissed = was?.disrupted == true && shown[id]?.disrupted != true,
                checking = id in checking,
                unknown = id !in known && id !in checking,
            )
        }.sortedWith(tripLineOrder)
        val disrupted = every.filter { it.disrupted && !it.dismissed }.map { it.leg }
        val unknown = every.filter { it.unknown }.map { it.leg }
        return TripRow(
            checking = checking.isNotEmpty(),
            lines = disrupted,
            unknown = unknown.isNotEmpty(),
            unknownLines = unknown,
            every = every,
        )
    }

    private const val TUBE_MODE = "tube"
}
