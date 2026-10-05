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
 * over the lines a rider near here may take: every line of the networks they chose ([Network], the tube
 * by default), and each line with a departure (or the
 * list's status row for one with an alert and none) from a stop within the walking reach ([NEARBY_METERS], the list's eager radius). A tap opens the same lines
 * page as a trip's ([TripLinesPage]).
 */
object HomeLines {
    /**
     * The networks the row can always cover, whatever's near (maintainer, 2026-10-05: a modes setting,
     * the tube on by default): each with its lines, by TfL id and name, in TfL's order. [key] is how a
     * choice is stored.
     */
    enum class Network(val key: String, val lines: List<LineRef>) {
        TUBE(
            "tube",
            listOf(
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
            ).map { (id, name) -> LineRef(id, name, "tube") },
        ),
        OVERGROUND(
            "overground",
            listOf("liberty" to "Liberty", "lioness" to "Lioness", "mildmay" to "Mildmay", "suffragette" to "Suffragette", "weaver" to "Weaver", "windrush" to "Windrush")
                .map { (id, name) -> LineRef(id, name, "overground") },
        ),
        ELIZABETH("elizabeth", listOf(LineRef("elizabeth", "Elizabeth line", "elizabeth-line"))),
        DLR("dlr", listOf(LineRef("dlr", "DLR", "dlr"))),
        TRAM("tram", listOf(LineRef("tram", "Tram", "tram"))),
        ;

        companion object {
            /** The networks [keys] name, in the row's order; a key this build doesn't know is left out. */
            fun of(keys: Set<String>): Set<Network> = entries.filterTo(LinkedHashSet()) { it.key in keys }
        }
    }

    /**
     * What the row covers until the rider chooses: every network (maintainer, 2026-10-05). Their 20 line ids
     * ride the list's own line-status request, so this costs no request of its own unless the nearby
     * lines push it past one batch ([app.stopdash.domain.LineStatusBatch]).
     */
    val DEFAULT_NETWORKS: Set<String> = Network.entries.mapTo(LinkedHashSet()) { it.key }

    /** The tube's lines. */
    val TUBE: List<LineRef> = Network.TUBE.lines

    val TUBE_IDS: Set<String> = TUBE.mapTo(LinkedHashSet()) { it.id }

    /** Every line of the [networks] (by key), in the row's order. Walks every network: on a worker only. */
    @WorkerThread
    fun linesOf(networks: Set<String>): List<LineRef> = Network.of(networks).flatMap { it.lines }

    /** Their ids, for the list's status request to ask about. On a worker only. */
    @WorkerThread
    fun idsOf(networks: Set<String>): Set<String> = linesOf(networks).mapTo(LinkedHashSet()) { it.id }

    /** How near a stop counts: the near-me list's walking reach (maintainer, 2026-10-05). */
    const val NEARBY_METERS: Int = NearbySelection.EAGER_RADIUS_METERS

    /**
     * The always-covered lines as the list's last check found them: the [statuses] TfL gave (good or not;
     * a line missing went undetermined) for the lines it [askedFor], as of [at], the oldest verdict's
     * stamp (by the steady clock), for a check asked at [asked]. Each line's own verdict is stamped in
     * [stamps], [at] standing in for one missing: a line's check stands on its own age, so dropping a
     * network whose verdict had aged never takes a current one down with it (Codex, #592).
     */
    class Always(
        val statuses: Map<String, LineStatus>,
        val at: Instant,
        val asked: Instant = at,
        val askedFor: Set<String> = statuses.keys,
        val stamps: Map<String, Instant> = emptyMap(),
    ) {
        /** Whether [id]'s verdict is in and still current at [now]. */
        fun current(id: String, now: Instant): Boolean = id in statuses && checkCurrent(stamps[id] ?: at, now)
    }

    /**
     * The row for the list as it stands ([loaded], null while none is), the stops' distances ([distances],
     * empty for the watched list, whose every stop counts), the [networks] the rider chose to cover (by key) and
     * their check ([always]), and what the rider [dismissed]. A line both lists is judged by the list's
     * own check where it has one. Walks every stop's departures: on a worker only.
     */
    @WorkerThread
    internal fun row(
        loaded: DeparturesUiState.Loaded?,
        distances: Map<String, Double>,
        always: Always?,
        dismissed: Set<DismissedAlert>,
        now: Instant,
        // A refresh under way, whose check of the always-covered lines may yet answer.
        refreshing: Boolean = false,
        networks: Set<String> = DEFAULT_NETWORKS,
    ): TripRow {
        val alwaysLines = linesOf(networks)
        val refs = LinkedHashMap<String, LineRef>()
        alwaysLines.forEach { refs[it.id] = it }
        val alwaysIds = alwaysLines.mapTo(HashSet()) { it.id }
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
        // What the list couldn't check, said here in place of its banner (maintainer, 2026-10-05): its line
        // whose check didn't answer, a farther stop's too, named among the unchecked; a stop whose closure
        // check failed, named on the page; and anything else (a departure with no line to check) as the
        // row's "Unknown" alone, never left unsaid.
        val listUnknown = loaded != null && loaded.disruptionUnknown && !loaded.checkingDisruptions
        if (listUnknown) {
            for (stop in loaded.stops) {
                stop.departures.forEach {
                    if (it.lineId.isNotBlank() && it.lineId !in listChecked) refs.putIfAbsent(it.lineId, LineRef(it.lineId, it.lineName, it.mode))
                }
            }
        }
        val unknownStops = if (listUnknown) {
            loaded.stops.filter { it.stopId in loaded.stopsDisruptionUnknown }.map { it.stopName }.distinct().joinToString(", ")
        } else {
            ""
        }
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
                always != null && always.current(id, now) -> {
                    known += id
                    always.statuses.getValue(id).takeIf { it.hasAlerts }?.let { raw[id] = it }
                }
                // Still being asked: a cold load's line not back yet, an always-covered line before its
                // first check, or while a refresh is under way, its last check too old to stand (back from
                // the background, say), or one just chosen that no check has asked about yet: "Checking…",
                // never "couldn't check" for a check not yet asked (maintainer, 2026-10-05).
                loaded == null || (loaded.statusPending && !loaded.checkFailed && (id in loaded.pendingLineIds || id in alwaysIds && always == null)) ||
                    // No check published at all (a refresh with no line to ask about) is one not asked yet either (Codex, #592).
                    (id in alwaysIds && (refreshing || always == null || id !in always.askedFor)) ->
                    checking += id
            }
        }
        val shown = shownStatuses(raw, dismissed)
        // Dismissed every way it's disrupted: on the list a rider dismisses a row's own way's alert, which
        // the line-wide status needn't match, so that alone left the line on the row (maintainer,
        // 2026-10-05: what's dismissed stays off it).
        fun everyWayDismissed(status: LineStatus) = status.byDirection.values.filter { it.disrupted }
            .let { ways -> ways.isNotEmpty() && ways.all { DismissedAlert.ofLineStatus(it) in dismissed } }
        val every = refs.values.map { ref ->
            val id = ref.id
            val was = raw[id]
            val dismissedHere = was?.disrupted == true && (shown[id]?.disrupted != true || everyWayDismissed(was))
            TripLine(
                leg = pillNamed(TripLeg(ref.mode, id, ref.name, "", "", "", "", Instant.EPOCH, Instant.EPOCH)),
                // A dismissed line names the alert it dismissed ("Diversions · dismissed"), never what's left
                // once it's gone, which with only planned work left read "Good service" (maintainer,
                // 2026-10-05).
                status = if (dismissedHere) was else shown[id] ?: was,
                dismissed = dismissedHere,
                checking = id in checking,
                unknown = id !in known && id !in checking,
            )
        }.sortedWith(tripLineOrder)
        val disrupted = every.filter { it.disrupted && !it.dismissed }.map { it.leg }
        val unknown = every.filter { it.unknown }.map { it.leg }
        return TripRow(
            checking = checking.isNotEmpty(),
            lines = disrupted,
            unknown = unknown.isNotEmpty() || listUnknown,
            unknownLines = unknown,
            unknownStops = unknownStops,
            every = every,
        )
    }

}
