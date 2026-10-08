package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.NearestByLine
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import app.stopdash.domain.StationIndex
import app.stopdash.domain.TripLeg
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.riderStopIds
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The home screen's disruptions row (maintainer, 2026-10-05): the trip's one-row summary ([TripRow])
 * over the lines a rider near here may take: every line of the networks they chose ([Network], none
 * by default), their favorites' lines (a journey's wherever it is, a starred row's in the list), and each line with a departure (or the
 * list's status row for one with an alert and none) from a stop within the walking reach ([NEARBY_METERS], the list's eager radius). A tap opens the same lines
 * page as a trip's ([TripLinesPage]).
 */
object HomeLines {
    /**
     * The networks the row can always cover, whatever's near (maintainer, 2026-10-05: a modes setting;
     * none on by default since 2026-10-06): each with its lines, by TfL id and name, in TfL's order. [key] is how a
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
     * What the row covers until the rider chooses: no network (maintainer, 2026-10-06), so the row is the
     * rider's own lines (near them, and their favorites'), a whole network only by choice. A chosen
     * network's line ids ride the list's own line-status request, so it costs no request of its own unless
     * the nearby lines push it past one batch ([app.stopdash.domain.LineStatusBatch]).
     */
    val DEFAULT_NETWORKS: Set<String> = emptySet()

    /** The tube's lines. */
    val TUBE: List<LineRef> = Network.TUBE.lines

    val TUBE_IDS: Set<String> = TUBE.mapTo(LinkedHashSet()) { it.id }

    /**
     * Every line the [chosen] cover, in the row's order: a network's every line by its key, and a line
     * picked on its own by its id (maintainer, 2026-10-06). One setting holds both: the single-line
     * networks' keys are their lines' ids, and a build that doesn't know line picks reads only the
     * network keys. Walks every network: on a worker only.
     */
    @WorkerThread
    fun linesOf(chosen: Set<String>): List<LineRef> =
        Network.entries.flatMap { network -> if (network.key in chosen) network.lines else network.lines.filter { it.id in chosen } }

    /**
     * The Disruptions summary page's chips, under their headers (maintainer, 2026-10-07): the Tube's and
     * the Overground's lines under the network's name, then the single-line networks' under "Other"
     * (a null network). Fixed, and built from the networks' own lists without walking them, so the page
     * draws every chip where it will stay on its first frame; only which ones show selected is worked out
     * ([covered]).
     */
    val PICKER: List<Pair<Network?, List<LineRef>>> = listOf(
        Network.TUBE to Network.TUBE.lines,
        Network.OVERGROUND to Network.OVERGROUND.lines,
        null to listOf(Network.ELIZABETH.lines[0], Network.DLR.lines[0], Network.TRAM.lines[0]),
    )

    /**
     * Which of [PICKER]'s chips show selected for what's [chosen], group by group in its order: a line
     * covered by its network's key (an older build's choice) or picked on its own (maintainer,
     * 2026-10-06). No chip picks a whole network (2026-10-07: the row leans on the lines near the rider
     * and their favorites'). On a worker only (AGENTS.md *Main thread: read and dispatch only*).
     */
    @WorkerThread
    fun covered(chosen: Set<String>): List<List<Boolean>> = PICKER.map { (network, lines) ->
        // A single-line network's key is its line's id, so "Other" needs only the ids.
        lines.map { line -> line.id in chosen || (network != null && network.key in chosen) }
    }

    /**
     * [chosen] with the line [lineId] turned the other way: off if covered, by its network's key or on its
     * own, else on. Off under its network leaves the network's other lines picked one by one, its own id
     * dropped too, should the choice hold the network and it both (Codex, #642). An id no network has is
     * left as it was. On a worker only.
     */
    @WorkerThread
    fun toggle(chosen: Set<String>, lineId: String): Set<String> {
        val network = Network.entries.firstOrNull { network -> network.lines.any { it.id == lineId } } ?: return chosen
        val ids = network.lines.map { it.id }
        return when {
            network.key in chosen -> chosen - network.key - ids.toSet() + (ids - lineId)
            lineId in chosen -> chosen - lineId
            else -> chosen + lineId
        }
    }

    /**
     * A change of the chosen lines from [before] to [after] as usage events
     * ([UsageEvent.SettingChanged.disruptionLines]), by the lines each covers ([linesOf]): a tap that turns an
     * older build's network key into its other lines one by one ([toggle]) is the one line taken off. On a
     * worker only.
     */
    @WorkerThread
    fun lineChanges(before: Set<String>, after: Set<String>): List<UsageEvent.SettingChanged> =
        UsageEvent.SettingChanged.disruptionLines(linesOf(before).mapTo(HashSet()) { it.id }, linesOf(after).mapTo(HashSet()) { it.id })

    /**
     * The favorite [journeys]' line ids as they change, for the screen to ask about a journey just starred at
     * once (Codex, #640): walked on [compute], never the collector's thread, which can be the main one
     * (AGENTS.md *Main thread: read and dispatch only*). An unreadable store (null) reads as none.
     */
    fun journeyLineIds(journeys: Flow<List<FavoriteJourney>?>, compute: CoroutineDispatcher): Flow<Set<String>> =
        journeys.map { list -> list.orEmpty().mapNotNullTo(HashSet()) { it.lineId.takeIf(String::isNotBlank) } }.flowOn(compute)

    /**
     * The rider's own stops ([riderStopIds]) as the [starred] rows and favorite [journeys] change, for a lines
     * page the home screen doesn't build (a trip's) to keep them on a line's map: walked on [compute], never
     * the collector's thread. A set this build can't read, or journeys not yet read, count as none.
     */
    fun riderStops(starred: Flow<StarredRowSet>, journeys: Flow<List<FavoriteJourney>?>, compute: CoroutineDispatcher): Flow<Set<String>> =
        combine(starred, journeys) { set, list -> riderStopIds((set as? StarredRowSet.Loaded)?.starred.orEmpty(), list.orEmpty()) }
            .distinctUntilChanged()
            .flowOn(compute)

    /**
     * The lines near the favorite places ([placeLines]): [lines], and [unread] while they can't be named, the
     * places never read (a newer build's file, or a read outage the store retries) or the bundled station index
     * missing, so the row says it couldn't check rather than reading as if there were no places (Codex, #657).
     */
    class PlaceLines(val lines: List<LineRef>, val unread: Boolean = false) {
        companion object {
            /** No favorite places. */
            val NONE = PlaceLines(emptyList())
        }
    }

    /**
     * The lines of every station within walking reach ([NEARBY_METERS]) of a favorite place, as the [places]
     * change, for the row to cover wherever the rider is (maintainer, 2026-10-07): stations only, from the
     * bundled [index], so it costs no request. Worked out on [compute], never the collector's thread. While the
     * places can't be read the last lines stand rather than vanishing, so a place's line is never dropped
     * unasked (Codex, #657); before any were read, none, marked [PlaceLines.unread]. A discarded file means the
     * places are gone: none.
     */
    fun placeLines(
        places: Flow<FavoritePlacesSet>,
        index: () -> StationIndex,
        compute: CoroutineDispatcher,
        warn: (String) -> Unit = {},
    ): Flow<PlaceLines> = flow {
        var last: PlaceLines? = null
        places.collect { set ->
            val lines = when (set) {
                is FavoritePlacesSet.Loaded -> if (set.places.isEmpty()) {
                    PlaceLines.NONE
                } else {
                    val stations = index()
                    // No bundled index to read (missing, corrupt, a newer format: [StationIndex.EMPTY]) names no line
                    // near any place: unread, not "no lines there" (Codex, #657).
                    if (stations.stations.isEmpty()) {
                        warn("disruptions row: no station index, the favorite places' lines can't be named")
                        PlaceLines(emptyList(), unread = true)
                    } else {
                        PlaceLines(stations.linesNear(set.places.map { it.coordinate }, NEARBY_METERS))
                    }
                }
                FavoritePlacesSet.Discarded -> PlaceLines(emptyList())
                FavoritePlacesSet.Unavailable -> {
                    warn("disruptions row: favorite places unavailable, keeping their last lines")
                    last ?: PlaceLines(emptyList(), unread = true)
                }
            }
            last = lines
            emit(lines)
        }
    }.flowOn(compute)

    /** Their ids, for the list's status request to ask about. On a worker only. */
    @WorkerThread
    fun idsOf(networks: Set<String>): Set<String> = linesOf(networks).mapTo(LinkedHashSet()) { it.id }

    /**
     * The last tie-break, after severity and the rider's own (maintainer, 2026-10-07): by name, ignoring
     * case, a run of digits by its number, so the 43 goes before the 134 and both before the Bakerloo. A
     * line with no name goes by its id.
     */
    private val byName: Comparator<TripLine> = Comparator { a, b ->
        compareNatural(a.leg.lineName.ifBlank { a.leg.lineId }, b.leg.lineName.ifBlank { b.leg.lineId })
    }

    // [a] against [b], run by run: digits as numbers (longer runs past leading zeros are bigger), the rest
    // as text ignoring case; then plain text, so the order is total.
    internal fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val ei = a.indexOfFirst(i) { !it.isDigit() }
                val ej = b.indexOfFirst(j) { !it.isDigit() }
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                val byNumber = if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
                if (byNumber != 0) return byNumber
                i = ei
                j = ej
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }

    // The index of the first character from [from] matching [predicate], else the length.
    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        var k = from
        while (k < length && !predicate(this[k])) k++
        return k
    }

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
        // The rider's favorites, whose lines lead the pills with the nearby ones: their starred rows, and
        // every line their favorite journeys ride ([journeyLines]).
        starred: Set<StarredRow> = emptySet(),
        journeyLines: Set<String> = emptySet(),
        // Their favorite journeys, whose ends a line's map keeps on the page with the starred rows' stops.
        journeys: List<FavoriteJourney> = emptyList(),
        // Each line's stop nearest the rider within reach by the nearby stops' own data, both tiers, a
        // stop whose times aren't fetched included ([NearbyStopsViewModel.State.Ready.nearestStopByLine]).
        nearestStops: Map<String, String> = emptyMap(),
        // The lines of the stations near each favorite place (maintainer, 2026-10-07), asked about with the
        // always-covered lines and ranked as the rider's own.
        placeLines: List<LineRef> = emptyList(),
        // The favorite places never read ([PlaceLines.unread]): their lines can't be named, so the row says
        // "Unknown" rather than leaving them unsaid.
        placesUnread: Boolean = false,
    ): TripRow {
        val alwaysLines = linesOf(networks)
        val refs = LinkedHashMap<String, LineRef>()
        alwaysLines.forEach { refs[it.id] = it }
        val near = loaded?.stops.orEmpty().filter { stop ->
            distances.isEmpty() || distances[stop.stopId]?.let { it <= NEARBY_METERS } == true
        }
        // A line with a departure; a declared one only with an alert, the status row the list shows for a
        // line with none (a suspended one), never every route a stop could serve (Codex, #577).
        val alerts = loaded?.lineStatuses.orEmpty()
        // The lines near the rider, an always-covered one among them: ranked ahead of the rest within a severity.
        val nearby = HashSet<String>()
        for (stop in near) {
            stop.departures.forEach {
                if (it.lineId.isNotBlank()) {
                    refs.putIfAbsent(it.lineId, LineRef(it.lineId, it.lineName, it.mode))
                    nearby += it.lineId
                }
            }
            stop.lines.forEach {
                if (it.id.isNotBlank() && alerts[it.id]?.hasAlerts == true) {
                    refs.putIfAbsent(it.id, it)
                    nearby += it.id
                }
            }
        }
        // The rider's favorites' lines (maintainer, 2026-10-06: the row is about the rider's own lines, the
        // networks only by choice). A journey's, wherever its stop is: named as a fetched stop declares it or
        // shows it leaving, else as the journey stored it, and asked about with the always-covered lines so one
        // no fetched stop vouches for is judged too (Codex, #640). A starred row's while its stop is in the list,
        // judged by the list's own check: a star stores no line name or mode to show it by otherwise, and ranks
        // only within the list anyway (Codex, #640).
        // A star is at one stop: its line counts there, not at another fetched stop the line also serves (Codex, #640).
        val starredAt = starred.mapTo(HashSet()) { it.stopId to it.lineId }
        // The starred lines whose own stop is in the list: those alone rank as the rider's own.
        val starredHere = HashSet<String>()
        for (stop in loaded?.stops.orEmpty()) {
            fun wanted(id: String): Boolean {
                if (id.isBlank()) return false
                val star = (stop.stopId to id) in starredAt
                if (star) starredHere += id
                return star || id in journeyLines
            }
            stop.departures.forEach { if (wanted(it.lineId)) refs.putIfAbsent(it.lineId, LineRef(it.lineId, it.lineName, it.mode)) }
            stop.lines.forEach { if (wanted(it.id)) refs.putIfAbsent(it.id, it) }
        }
        journeys.forEach { if (it.lineId.isNotBlank()) refs.putIfAbsent(it.lineId, it.line) }
        placeLines.forEach { refs.putIfAbsent(it.id, it) }
        val alwaysIds = alwaysLines.mapTo(HashSet()) { it.id }.apply {
            journeys.forEach { if (it.lineId.isNotBlank()) add(it.lineId) }
            placeLines.forEach { add(it.id) }
        }
        // Each line's stop nearest the rider within reach, which its map keeps on the page.
        val nearest = NearestByLine.merged(loaded?.stops.orEmpty(), nearestStops, distances)
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
        // The lines that matter most to the rider: near them, or a favorite's.
        val mine = HashSet<String>(nearby)
        mine += starredHere
        mine += journeyLines
        placeLines.forEach { mine += it.id }
        val every = refs.values.map { ref ->
            val id = ref.id
            val was = raw[id]
            val dismissedHere = was?.disrupted == true && (shown[id]?.disrupted != true || everyWayDismissed(was))
            // A dismissed line names the alert it dismissed ("Diversions · dismissed"), never what's left
            // once it's gone, which with only planned work left read "Good service" (maintainer,
            // 2026-10-05).
            val status = if (dismissedHere) was else shown[id] ?: was
            // Its worse alert dismissed while a milder one stands: named beside it, as on a trip's lines
            // page, so its map's closure has words on the page (Codex, #606).
            val quieted = if (dismissedHere) null else quietedBeside(was?.takeIf { DismissedAlert.ofLineStatus(it) in dismissed }, status)
            TripLine(
                leg = pillNamed(TripLeg(ref.mode, id, ref.name, "", "", "", "", Instant.EPOCH, Instant.EPOCH)),
                status = status,
                mapKey = LineMap.alertKey(status, quieted),
                dismissed = dismissedHere,
                quieted = quieted,
                checking = id in checking,
                unknown = id !in known && id !in checking,
                nearby = nearest[id]?.let { setOf(it) }.orEmpty(),
            )
        // The page: worst first as a trip's orders them, then, as bad as each other, the rider's own lines
        // ahead of a network's far away (maintainer, 2026-10-05).
        }.sortedWith(tripLineOrder.thenBy<TripLine> { it.leg.lineId !in mine }.then(byName))
        // The pills: in the page's order, worst first, the rider's own ahead of the rest when as bad, so "+N"
        // takes the mildest first (maintainer, 2026-10-07: a far suspension outranks a near minor delay); a
        // dismissed one is never a pill.
        val disrupted = every.filter { it.disrupted && !it.dismissed }.map { it.leg }
        val unknown = every.filter { it.unknown }.map { it.leg }
        return TripRow(
            checking = checking.isNotEmpty(),
            lines = disrupted,
            unknown = unknown.isNotEmpty() || listUnknown || placesUnread,
            unknownLines = unknown,
            unknownStops = unknownStops,
            every = every,
            starredStops = riderStopIds(starred, journeys),
        )
    }

}
