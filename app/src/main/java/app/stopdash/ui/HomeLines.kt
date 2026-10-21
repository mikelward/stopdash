package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.Color
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.NearestByLine
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.riderStopIds
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The home screen's disruptions row (maintainer, 2026-10-05): the trip's one-row summary ([TripRow])
 * over the lines the rider watches: every line the home screen shows (maintainer, 2026-10-17: its rows'
 * and its farther cards', [ScreenLines]) unless they turned that off (2026-10-21), their favorites' lines
 * (a journey's wherever it is, unless they turned that off too, and a starred row's in the list), the
 * trip's on the way, and every line they chose ([Network], none by default). A tap opens the same lines
 * page as a trip's ([TripLinesPage]), which lists every other rail line too, under *Other* ([RAIL]).
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
        ELIZABETH("elizabeth", listOf(LineRef("elizabeth", "Elizabeth", "elizabeth-line"))),
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
     * The Disruptions summary page's chips, under their headers: each under the mode group the near-me
     * list hides it by (maintainer, 2026-10-08): the DLR and the Elizabeth line under Underground with
     * the Tube. Fixed, and built from the networks' own lists without walking them, so the
     * page draws every chip where it will stay on its first frame; only which ones show selected is
     * worked out ([covered]).
     */
    val PICKER: List<Pair<ModeGroups.Group, List<LineRef>>> = listOf(
        ModeGroups.of("tube") to Network.TUBE.lines + Network.DLR.lines + Network.ELIZABETH.lines,
        ModeGroups.of("overground") to Network.OVERGROUND.lines,
        ModeGroups.of("tram") to Network.TRAM.lines,
    )

    /**
     * Every rail line (maintainer, 2026-10-17): the tube's, the DLR, the Elizabeth line, the Overground's and
     * the tram, in [PICKER]'s order. The lines page lists each the rider doesn't watch under *Other*, asked
     * about only while the page is open ([MainViewModel.linesPage]).
     */
    val RAIL: List<LineRef> = PICKER.flatMap { it.second }

    /** [RAIL]'s ids. */
    val RAIL_IDS: Set<String> = RAIL.mapTo(LinkedHashSet()) { it.id }

    // Each rail line's place in [RAIL], which orders *Other*'s lines as bad as each other as the chips are.
    private val RAIL_ORDER: Map<String, Int> = RAIL.withIndex().associate { (i, line) -> line.id to i }

    /** Each [PICKER] chip's network, by line id, for a choice an older build stored by network key. */
    private val NETWORK_KEYS: Map<String, String> = Network.entries.flatMap { network -> network.lines.map { it.id to network.key } }.toMap()

    /**
     * Whether [chosen] watches the line [lineId], by its own id or its network's key, for one line's long press
     * on the lines page ([LineWatch]); null for a line no network has (a bus), which can't be watched so. A
     * lookup, no walk: fine on the main thread.
     */
    fun watching(chosen: Set<String>, lineId: String): Boolean? {
        val network = NETWORK_KEYS[lineId] ?: return null
        return lineId in chosen || network in chosen
    }

    /**
     * Which of [PICKER]'s chips show selected for what's [chosen], group by group in its order: a line
     * covered by its network's key (an older build's choice) or picked on its own (maintainer,
     * 2026-10-06). No chip picks a whole network (2026-10-07: the row leans on the lines near the rider
     * and their favorites'). On a worker only (AGENTS.md *Main thread: read and dispatch only*).
     */
    @WorkerThread
    fun covered(chosen: Set<String>): List<List<Boolean>> = PICKER.map { (_, lines) ->
        lines.map { line -> line.id in chosen || NETWORK_KEYS[line.id] in chosen }
    }

    /**
     * Each [PICKER] chip's colors on [surface], in its order, as its line's pill takes them ([pillColors]), for a
     * chip turned on. Worked out with [covered], so the picker's first frame does none of the contrast work
     * (Codex, #923). On a worker only.
     */
    @WorkerThread
    fun pickerPills(surface: Color): List<List<PillColors>> = PICKER.map { (_, lines) ->
        lines.map { line -> pillColors(line.name, line.id, line.mode, surface) }
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
     * The lines the trip on the way still rides ([RouteDisruption.comingLineRefs]), for the row to cover as the
     * rider's own: [unread] while the kept trip can't be read, so the row says it couldn't check rather than
     * leaving them unsaid.
     */
    data class TripLines(val lines: List<LineRef>, val unread: Boolean = false) {
        companion object {
            /** No trip on the way. */
            val NONE = TripLines(emptyList())
        }
    }

    /**
     * The [trip] on the way's lines as it goes (maintainer, 2026-10-17), by how far the kept trip has been
     * [read]: null until it has, so "not read yet" is never taken for no trip (Codex, #920); [TripLines.unread]
     * while it can't be; then the trip's, none with no trip, or once it has arrived ([progress]): one kept only
     * because it couldn't be forgotten yet has no line left to ride (Codex, #920). Walked on [compute], never
     * the collector's thread.
     */
    fun tripLines(
        trip: Flow<ActiveTrip?>,
        progress: Flow<TripProgress?>,
        read: Flow<ActiveTripTracker.KeptTrip>,
        compute: CoroutineDispatcher,
    ): Flow<TripLines?> =
        combine(trip, progress, read) { now, step, kept ->
            when (kept) {
                ActiveTripTracker.KeptTrip.UNREAD -> null
                ActiveTripTracker.KeptTrip.UNREADABLE -> TripLines(emptyList(), unread = true)
                ActiveTripTracker.KeptTrip.READ ->
                    now?.takeIf { step != TripProgress.Arrived }?.let { TripLines(RouteDisruption.comingLineRefs(it)) } ?: TripLines.NONE
            }
        }
            .distinctUntilChanged()
            .flowOn(compute)

    /**
     * The rider's own stops ([riderStopIds]) as the [starred] rows and favorite [journeys] change, for a lines
     * page the home screen doesn't build (a trip's) to keep them on a line's map: walked on [compute], never
     * the collector's thread. A set this build can't read, or journeys not yet read, count as none.
     */
    fun riderStops(starred: Flow<StarredRowSet>, journeys: Flow<List<FavoriteJourney>?>, compute: CoroutineDispatcher): Flow<Set<String>> =
        combine(starred, journeys) { set, list -> riderStopIds((set as? StarredRowSet.Loaded)?.starred.orEmpty(), list.orEmpty()) }
            .distinctUntilChanged()
            .flowOn(compute)

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

    /**
     * The lines the home screen shows (maintainer, 2026-10-17: what it shows is what the rider watches): its
     * rows' and its farther cards', each once, and how far each card is ([meters], by line, the nearest card's).
     */
    class ScreenLines(
        val lines: List<LineRef>,
        val meters: Map<String, Double> = emptyMap(),
        // The lines only a farther card shows, whose stop isn't fetched, so the list's own check never asks about
        // them: asked about with the always-covered lines ([MainViewModel.screenLines]).
        val farIds: Set<String> = emptySet(),
        // Whether a drawn row has no line to check: the row's "Unknown" says so, not for a hidden row's.
        val lineless: Boolean = false,
    ) {
        /**
         * Whether [loaded] left something these lines show unchecked, as [row]'s "Unknown" says it while it
         * watches them: a drawn line with no verdict, a stop whose closure check failed, a row with no line.
         * Never a line a stop only declares or a hidden mode's, which nothing on the screen shows (Codex,
         * #930): the list's banner goes by this while the row watches only the rider's own lines
         * (maintainer, 2026-10-21; a declared Eurostar TfL gives no status for kept it up for good).
         */
        @WorkerThread
        fun uncheckedIn(loaded: DeparturesUiState.Loaded): Boolean =
            loaded.disruptionUnknown && !loaded.checkingDisruptions &&
                (loaded.stopsDisruptionUnknown.isNotEmpty() || lineless ||
                    lines.any { it.id !in farIds && it.id !in loaded.determinedLineIds })


        companion object {
            /**
             * From the [rows] the list draws, its journey cards' [cardRows] and its [farther] cards: a line with a
             * departure or a status row, and every line a card names while it shows them, collapsed. An opened card
             * shows its departures as the list's own rows instead (Codex, #930), so its lines are those rows';
             * one opened with nothing to list still stands as its card. On a worker only.
             */
            @WorkerThread
            fun of(rows: List<DepartureRow>, cardRows: List<DepartureRow>, farther: List<FartherCard>): ScreenLines {
                val lines = LinkedHashMap<String, LineRef>()
                var lineless = false
                for (row in rows + cardRows) {
                    // A stop's own notice (a closure, a lift) is no departure: it has no line to check, nor
                    // needs one, so it never counts as a row with no line (maintainer, 2026-10-21).
                    if (row.lineId.isNotBlank()) {
                        lines.putIfAbsent(row.lineId, LineRef(row.lineId, row.lineName, row.mode))
                    } else if (row.stopDisruption == null) {
                        lineless = true
                    }
                }
                val listed = lines.keys.toSet()
                val drawnStops = (rows + cardRows).mapTo(HashSet()) { it.stopId }
                val meters = HashMap<String, Double>()
                for (card in farther) {
                    val open = card.load as? FartherLoad.Open
                    if (open != null && open.stops.any { it.id in drawnStops }) continue
                    card.place.lines.forEach { line ->
                        if (line.id.isBlank()) return@forEach
                        lines.putIfAbsent(line.id, line)
                        if (card.place.meters < (meters[line.id] ?: Double.MAX_VALUE)) meters[line.id] = card.place.meters
                    }
                }
                return ScreenLines(lines.values.toList(), meters, lines.keys - listed, lineless)
            }
        }
    }

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
     * empty for the watched list), the [networks] the rider chose to cover (by key) and the check of every line
     * the list doesn't check itself ([always]), and what the rider [dismissed]. A line both lists is judged by
     * the list's own check where it has one. The lines the screen shows ([screen]) are watched, wherever they
     * are (maintainer, 2026-10-17), unless the rider turned that off ([watchNearby]); with none given, every
     * stop's are. The favorite rides' lines are watched wherever they run, unless turned off too ([watchRides]). Walks every stop's departures: on a worker only.
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
        // How far each line's nearest stop is, both tiers of the near-me lookup however far, a stop whose
        // times aren't fetched included ([NearbyStopsViewModel.State.Ready.nearestMetersByLine]).
        lineMeters: Map<String, Double> = emptyMap(),
        // The lines the trip on the way still rides ([RouteDisruption.comingLineRefs]), wherever they run:
        // asked about with the always-covered lines and ranked as the rider's own (maintainer, 2026-10-17).
        tripLines: List<LineRef> = emptyList(),
        // The kept trip can't be read ([TripLines.unread]): its lines can't be named, so the row says "Unknown".
        tripUnread: Boolean = false,
        // The lines the home screen shows ([ScreenLines]), every one watched; null for every stop's in the list.
        screen: ScreenLines? = null,
        // Whether the lines the screen shows are watched at all (maintainer, 2026-10-21): off, only the rider's
        // own and the chosen lines are, and what the list couldn't check is the list's banner's to say.
        watchNearby: Boolean = true,
        // Whether the favorite rides' lines are watched wherever they run (maintainer, 2026-10-21); off, a ride's
        // line is watched only as any other is, for being on the screen or chosen.
        watchRides: Boolean = true,
    ): TripRow {
        val rideLines = if (watchRides) journeyLines else emptySet()
        val rides = if (watchRides) journeys else emptyList()
        val alwaysLines = linesOf(networks)
        val refs = LinkedHashMap<String, LineRef>()
        alwaysLines.forEach { refs[it.id] = it }
        // The lines on the screen, an always-covered one among them: watched wherever they are (maintainer,
        // 2026-10-17), and ranked ahead of the rest within a severity.
        val nearby = HashSet<String>()
        if (!watchNearby) {
            // None: the rider watches only their own lines and the ones they chose.
        } else if (screen != null) {
            screen.lines.forEach {
                refs.putIfAbsent(it.id, it)
                nearby += it.id
            }
        } else {
            // A line with a departure; a declared one only with an alert, the status row the list shows for a
            // line with none (a suspended one), never every route a stop could serve (Codex, #577).
            val alerts = loaded?.lineStatuses.orEmpty()
            for (stop in loaded?.stops.orEmpty()) {
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
                return star || id in rideLines
            }
            stop.departures.forEach { if (wanted(it.lineId)) refs.putIfAbsent(it.lineId, LineRef(it.lineId, it.lineName, it.mode)) }
            stop.lines.forEach { if (wanted(it.id)) refs.putIfAbsent(it.id, it) }
        }
        rides.forEach { if (it.lineId.isNotBlank()) refs.putIfAbsent(it.lineId, it.line) }
        tripLines.forEach { refs.putIfAbsent(it.id, it) }
        // Asked about with the always-covered lines: a farther card's line too, whose stop isn't fetched.
        val alwaysIds = alwaysLines.mapTo(HashSet()) { it.id }.apply {
            rides.forEach { if (it.lineId.isNotBlank()) add(it.lineId) }
            tripLines.forEach { add(it.id) }
            if (watchNearby) screen?.farIds?.let { addAll(it) }
        }
        // Each line's stop nearest the rider within reach, which its map keeps on the page.
        val nearest = NearestByLine.merged(loaded?.stops.orEmpty(), nearestStops, distances)
        val listChecked = loaded?.determinedLineIds.orEmpty()
        // What the list couldn't check, said here in place of its banner (maintainer, 2026-10-05): its line
        // whose check didn't answer, a farther stop's too, named among the unchecked (every line it shows is
        // watched); a stop whose closure check failed, named on the page; and anything else (a departure with
        // no line to check) as the row's "Unknown" alone, never left unsaid. With the screen's lines, only what
        // the screen shows: a hidden mode's line the list couldn't check never makes the card say so (Codex, #930).
        val listUnknown = watchNearby && loaded != null && loaded.disruptionUnknown && !loaded.checkingDisruptions &&
            (screen == null || loaded.stopsDisruptionUnknown.isNotEmpty() || screen.lineless)
        val unknownStops = if (listUnknown) {
            loaded.stops.filter { it.stopId in loaded.stopsDisruptionUnknown }.map { it.stopName }.distinct().joinToString(", ")
        } else {
            ""
        }
        // Every other rail line, listed under *Other* (maintainer, 2026-10-17) and asked about only while the
        // lines page is open: never a pill, nor the card's "Checking…" or "Unknown".
        val otherIds = HashSet<String>()
        for (line in RAIL) {
            if (line.id !in refs) {
                refs[line.id] = line
                otherIds += line.id
            }
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
                // Asked about once the page opens, so "Checking…" until a check that asked about it is in.
                id in otherIds -> if (always == null || refreshing || id !in always.askedFor) checking += id
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
        // The lines that matter most to the rider: near them, a favorite's (a starred row's, a journey's) or
        // the trip's on the way. A favorite place's no longer count (maintainer, 2026-10-17: at home, the
        // rides to work matter, not everything near work).
        val mine = HashSet<String>(nearby)
        mine += starredHere
        mine += rideLines
        tripLines.forEach { mine += it.id }
        // Each line's nearest stop, however far, the list's or the near-me lookup's (Codex, #710): as bad and
        // as much the rider's own, the nearer goes first.
        val closest = HashMap<String, Double>(lineMeters)
        screen?.meters?.forEach { (id, d) -> if (d < (closest[id] ?: Double.MAX_VALUE)) closest[id] = d }
        for (stop in loaded?.stops.orEmpty()) {
            val d = distances[stop.stopId] ?: continue
            fun note(id: String) {
                if (id.isNotBlank() && d < (closest[id] ?: Double.MAX_VALUE)) closest[id] = d
            }
            stop.departures.forEach { note(it.lineId) }
            stop.lines.forEach { note(it.id) }
        }
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
                planned = plannedShown(id, was ?: status, dismissed),
                // Watched (maintainer, 2026-10-17): the lines on the screen, the rider's own and every line they
                // chose to watch, which is every line but *Other*'s.
                watched = id !in otherIds,
            )
        }
        // The pills' order, and *Watched*'s on the page: worst first as a trip's orders them, then, as bad as
        // each other, the rider's own lines ahead of a network's far away (maintainer, 2026-10-05), the nearer
        // first, then by name.
        val watched = every.filter { it.watched == true }.sortedWith(
            tripLineOrder.thenBy<TripLine> { if (it.leg.lineId in mine) 0 else 1 }
                .thenBy { closest[it.leg.lineId] ?: Double.MAX_VALUE }
                .then(byName),
        )
        // *Other*'s: worst first too, then as Settings' chips go (maintainer, 2026-10-17).
        val other = every.filter { it.watched != true }.sortedWith(
            tripLineOrder.thenBy<TripLine> { RAIL_ORDER[it.leg.lineId] ?: Int.MAX_VALUE }.then(byName),
        )
        // The pills: in that order, worst first, the rider's own ahead of the rest when as bad, so "+N"
        // takes the mildest first (maintainer, 2026-10-07: a far suspension outranks a near minor delay); a
        // dismissed one is never a pill.
        val disrupted = watched.filter { it.disrupted && !it.dismissed }.map { it.leg }
        val unknown = watched.filter { it.unknown }.map { it.leg }
        // The page in two sections (maintainer, 2026-10-17): the watched lines under *Watched*, then every
        // other rail line under *Other*.
        val sectioned = watched + other
        return TripRow(
            checking = (checking - otherIds).isNotEmpty(),
            lines = disrupted,
            unknown = unknown.isNotEmpty() || listUnknown || tripUnread,
            unknownLines = unknown,
            unknownStops = unknownStops,
            every = sectioned,
            starredStops = riderStopIds(starred, journeys),
        )
    }

}
