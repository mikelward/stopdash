package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A line drawn as a map for its page (SPEC *Line page → Map*): every station once per branch, top to
 * bottom, each branch on a rail of its own column, curving into another where they meet and out of
 * one where they part — as a TfL line diagram draws a branching line, but one station a row, so it
 * reads like the route page's stop list and folds the same way ([folded]).
 *
 * Laid out from the line's TfL routes ([of]), never from a picture of the line: whatever TfL runs is
 * drawn, a new station or branch included, and a line whose routes can't be laid out this way (a
 * loop) gets no map rather than a wrong one.
 */
class LineMap internal constructor(
    val rows: List<Row>,
    val columns: Int,
    // False where a closure TfL places can't be put anywhere on the map: none of its track is drawn and
    // its words name no station drawn (a bus's way back left off). The page then says so, rather than
    // show a map that looks unaffected (Codex, #606).
    val closurePlaced: Boolean = true,
    // Each stop of the way back drawn as an outbound stop (a bus's pole across the road), by that outbound
    // stop's id: what a page that names stops by its route's own ids maps a drawn row's back to.
    val drawnAs: Map<String, String> = emptyMap(),
    // A route page's stretch behind where its train is boarded ([Behind]), folded whole at first; null elsewhere.
    val behind: Behind? = null,
) {
    /**
     * The rows of a route page's map ([of]'s `ridesOpen`) on the far side of where its train is boarded from
     * where it goes, [from] until [until], and the line's ends there its trains come from ([origins]): what
     * [folded] folds into one line at first, named by those ends (maintainer, 2026-10-21: "fully collapse and
     * show High Barnet/Mill Hill East (both origins) and tapping could expand one level").
     */
    class Behind(val from: Int, val until: Int, val origins: List<String>, val key: String, internal val rowKeys: Set<String>)

    /** How bad an alert placed on a station is: no service there, or its name in an alert's words. */
    enum class Level { WARNING, CLOSURE }

    /**
     * One half of a row's rail: from column [from] at its top to column [to] at its foot, straight
     * where they're the same (a branch going by) and curving where they aren't (one meeting or leaving
     * this row's). [closedGoingDown] and [closedGoingUp] where TfL places a closure on the track it
     * stands for, for trains going down the map and up it: TfL shuts a stretch one way or both.
     * [riddenGoingDown] and [riddenGoingUp] where the rider's trip rides that track, down the map and
     * up it. [runsDown] and [runsUp] where the line runs that track down the map and up it: a bus round
     * a one-way street, or to a stand its way back doesn't start from, runs one way only. [arrives] on
     * the rail into the row a track ends at, where the track is drawn once rather than at every row it
     * passes. [offRoute] on a route page's map, for another route than its train's, drawn dashed, with no rows of
     * its own ([LineMap.of]'s `ridesOpen`): one track between two of the train's stations it runs between, or a
     * stub half a row long where it runs on to the line's end. Such a track can stand for several, drawn closed where
     * any is; [stationClosedDown], [stationClosedUp], [stationUpcomingDown] and [stationUpcomingUp] are then the one
     * it meets this row's station by, which alone say whether trains call there (Codex on #1102). Elsewhere, the
     * track's own.
     */
    data class Rail(
        val from: Int,
        val to: Int,
        val closedGoingDown: Boolean = false,
        val closedGoingUp: Boolean = false,
        val riddenGoingDown: Boolean = false,
        val riddenGoingUp: Boolean = false,
        val runsDown: Boolean = true,
        val runsUp: Boolean = true,
        val arrives: Boolean = false,
        // Each closure still to come that shuts this track going down the map, and going up it: by the day it
        // starts, keyed by which closure, so one closure's day is never read as another's (Codex, #707).
        val upcomingDown: Map<Int, java.time.LocalDate> = emptyMap(),
        val upcomingUp: Map<Int, java.time.LocalDate> = emptyMap(),
        val offRoute: Boolean = false,
        val stationClosedDown: Boolean = closedGoingDown,
        val stationClosedUp: Boolean = closedGoingUp,
        val stationUpcomingDown: Map<Int, java.time.LocalDate> = upcomingDown,
        val stationUpcomingUp: Map<Int, java.time.LocalDate> = upcomingUp,
    ) {
        /** Run one way only: drawn with an arrow the way it's run. */
        val oneWay: Boolean = runsDown != runsUp

        /** Closed one way or both: drawn closed. */
        val closed: Boolean = closedGoingDown || closedGoingUp

        /** The track it meets its station by closed one way or both ([stationClosedDown]). */
        val stationClosed: Boolean = stationClosedDown || stationClosedUp

        /** Shut the way the trip rides it: a closure the other way doesn't touch the trip (Codex, #613). */
        val closedRidden: Boolean = closedGoingDown && riddenGoingDown || closedGoingUp && riddenGoingUp
    }

    data class Row(
        // This row's own identity: the stop's id, or the stop's id and which branch, for a station
        // two branches call at without meeting there (two rows).
        val key: String,
        val stopId: String,
        val name: String,
        val column: Int,
        // The rails from the row above into this one, and on from this one to the row below.
        val top: List<Rail>,
        val bottom: List<Rail>,
        // A branch ends here, the line's end included.
        val end: Boolean,
        // Branches meet or part here.
        val junction: Boolean,
        // The alert names it, where TfL placed no closure to say where the alert is ([AlertStops]).
        val marked: Boolean = false,
        // Of [marked], one the alert's words name itself, not one only between two named ends of a stretch.
        val named: Boolean = false,
        // One of the rider's starred stops.
        val starred: Boolean = false,
        // The line's station nearest the rider, within walking reach.
        val nearby: Boolean = false,
        // A stop of the rider's trip on this line.
        val riding: Boolean = false,
        // On a stretch the rider's trip rides, where it boards and gets off included.
        val ridden: Boolean = false,
        // One of [riding] that no ride's stretch places, so not known which way it's ridden.
        val ridingUnplaced: Boolean = false,
        // On the stretch the page is about (a route page's train), never folded whatever is placed on it.
        val onPath: Boolean = false,
    ) {
        private val own: List<Rail> = top.filter { it.to == column } + bottom.filter { it.from == column }

        // Every track to it closed going down the map, or going up it: no train calls going that way.
        private val noneDown: Boolean = own.isNotEmpty() && own.all { it.stationClosedDown }
        private val noneUp: Boolean = own.isNotEmpty() && own.all { it.stationClosedUp }

        /** Every track to it closed both ways: no train calls. */
        val unserved: Boolean = noneDown && noneUp

        /** Every track to it closed going one way only: trains call going the other way. */
        val servedOneWay: Boolean = noneDown != noneUp

        /**
         * The day a closure still to come shuts every track to it, so no train will call: marked with a calendar,
         * its rails drawn as they run today. No alert placed on the rider, so it never changes how the map folds.
         * Null where trains will still call (where such a closure begins, as Kennington, or one shuts it one
         * way only). One closure has to shut it all: two shutting a track each, on their own days, never leave
         * it with no train at once (Codex, #707). The soonest, where several do.
         */
        val upcomingFrom: java.time.LocalDate? = if (own.isEmpty()) {
            null
        } else {
            // Each way a track is run: a one-way track (a bus round a loop) shut its one way leaves none (Codex, #707).
            (own.first().stationUpcomingDown + own.first().stationUpcomingUp).entries
                .filter { (closure, _) ->
                    own.all { (!it.runsDown || closure in it.stationUpcomingDown) && (!it.runsUp || closure in it.stationUpcomingUp) }
                }
                .minOfOrNull { it.value }
        }

        /** A closed track to it while it's still served both ways: where a closure begins (Kennington's). */
        val besideClosure: Boolean = !unserved && !servedOneWay && own.any { it.stationClosed }

        /** What the alert places here: a closed track to it, or its name in the alert's words. */
        val alerted: Boolean = marked || own.any { it.stationClosed }


        /**
         * How bad what's placed on it is, said on a fold holding it ([Item.Fold.level]): no service one way
         * or both, else its name in an alert's words. None at an open station a closure begins beside.
         */
        val level: Level? = when {
            noneDown || noneUp -> Level.CLOSURE
            marked -> Level.WARNING
            else -> null
        }

        /**
         * An alert placed on the rider: a closed track their trip rides the way it's shut, a station on
         * it an alert's words name, or one of their own stops it shuts or names, a stop their trip boards
         * or leaves at by the way it rides where its stretch is placed (Codex, #613). Not a closure on
         * another branch at a station the trip only passes or gets off at, which folds as anywhere else.
         */
        val alertsRider: Boolean = ridden && (marked || own.any { it.closedRidden }) || (starred || nearby || ridingUnplaced) && level != null

        /**
         * On the page however the map is folded: the rider's own stops, and what an alert places on a
         * stretch their trip rides (the closure there and the open stations at its ends, where they change
         * or turn back). An alert anywhere else folds away with where it is, its fold saying how bad
         * (maintainer, 2026-10-06).
         */
        val kept: Boolean = starred || nearby || riding || alertsRider || onPath

        /**
         * A one-way track into it run down the map, and one run up it: its arrows, said to a screen reader
         * too (Codex, #665), worked out here on the worker rather than in composition.
         */
        val oneWayDown: Boolean = top.any { it.arrives && it.oneWay && it.runsDown }
        val oneWayUp: Boolean = top.any { it.arrives && it.oneWay && it.runsUp }

        /** Folds into a run with its neighbors: one track through, nothing placed on it, nothing of its own to say. */
        val plain: Boolean = !kept && !end && !junction && level == null

        /**
         * Another route's track meeting it on a route page's map, drawn dashed with no rows of its own
         * ([Rail.offRoute]): said to a screen reader as well as drawn.
         */
        val otherRoutes: Boolean = top.any { it.offRoute && it.to == column } || bottom.any { it.offRoute && it.from == column }
    }

    /** One line of the map as the page draws it: a station, or a fold standing in for several. */
    sealed interface Item {
        val key: String

        data class Station(val row: Row) : Item {
            override val key: String get() = row.key
        }

        /**
         * Several stations folded into one line, which a tap opens ([folded]'s `opened`): a [section]
         * of the line, named by where it leads, or a run of stations on one track. [ends] are the line's
         * ends folded into it, top first, by name — where it leads; [first] and [last] its first and
         * last stations; [count] how many it holds; [level] the worst alert placed on one of them or a
         * closed track of its own, said on the fold without naming which (maintainer, 2026-10-06). An
         * [unnamed] fold holds the stations inside an alert off the rider's route, between the
         * stations shown where it starts and ends: it says how many and how bad.
         */
        data class Fold(
            override val key: String,
            val rails: List<FoldRail>,
            val ends: List<String>,
            val first: String,
            val last: String,
            val count: Int,
            val section: Boolean,
            val level: Level? = null,
            val unnamed: Boolean = false,
            // A closure still to come shuts a station folded into it ([Row.upcomingFrom]).
            val upcoming: Boolean = false,
        ) : Item {
            /** [ends] as one line, "Edgware · High Barnet · Mill Hill East", joined here on the worker. */
            val endsText: String = ends.joinToString(" · ")

            /**
             * The rail a closure's no-entry sign is drawn on, with no service in it: the closed rail, else
             * the stations' own. Picked here on the worker, since the row's height depends on it.
             */
            val signRail: FoldRail? = if (level != Level.CLOSURE) null else
                rails.firstOrNull { it.closed } ?: rails.firstOrNull { it.folded } ?: rails.firstOrNull()

            /** A one-way track folded in run down the map, and one run up it ([Row.oneWayDown]). */
            val oneWayDown: Boolean = rails.any { it.oneWayDown }
            val oneWayUp: Boolean = rails.any { it.oneWayUp }
        }
    }

    /**
     * A fold's rail in [column]: dotted where its stations are [folded] into it, solid for a branch only
     * passing by; running in from the row above where [fromTop], on to the row below where [toBottom].
     */
    data class FoldRail(
        val column: Int,
        val folded: Boolean,
        val fromTop: Boolean,
        val toBottom: Boolean,
        val closed: Boolean = false,
        // A one-way track folded in on this column run down the map, and one run up it: a one-way track's
        // arrow is drawn on the rail into the row it ends at, so a fold holding that row draws it instead,
        // both ways where the tracks it holds disagree (Codex, #665).
        val oneWayDown: Boolean = false,
        val oneWayUp: Boolean = false,
        // Another route's track only, on a route page's map ([Rail.offRoute]): dashed as it is outside the fold.
        val offRoute: Boolean = false,
    )

    // Inside what an alert places on its track, so it folds rather than shows ([runs]): the stations either side
    // of it on its own track alerted too, and its own track closed both ways or neither, so a stretch's first
    // and last stations, and those either side of an open track between two closures, are never inside. A
    // station the alert's words name itself is never inside either: only one between a stretch's ends is
    // (Codex, #949).
    private val inside: BooleanArray = BooleanArray(rows.size) { k ->
        val row = rows[k]
        val own = row.top.filter { it.to == row.column } + row.bottom.filter { it.from == row.column }
        row.alerted && !row.named && (own.all { it.stationClosed } || own.none { it.stationClosed }) &&
            neighbor(k, up = true)?.alerted == true && neighbor(k, up = false)?.alerted == true
    }

    // The station next to row [k] along its own track, above it or below it: a rail between two rows is
    // drawn half in each (the upper's bottom, the lower's top), either half curving, and rows of other
    // columns between are passed by, following the rail's column through them.
    private fun neighbor(k: Int, up: Boolean): Row? {
        var column = rows[k].column
        var j = k
        while (true) {
            val next = j + if (up) -1 else 1
            if (next !in rows.indices) return null
            val (near, far) = if (up) rows[j].top to rows[next].bottom else rows[j].bottom to rows[next].top
            column = if (up) {
                val half = near.firstOrNull { it.to == column } ?: return null
                far.firstOrNull { it.to == half.from }?.from ?: return null
            } else {
                val half = near.firstOrNull { it.from == column } ?: return null
                far.firstOrNull { it.from == half.to }?.to ?: return null
            }
            if (rows[next].column == column) return rows[next]
            j = next
        }
    }

    /** Whether an alert is placed on the rider's own stops or a stretch they ride: then the rest of the line folds away ([folded]). */
    val alerted: Boolean = rows.any { it.alertsRider }

    /**
     * The map as the page draws it, with the folds keyed in [opened] open, or every station with [all].
     * With an alert placed on the rider's own stops or a stretch they ride, every other stretch that
     * leads to an end of the line folds to one line naming where it leads, so the page opens on what's
     * theirs with the rest of the line around it, though an alert off their route there still shows where it
     * starts and ends; one between two kept stations leading nowhere shows
     * its ends and junctions with its plain runs folded. Otherwise the line's ends and junctions show
     * and its plain runs fold. A fold opened shows every station it holds. An alert off the rider's own stops and
     * stretches shows the first and last stations it's placed on (maintainer, 2026-10-18), the stations
     * inside it folding between them, saying how many and how bad ([Item.Fold.level],
     * [Item.Fold.unnamed]). The rider's own stops never fold (SPEC *Line page → Map*). A route page's stretch
     * behind its train ([behind]) folds whole at first, named by where its trains come from, and folds as the
     * rest of the line does once opened, or a fold in it is.
     */
    @WorkerThread
    fun folded(opened: Set<String>, all: Boolean = false): List<Item> {
        if (all) return rows.map { Item.Station(it) }
        val items = ArrayList<Item>()
        // A route page's stretch behind its train, one line at first; opened, or a fold in it opened (one at a
        // time on a route page), to its ends and junctions with their runs folded, an alert on the way ahead or
        // not: else the tap would only swap one line for another (Codex on #1062). Never over a stop of the
        // rider's own, nor over an alert, whose first and last stations show as anywhere else rather than the
        // whole stretch reading as shut.
        val behind = behind?.takeIf { b -> (b.from until b.until).none { rows[it].kept || rows[it].alerted } }
        if (behind == null) {
            foldInto(0, rows.size, opened, items)
            return items
        }
        val open = behind.key in opened || opened.any { it.substringAfter(':') in behind.rowKeys }
        fun behindItems() {
            if (open) {
                foldInto(behind.from, behind.until, opened, items, byAlerts = false)
            } else {
                items += sectionFold(behind.key, behind.from, behind.until).let { if (behind.origins.isEmpty()) it else it.copy(ends = behind.origins) }
            }
        }
        if (behind.from == 0) {
            behindItems()
            foldInto(behind.until, rows.size, opened, items)
        } else {
            foldInto(0, behind.from, opened, items)
            behindItems()
        }
        return items
    }

    // [folded]'s rows [from] until [until], folded the way an alert on the rider has the map fold ([byAlerts]).
    private fun foldInto(from: Int, until: Int, opened: Set<String>, items: MutableList<Item>, byAlerts: Boolean = alerted) {
        // Its own row from the first: the rider's, and with nothing of theirs alerted, the line's ends and
        // junctions, an alert on one or not, so where the line goes and parts reads at a glance
        // (maintainer, 2026-10-06).
        fun shown(row: Row) = row.kept || !byAlerts && (row.end || row.junction)
        var i = from
        while (i < until) {
            if (shown(rows[i])) {
                items += Item.Station(rows[i])
                i++
                continue
            }
            var end = i
            while (end < until && !shown(rows[end])) end++
            val key = "section:${rows[i].key}"
            // A stretch holding none of the line's ends leads nowhere to name it by, and may run down
            // two trunks: shown by its runs, each on one track, rather than a "from A to B" that
            // jumps between them.
            val leadsSomewhere = (i until end).any { rows[it].end }
            when {
                // Opened in one tap, every station it holds, never to its ends with the rest folded again.
                key in opened -> (i until end).mapTo(items) { Item.Station(rows[it]) }
                byAlerts && leadsSomewhere -> sections(i, end, opened, items)
                else -> runs(i, end, opened, items)
            }
            i = end
        }
    }

    /** Whether [folded] at first, nothing opened, folds anything: else the page offers nothing to unfold. */
    @WorkerThread
    fun foldable(): Boolean = folded(emptySet()).any { it is Item.Fold }

    // Rows [from] until [until], each run on one track of two or more stations folded unless [opened]
    // holds it. The first and last stations an alert places on show, so the page says where it starts and
    // ends rather than folding them into a run that reads as shut (maintainer, 2026-10-18); the stations
    // inside it fold between them, saying how many and how bad. An end or a junction never folds into a
    // run, an alert on it or not.
    private fun runs(from: Int, until: Int, opened: Set<String>, into: MutableList<Item>) {
        fun folds(k: Int) = rows[k].let { !it.kept && !it.end && !it.junction && (!it.alerted || inside[k]) }
        var i = from
        while (i < until) {
            val row = rows[i]
            if (!folds(i)) {
                into += Item.Station(row)
                i++
                continue
            }
            var end = i + 1
            while (end < until && folds(end) && rows[end].column == row.column) end++
            val key = "run:${row.key}"
            if (key in opened) {
                for (k in i until end) into += Item.Station(rows[k])
            } else {
                val fold = runFold(key, i, end)
                into += when {
                    end - i < 2 -> Item.Station(row)
                    // Inside a closure, between the stations where it starts and ends.
                    fold.level != null -> fold.copy(unnamed = true)
                    else -> fold
                }
            }
            i = end
        }
    }

    // A stretch leading to an end of the line, with an alert placed on the rider: each alert off their route in it
    // shows its first and last stations as anywhere else (maintainer, 2026-10-18), the rest folding by where it
    // leads, or where a part leads to no end, by its runs. With none, one fold named by where it leads.
    private fun sections(from: Int, until: Int, opened: Set<String>, into: MutableList<Item>) {
        fun edge(k: Int) = rows[k].alerted && !inside[k]
        if ((from until until).none { edge(it) }) {
            into += sectionFold("section:${rows[from].key}", from, until)
            return
        }
        fun part(a: Int, b: Int) {
            if (a >= b) return
            val key = "part:${rows[a].key}"
            when {
                key in opened -> (a until b).mapTo(into) { Item.Station(rows[it]) }
                b - a >= 2 && (a until b).any { rows[it].end } -> into += sectionFold(key, a, b)
                else -> runs(a, b, opened, into)
            }
        }
        var start = from
        for (k in from until until) {
            if (!edge(k)) continue
            part(start, k)
            into += Item.Station(rows[k])
            start = k + 1
        }
        part(start, until)
    }

    private fun sectionFold(key: String, from: Int, until: Int): Item.Fold {
        val span = rows.subList(from, until)
        val columns = sortedSetOf<Int>()
        span.forEach { row ->
            columns += row.column
            (row.top + row.bottom).forEach { columns += it.from; columns += it.to }
        }
        val rails = columns.mapNotNull { c ->
            // Another route's track alone, no station of the fold's on it: dashed, and left out where it's a stub
            // running neither in nor out of the fold.
            val offRoute = span.none { it.column == c } &&
                span.all { row -> (row.top + row.bottom).all { !(it.from == c || it.to == c) || it.offRoute } }
            val fromTop = span.first().top.any { it.from == c }
            val toBottom = span.last().bottom.any { it.to == c }
            if (offRoute && !fromTop && !toBottom) return@mapNotNull null
            // The column's own rails: another route's stub meeting the train's column is that route's, never the
            // train's, so its closure or one-way running isn't the train's rail's (Codex on #1102).
            fun Rail.its() = (from == c || to == c) && this.offRoute == offRoute
            FoldRail(
                column = c,
                folded = !offRoute,
                fromTop = fromTop,
                toBottom = toBottom,
                closed = span.any { row -> (row.top + row.bottom).any { it.closed && it.its() } },
                offRoute = offRoute,
            ).withWay(span, c, offRoute)
        }
        // No service is the train's own tracks': another route's dashed track shut through it is drawn shut, nothing more.
        return fold(key, rails, span, section = true, closedTrack = rails.any { it.closed && !it.offRoute })
    }

    private fun runFold(key: String, from: Int, until: Int): Item.Fold {
        val span = rows.subList(from, until)
        val column = span.first().column
        val passing = span.first().top.filter { it.from == it.to && it.from != column }
            .map { FoldRail(it.from, folded = false, fromTop = true, toBottom = true, closed = it.closed, offRoute = it.offRoute) }
        // Its own track drawn closed where a closure shuts any of it, into or out of the fold included: not another
        // route's stub meeting it (Codex on #1102).
        val closed = span.any { row ->
            row.top.any { it.closed && !it.offRoute && it.to == column } || row.bottom.any { it.closed && !it.offRoute && it.from == column }
        }
        val own = FoldRail(column, folded = true, fromTop = true, toBottom = true, closed = closed).withWay(span, column, offRoute = false)
        return fold(key, listOf(own) + passing, span, section = false, closedTrack = closed)
    }

    // This rail with the ways the line runs the one-way tracks [span] ends on [column], those of its own kind ([offRoute]).
    private fun FoldRail.withWay(span: List<Row>, column: Int, offRoute: Boolean): FoldRail {
        val arriving = span.flatMap { row -> row.top.filter { it.arrives && it.to == column && it.oneWay && it.offRoute == offRoute } }
        return copy(oneWayDown = arriving.any { it.runsDown }, oneWayUp = arriving.any { it.runsUp })
    }

    // [closedTrack]: a track of its own closed, which says no service there even where every station
    // in it is still served (Codex, #613).
    private fun fold(key: String, rails: List<FoldRail>, span: List<Row>, section: Boolean, closedTrack: Boolean) = Item.Fold(
        key = key,
        rails = rails.sortedBy { it.column },
        ends = span.filter { it.end }.map { it.name }.distinct(),
        first = span.first().name,
        last = span.last().name,
        count = span.mapTo(HashSet()) { it.stopId }.size,
        section = section,
        level = (span.mapNotNull { it.level } + listOfNotNull(Level.CLOSURE.takeIf { closedTrack })).maxOrNull(),
        // Shut now, its no-entry sign says more: the calendar is for a station still served (Codex, #707).
        upcoming = span.any { it.upcomingFrom != null && !it.unserved && !it.servedOneWay },
    )

    companion object {
        /** More branches side by side than a phone's width gives room for: no map. */
        const val MAX_COLUMNS = 5

        /**
         * [sequence]'s line as a map, or null where its routes can't be laid out top to bottom (a loop,
         * a line drawn in more than [MAX_COLUMNS]) or there's none. [closures] are the stretches TfL
         * names an alert as shutting ([PartClosure.sections]), each its stops in the order trains run
         * through it, so a stretch TfL shuts both ways is listed once each way round; the stations
         * [alertText] names are marked, and those each of the [placed] closures' own words name where none
         * of its own track lands on the map. [starred], [nearby] and [riding] are the rider's own stops,
         * matched by stop, stop area or interchange, and [rides] the stretches their trip rides, each the stops it
         * calls at in order, where it boards first (its two ends alone where no path is known).
         *
         * The routes are taken one way (TfL's outbound, else each kept once whichever way TfL runs it),
         * turned so the line reads north to south where TfL gives positions, as a map would. Where the
         * other way calls at stops of its own (a bus's poles across the road, a one-way street), it's
         * drawn too, each of its stops as the outbound stop in the same stop area, interchange, or of its
         * name close by where there is one, and run through the outbound stops it passes without calling;
         * where that can't be laid out, or would draw a station twice that the outbound routes draw once
         * (the way back calling in another order), the outbound routes alone are. A station two branches call at without
         * meeting there (TfL lists both under one stop, as the Northern line's two Euston platforms) is
         * drawn once on each, since no train goes from one to the other there. With no [nearby] stop
         * given, the one kept as the rider's nearest is the stop the map draws nearest [here], however far:
         * chosen among its own rows, so a pole drawn as another stop, or not drawn, is never the pick.
         */
        @WorkerThread
        fun of(
            sequence: LineSequence,
            closures: List<List<String>> = emptyList(),
            alertText: String? = null,
            starred: Set<String> = emptySet(),
            riding: Set<String> = emptySet(),
            placed: List<PartClosure> = emptyList(),
            rides: List<List<String>> = emptyList(),
            nearby: Set<String> = emptySet(),
            here: Coordinates? = null,
            upcoming: List<PlannedAlert> = emptyList(),
            // Every station [rides] take open, as a route page's train is (SPEC *Route detail*); else only
            // what an alert places on them.
            ridesOpen: Boolean = false,
            // With [ridesOpen], the whole line drawn, every route's stations, rather than the train's route alone (a
            // route page's All chip).
            wholeLine: Boolean = false,
        ): LineMap? {
            val alone = oneWay(sequence, otherWay = false)
            val both = oneWay(sequence, otherWay = true)
            // The way back drawn on the outbound way's own tracks: one map, its arrows from both ways.
            if (both.routes == alone.routes) return laidOut(sequence, alone, closures, alertText, placed, starred, riding, rides, nearby, here, upcoming, ridesOpen, wholeLine)
            // Else the outbound way alone where the way back can't be drawn, with no arrows: the way back runs
            // somewhere the map doesn't show, so its tracks can't say which way buses run them.
            val unknownWay = OneWay(alone.routes, alone.same, alone.through)
            val outbound = laidOut(sequence, unknownWay, closures, alertText, placed, starred, riding, rides, nearby, here, upcoming, ridesOpen, wholeLine)
            val drawn = laidOut(sequence, both, closures, alertText, placed, starred, riding, rides, nearby, here, upcoming, ridesOpen, wholeLine) ?: return outbound
            fun LineMap.twice() = rows.size - rows.mapTo(HashSet()) { it.stopId }.size
            return if (outbound != null && drawn.twice() > outbound.twice()) outbound else drawn
        }

        private fun laidOut(
            sequence: LineSequence,
            way: OneWay,
            closures: List<List<String>>,
            alertText: String?,
            placed: List<PartClosure>,
            starred: Set<String>,
            riding: Set<String>,
            rides: List<List<String>>,
            nearby: Set<String>,
            here: Coordinates?,
            upcoming: List<PlannedAlert>,
            ridesOpen: Boolean,
            wholeLine: Boolean,
        ): LineMap? {
            fun same(id: String) = way.same[id] ?: id
            // A section's stops as the map has them: the way back's poles as the outbound stops, and a hop
            // the map draws through stops the way back passes ([OneWay.through]) given those stops, so a
            // closure shutting that hop shuts the track it's drawn on (Codex, #663).
            fun drawn(section: List<String>): List<String> {
                val stops = section.map { same(it) }
                if (stops.isEmpty()) return stops
                val out = arrayListOf(stops.first())
                for ((a, b) in stops.zipWithNext()) {
                    out += way.through[a to b] ?: way.through[b to a]?.asReversed().orEmpty()
                    out += b
                }
                return out
            }
            val routes = northUp(way.routes, sequence)
            if (routes.isEmpty()) return null
            val nodeRoutes = splitStations(routes)
            val graph = Graph(nodeRoutes)
            val remaining = graph.remaining() ?: return null

            // Each closed track the way round trains can't run it.
            val closed = closures.flatMapTo(HashSet()) { section -> drawn(section).zipWithNext { a, b -> "$a>$b" } }
            // Each track a closure still to come shuts, the way round trains can't run it: by which closure and
            // the day it starts.
            val upcomingTracks = HashMap<String, HashMap<Int, java.time.LocalDate>>()
            upcoming.forEachIndexed { index, alert ->
                for (section in alert.closure?.sections.orEmpty()) {
                    drawn(section).zipWithNext { a, b -> upcomingTracks.getOrPut("$a>$b") { HashMap() }[index] = alert.startsOn }
                }
            }
            fun named(text: String?): Set<String> = if (text.isNullOrBlank()) {
                emptySet()
            } else {
                routes.flatMapTo(HashSet()) { route ->
                    AlertStops.affected(text, route.map { RouteStop(it, sequence.stopNames[it].orEmpty()) })
                }
            }
            val starredPlaces = places(starred.mapTo(HashSet(starred)) { same(it) }, sequence)
            // No nearby stop given: the stop the map draws nearest the rider's fix, however far.
            val near = nearby.ifEmpty {
                if (here == null) return@ifEmpty emptySet()
                val drawnAt = graph.nodes.mapNotNull { node -> base(node).let { id -> sequence.stopPositions[id]?.let { id to it } } }.toMap()
                NearestByLine.onLine(drawnAt, here)?.let { setOf(it.first) }.orEmpty()
            }
            val nearbyPlaces = places(near.mapTo(HashSet(near)) { same(it) }, sequence)
            val ridingPlaces = places(riding.mapTo(HashSet(riding)) { same(it) }, sequence)
            val nodePlaces = graph.nodes.associateWith { places(setOf(base(it)), sequence) }
            // The stretches the trip rides, each through the stops it calls at in order, so it takes the
            // branch it does where two join the same stops (Codex, #613); with only its two ends known, the
            // nearest way between them. Either way down the map: an alert there is shown in full. Calls
            // the map can't find (a stop TfL has since renamed, say) still count a track each: the stretch
            // across them is the one way that many tracks long, else left out rather than a branch guessed
            // (Codex, #613).
            fun nodesAt(id: String): Set<String> {
                val at = places(setOf(same(id)), sequence)
                return graph.nodes.filterTo(HashSet()) { node -> nodePlaces.getValue(node).any { it in at } }
            }
            val ridden = HashSet<String>()
            // Each track a ride takes, as "a>b" for the way it rides it, from a to b.
            val riddenTracks = HashSet<String>()
            // Each ride's calls as the nodes they're at, and the nodes its own stretch takes.
            val rideCalls = rides.map { calls -> calls.map { nodesAt(it) } }
            val rideNodes = rideCalls.map { calls ->
                val nodes = HashSet<String>()
                var from: Set<String>? = null
                var hops = 0
                for (at in calls) {
                    hops++
                    if (at.isEmpty()) continue
                    // Found down the map from where it boards, or up it: the way it rides each track.
                    val down = from?.let { if (hops == 1) graph.between(it, at) else graph.exactly(it, at, hops) }
                    val way = down ?: from?.let { if (hops == 1) graph.between(at, it) else graph.exactly(at, it, hops) }
                    way?.let {
                        nodes += it
                        // Each way runs from its lower end up.
                        it.zipWithNext { lower, upper -> riddenTracks += if (down != null) "$upper>$lower" else "$lower>$upper" }
                    }
                    from = at
                    hops = 0
                }
                // A one-station ride (a train ending where it's boarded) takes no track, but its station
                // is still the trip's own, open on the map: where it's one row. A station on two
                // branches (Euston) has no track to say which, so it opens none, and the page keeps
                // its list (Codex, #729).
                if (calls.size == 1) calls.single().singleOrNull()?.let { nodes += it }
                ridden += nodes
                nodes
            }

            // A route page's map of its train's route alone, unless it's asked for the whole line.
            val routeAlone = ridesOpen && !wholeLine
            // A route page's train's path, kept in one column ([layOut]).
            val path = if (routeAlone) ridden else emptySet()
            // A route page's map has its train's route alone, behind where it's boarded and on from there: every
            // other route's stations have no row, its tracks drawn dashed where they meet the train's, and the
            // train's rail is the rightmost, beside the names (maintainer, 2026-10-22). Laid out as the routes run
            // through what's kept, another route's way between two kept stations a stop of its own standing in for
            // it ([standIns]), its row dropped once laid out, since every rail runs straight through it: so it keeps a
            // track of its own beside any the two share, and what's placed on the tracks it stands for is drawn on it
            // ([along]). Each stub is kept by the station it runs to or from (Codex on #1102).
            val own = if (routeAlone) ownRoute(graph, ridden, riddenTracks, way.travel) else null
            val kept = own?.first
            val standIns = HashMap<String, List<String>>()
            val into = HashMap<String, MutableSet<List<String>>>()
            val outOf = HashMap<String, MutableSet<List<String>>>()
            val laid = kept?.let { k ->
                Graph(
                    nodeRoutes.mapNotNull { route ->
                        val at = route.indices.filter { route[it] in k }
                        if (at.isEmpty()) return@mapNotNull null
                        if (at.first() > 0) into.getOrPut(route[at.first()]) { HashSet() } += route.subList(0, at.first() + 1)
                        if (at.last() < route.lastIndex) outOf.getOrPut(route[at.last()]) { HashSet() } += route.subList(at.last(), route.size)
                        buildList {
                            at.forEachIndexed { n, i ->
                                if (n > 0 && i > at[n - 1] + 1) {
                                    val run = route.subList(at[n - 1], i + 1)
                                    val id = STAND_IN + run.joinToString(">")
                                    standIns[id] = run
                                    add(id)
                                }
                                add(route[i])
                            }
                        }
                    },
                )
            } ?: graph
            val laidRemaining = if (kept == null) remaining else laid.remaining() ?: return null
            fun rail(from: String, to: String) = Rail(
                0, 0,
                closedGoingDown = "${base(from)}>${base(to)}" in closed,
                closedGoingUp = "${base(to)}>${base(from)}" in closed,
                riddenGoingDown = "$from>$to" in riddenTracks,
                riddenGoingUp = "$to>$from" in riddenTracks,
                runsDown = way.travel?.contains("${base(from)}>${base(to)}") ?: true,
                runsUp = way.travel?.contains("${base(to)}>${base(from)}") ?: true,
                upcomingDown = upcomingTracks["${base(from)}>${base(to)}"].orEmpty(),
                upcomingUp = upcomingTracks["${base(to)}>${base(from)}"].orEmpty(),
            )
            // Another route's dashed track standing for [runs]: closed where a track of theirs is, shut to come the
            // same, and run each way one of them runs whole. Whether trains call at the station it meets is the
            // track it meets it by, [first] of each run or its last: only every one of those closed shuts it, and
            // only a closure to come shutting each of them run that way, as each track's own runs are read on the
            // line's map (Codex on #1102).
            fun along(runs: Collection<List<String>>, first: Boolean): Rail {
                val tracks = runs.flatMap { it.zipWithNext() }
                val meets = runs.map { if (first) it[0] to it[1] else it[it.lastIndex - 1] to it.last() }
                val travel = way.travel
                fun runWhole(back: Boolean) = travel == null ||
                    runs.any { run -> run.zipWithNext().all { (a, b) -> (if (back) "${base(b)}>${base(a)}" else "${base(a)}>${base(b)}") in travel } }
                fun shut(a: String, b: String) = "${base(a)}>${base(b)}" in closed
                fun toCome(a: String, b: String) = upcomingTracks["${base(a)}>${base(b)}"].orEmpty()
                fun isRun(a: String, b: String) = travel == null || "${base(a)}>${base(b)}" in travel
                fun common(each: List<Map<Int, java.time.LocalDate>>) =
                    if (each.isEmpty()) emptyMap() else each.reduce { all, days -> all.filterKeys { it in days } }
                return Rail(
                    0, 0,
                    closedGoingDown = tracks.any { (a, b) -> shut(a, b) },
                    closedGoingUp = tracks.any { (a, b) -> shut(b, a) },
                    runsDown = runWhole(back = false),
                    runsUp = runWhole(back = true),
                    upcomingDown = tracks.fold(emptyMap()) { days, (a, b) -> days + toCome(a, b) },
                    upcomingUp = tracks.fold(emptyMap()) { days, (a, b) -> days + toCome(b, a) },
                    offRoute = true,
                    stationClosedDown = meets.all { (a, b) -> shut(a, b) },
                    stationClosedUp = meets.all { (a, b) -> shut(b, a) },
                    stationUpcomingDown = common(meets.filter { (a, b) -> isRun(a, b) }.map { (a, b) -> toCome(a, b) }),
                    stationUpcomingUp = common(meets.filter { (a, b) -> isRun(b, a) }.map { (a, b) -> toCome(b, a) }),
                )
            }
            // A track between two of the train's stations that isn't the train's: another route's, dashed (Codex on
            // #1102), drawn as the track it is.
            val ownTracks = own?.second
            val rows = layOut(laid, laidRemaining, path, flags = graph) { from, to ->
                standIns[to]?.let { along(listOf(it), first = true) }
                    ?: standIns[from]?.let { along(listOf(it), first = false) }
                    ?: rail(from, to).let { if (ownTracks != null && "$from>$to" !in ownTracks) it.copy(offRoute = true) else it }
            }?.filter { it.key !in standIns } ?: return null
            // Each closure, none of its own track on the map (the way back it shuts left off), is placed by
            // its words, as an alert TfL doesn't place, rather than leave the map looking unaffected there
            // (Codex, #606). On a route page's map, the tracks drawn are the train's route's and those its dashed
            // tracks and stubs stand for: a route sharing no station with the train's is drawn nowhere, so a
            // closure on it is placed by its words too (Codex on #1102).
            val tracks = if (kept == null) {
                graph.nodes.flatMapTo(HashSet()) { node -> graph.nextOf(node).map { "${base(node)}>${base(it)}" } }
            } else {
                HashSet<String>().apply {
                    for (node in laid.nodes) {
                        if (node in standIns) continue
                        for (next in laid.nextOf(node)) if (next !in standIns) add("${base(node)}>${base(next)}")
                    }
                    for (run in standIns.values + into.values.flatten() + outOf.values.flatten()) {
                        run.zipWithNext { a, b -> add("${base(a)}>${base(b)}") }
                    }
                }
            }
            fun landed(closure: PartClosure) = closure.sections.any { section ->
                drawn(section).zipWithNext().any { (a, b) -> "$a>$b" in tracks || "$b>$a" in tracks }
            }
            // Placed by its words only at a station the map draws: on a route page's, one of its train's route (Codex on
            // #1102).
            val drawnStops = kept?.mapTo(HashSet()) { base(it) }
            val byWords = placed.filterNot { landed(it) }.map { closure ->
                named(closure.fullText).let { stops -> if (drawnStops == null) stops else stops.filterTo(HashSet()) { it in drawnStops } }
            }
            val marked = named(alertText) + byWords.flatten()
            val texts = listOfNotNull(alertText) + placed.filterNot { landed(it) }.mapNotNull { it.fullText }
            val mentioned = texts.flatMapTo(HashSet()) { text ->
                routes.flatMap { route -> AlertStops.mentioned(text, route.map { RouteStop(it, sequence.stopNames[it].orEmpty()) }) }
            }
            // The trip's own stops by the rows its rides take: a station with a row on each of two branches
            // (Euston on the Northern line) is the rider's only on the branch they ride, where a ride runs
            // through it; matched by stop alone where none does, each ride judged by its own stretch, not
            // another's (Codex, #613). Starred stops stay matched by stop, every row of one.
            // A stop no ride places is the rider's unplaced: what shuts it counts whichever way they ride.
            val riding = HashSet<String>()
            val unplaced = HashSet<String>()
            for (row in rows) {
                if (nodePlaces.getValue(row.key).none { it in ridingPlaces }) continue
                val calling = rideCalls.indices.filter { r -> rideCalls[r].any { row.key in it } }
                val placed = calling.any { r -> row.key in rideNodes[r] }
                val lost = calling.isEmpty() || calling.any { r -> rideCalls[r].any { at -> row.key in at && at.none { it in rideNodes[r] } } }
                if (placed || lost) riding += row.key
                if (lost) unplaced += row.key
            }
            val all = rows.map { row ->
                val stop = row.stopId
                row.copy(
                    name = sequence.stopNames[stop]?.takeIf { it.isNotBlank() } ?: stop,
                    marked = stop in marked,
                    named = stop in marked && stop in mentioned,
                    starred = nodePlaces.getValue(row.key).any { it in starredPlaces },
                    nearby = nodePlaces.getValue(row.key).any { it in nearbyPlaces },
                    riding = row.key in riding,
                    ridingUnplaced = row.key in unplaced,
                    ridden = row.key in ridden,
                    onPath = ridesOpen && row.key in ridden,
                )
            }
            // Where another route runs to the train's from stations left off, or on from it to them, a dashed stub.
            val drawnRows = if (kept == null) all else stubbed(rightmost(all), into.mapValues { along(it.value, first = false) }, outOf.mapValues { along(it.value, first = true) })
            val columns = drawnRows.maxOf { row -> maxOf(row.column, (row.top + row.bottom).maxOfOrNull { maxOf(it.from, it.to) } ?: 0) } + 1
            if (columns > MAX_COLUMNS) return null
            return LineMap(
                drawnRows,
                columns,
                closurePlaced = byWords.none { it.isEmpty() },
                drawnAs = way.same,
                behind = if (ridesOpen) behindOf(drawnRows, graph, way.travel) else null,
            )
        }

        // A route page's train's own route: the nodes it rides from where it's boarded ([ridden], each track as
        // [tracks] has it, "a>b" the way it rides from a to b), and those its trains come from to there, by tracks
        // the line runs that way ([travel]; null, both ways), so a bus's stand only buses the other way end at is
        // another route's (Codex on #1102). Null where it rides no track (a train ending where it's boarded), or rides
        // them both ways, as for [behindOf], or is boarded at more than one: the line drawn whole.
        private fun ownRoute(graph: Graph, ridden: Set<String>, tracks: Set<String>, travel: Set<String>?): Pair<Set<String>, Set<String>>? {
            if (tracks.isEmpty()) return null
            val hops = tracks.map { it.substringBefore('>') to it.substringAfter('>') }
            val down = hops.all { (a, b) -> b in graph.nextOf(a) }
            if (!down && !hops.all { (a, b) -> a in graph.nextOf(b) }) return null
            val board = ridden.filter { node -> hops.none { it.second == node } }.singleOrNull() ?: return null
            val kept = HashSet(ridden)
            // Its tracks, each way round: those it rides, and those its trains come to the boarding stop by.
            val own = hops.flatMapTo(HashSet()) { (a, b) -> listOf("$a>$b", "$b>$a") }
            val queue = ArrayDeque(listOf(board))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (n in if (down) graph.previousOf(node) else graph.nextOf(node)) {
                    if (travel != null && "${base(n)}>${base(node)}" !in travel) continue
                    own += "$n>$node"
                    own += "$node>$n"
                    if (kept.add(n)) queue += n
                }
            }
            return kept to own
        }

        // [rows] with a dashed stub half a row long where another route runs [into] a station from stations left off
        // the map above it, and out of one ([outOf]) to them below it, each drawn as the rail it's keyed to, in the
        // nearest column free there, left of it first: a column of its own at the left where none is. Drawn beside a
        // dashed track meeting it the same side too, so what's placed on the stub's tracks is never lost (Codex on
        // #1102).
        private fun stubbed(rows: List<Row>, into: Map<String, Rail>, outOf: Map<String, Rail>): List<Row> {
            var out = rows.toMutableList()
            fun touches(rails: List<Rail>, column: Int) = rails.any { it.from == column || it.to == column }
            fun width() = out.maxOf { row -> maxOf(row.column, (row.top + row.bottom).maxOfOrNull { maxOf(it.from, it.to) } ?: 0) } + 1
            // The column a stub from row [i] takes, its other half in row [j]'s [other] half; null with none free.
            fun column(i: Int, j: Int?, below: Boolean): Int? {
                val row = out[i]
                val near = out.getOrNull(j ?: -1)
                return ((row.column - 1 downTo 0) + (row.column + 1 until width())).firstOrNull { c ->
                    !touches(if (below) row.bottom else row.top, c) &&
                        (near == null || near.column != c && !touches(if (below) near.top else near.bottom, c))
                }
            }
            fun shifted() = out.map { row ->
                fun Rail.moved() = copy(from = from + 1, to = to + 1)
                row.copy(column = row.column + 1, top = row.top.map { it.moved() }, bottom = row.bottom.map { it.moved() })
            }.toMutableList()
            for (i in out.indices) {
                val leaving = outOf[out[i].key]
                if (leaving != null) {
                    val next = (i + 1).takeIf { it < out.size }
                    val c = column(i, next, below = true) ?: run { out = shifted(); 0 }
                    out[i] = out[i].copy(bottom = out[i].bottom + leaving.copy(from = out[i].column, to = c))
                    if (next != null) out[next] = out[next].copy(top = out[next].top + leaving.copy(from = c, to = c))
                }
                val coming = into[out[i].key]
                if (coming != null) {
                    val previous = (i - 1).takeIf { it >= 0 }
                    val c = column(i, previous, below = false) ?: run { out = shifted(); 0 }
                    out[i] = out[i].copy(top = out[i].top + coming.copy(from = c, to = out[i].column, arrives = true))
                    if (previous != null) out[previous] = out[previous].copy(bottom = out[previous].bottom + coming.copy(from = c, to = c))
                }
            }
            return out
        }

        // [rows] with the train's path, in one column ([layOut]), as the rightmost, beside the names: the columns left
        // of it stay as they were, those right of it turn round to its left, nearest first, and any no row or rail
        // uses goes. As they were where the path takes more than one column.
        private fun rightmost(rows: List<Row>): List<Row> {
            val path = rows.filter { it.onPath }.mapTo(HashSet()) { it.column }.singleOrNull() ?: return rows
            val used = sortedSetOf<Int>()
            for (row in rows) {
                used += row.column
                for (rail in row.top + row.bottom) {
                    used += rail.from
                    used += rail.to
                }
            }
            val order = used.filter { it < path } + used.filter { it > path }.sortedDescending() + path
            val at = order.withIndex().associate { (i, column) -> column to i }
            fun Rail.moved() = copy(from = at.getValue(from), to = at.getValue(to))
            return rows.map { row -> row.copy(column = at.getValue(row.column), top = row.top.map { it.moved() }, bottom = row.bottom.map { it.moved() }) }
        }

        // A route page's stretch behind where its train is boarded ([Behind]): the rows above the boarding row where
        // the train runs down the map, below it where it runs up, and the line's ends there a way runs from to the
        // boarding row, the way the line runs each track ([travel]; null, both ways): a bus's stand it only arrives
        // at is nowhere its buses come from (Codex on #1062). None for a train going nowhere from here, or whose
        // path runs both ways from it, or with one station behind it: a fold of it saves no room, as with a run.
        private fun behindOf(rows: List<Row>, graph: Graph, travel: Set<String>?): Behind? {
            val board = rows.indexOfFirst { it.riding && it.onPath }.takeIf { it >= 0 } ?: return null
            val ahead = rows.indices.filter { it != board && rows[it].onPath }
            if (ahead.isEmpty()) return null
            val down = ahead.all { it > board }
            if (!down && ahead.any { it > board }) return null
            val (from, until) = if (down) 0 to board else board + 1 to rows.size
            if (until - from < 2) return null
            val boardKey = rows[board].key
            fun runs(a: String, b: String) = travel == null || "${base(a)}>${base(b)}" in travel
            val origins = (from until until).filter { rows[it].end }.filter { k ->
                val end = setOf(rows[k].key)
                if (down) graph.between(end, setOf(boardKey)) { a, b -> runs(a, b) } != null
                else graph.between(setOf(boardKey), end) { a, b -> runs(b, a) } != null
            }.map { rows[it].name }.distinct()
            return Behind(from, until, origins, "behind:$boardKey", (from until until).mapTo(HashSet()) { rows[it].key })
        }

        /**
         * [sequence]'s map ([of]) with [status]'s alerts placed on it: every closure TfL places under way,
         * the line's and each direction's, each on its own ([of]'s `placed`), and the stations named by
         * the alert shown ([LineStatus.fullText]) unless it is one of those closures, whose words name
         * where it already is drawn, and the stations it sends riders to instead, which no alert is at.
         * A [quieted] alert, worse than [status] but dismissed, which the page still names, has its
         * closures drawn too (Codex, #606). [rides] are the stretches the rider's trip rides, each the
         * stops it calls at in order, and [nearby] the line's station nearest the rider; with none, the
         * station the map draws nearest [here], however far ([of]).
         */
        @WorkerThread
        fun forStatus(
            sequence: LineSequence,
            status: LineStatus?,
            starred: Set<String> = emptySet(),
            riding: Set<String> = emptySet(),
            quieted: LineStatus? = null,
            rides: List<List<String>> = emptyList(),
            nearby: Set<String> = emptySet(),
            here: Coordinates? = null,
            upcoming: List<PlannedAlert> = emptyList(),
            ridesOpen: Boolean = false,
            wholeLine: Boolean = false,
        ): LineMap? {
            val placed = (placed(status) + placed(quieted)).distinct()
            return of(sequence, placed.flatMap { it.sections }.distinct(), shown(status, placed), starred, riding, placed, rides, nearby, here, upcoming, ridesOpen, wholeLine)
        }

        /**
         * What [forStatus] draws of [status] and [quieted], as one value: equal for two statuses whose maps
         * would be the same, so a status rebuilt with the same alert never sets the map to work out again.
         */
        @WorkerThread
        fun alertKey(status: LineStatus?, quieted: LineStatus? = null): String {
            val placed = (placed(status) + placed(quieted)).distinct()
            // Each closure's own words count: where its track isn't drawn, they place it ([of]).
            val closures = placed.joinToString(";") { closure ->
                closure.sections.joinToString("/") { it.joinToString(",") } + "=" + closure.fullText.orEmpty()
            }
            return "$closures|${shown(status, placed).orEmpty()}"
        }

        // Every closure TfL places under way on [status]'s line, the line's own and each direction's.
        private fun placed(status: LineStatus?): List<PartClosure> =
            status?.let { it.closures + it.byDirection.values.flatMap { way -> way.closures } }.orEmpty().filter { it.sections.isNotEmpty() }

        // The words of the alert [status] shows, unless it's one of the [placed] closures: the page's own
        // ([LineStatus.shownText]), so a rail alert whose TfL text is only a link to National Rail's page is
        // placed by National Rail's words, as it reads.
        private fun shown(status: LineStatus?, placed: List<PartClosure>): String? =
            status?.takeIf { it.disrupted }?.takeIf { shown -> placed.none { it.fullText == shown.fullText } }?.shownText

        /**
         * The line's [routes] all one way, and the stops of the other way taken as an outbound stop ([same]);
         * [through] each hop of the way back that runs past outbound stops, with those stops in order.
         */
        private class OneWay(
            val routes: List<List<String>>,
            val same: Map<String, String>,
            val through: Map<Pair<String, String>, List<String>> = emptyMap(),
            val travel: Set<String>? = null,
        )

        // The routes one way: TfL's outbound, which runs each pattern once; failing that (a sequence
        // kept before directions were), every route, each kept once whichever way it's listed. With
        // [otherWay], the inbound routes too, turned round, each stop no outbound route calls at taken as
        // the outbound one in its stop area where there is one (a bus stop's pole across the road), so
        // only where the inbound way really runs elsewhere does it add to the map (Codex, #606). Its
        // stops are taken that way ([OneWay.same]) without [otherWay] too.
        private fun oneWay(sequence: LineSequence, otherWay: Boolean): OneWay {
            val routes = sequence.routes
            val outbound = routes.filter { it.direction == "outbound" }.map { it.stopIds }.filter { it.size >= 2 }
            val kept = LinkedHashSet<List<String>>()
            if (outbound.isEmpty()) {
                for (route in routes.map { it.stopIds }.filter { it.size >= 2 }) if (route.asReversed().toList() !in kept) kept += route
                return OneWay(kept.toList(), emptyMap())
            }
            kept += outbound
            val onOutbound = outbound.flatMapTo(HashSet()) { it }
            val byArea = HashMap<String, String>()
            for (route in outbound) for (id in route) sequence.stopAreas[id]?.takeIf { it.isNotBlank() }?.let { byArea.putIfAbsent(it, id) }
            val same = HashMap<String, String>()
            val through = HashMap<Pair<String, String>, List<String>>()
            val inbound = routes.filter { it.direction == "inbound" }.map { it.stopIds }.filter { it.size >= 2 }
            // Each hop the way buses run it, outbound and back, as "a>b": a track run one way only gets an
            // arrow. Unknown (null) with no way back that meets the outbound way to say which run both.
            val travel = outbound.flatMapTo(HashSet()) { route -> route.zipWithNext { a, b -> "$a>$b" } }
            var back = false
            for (route in inbound) {
                for (id in route) {
                    if (id in onOutbound) continue
                    (sequence.stopAreas[id]?.let { byArea[it] } ?: across(id, onOutbound, sequence))?.let { same[id] = it }
                }
                // Turned to run the outbound way, a stop taken as one already just before it dropped.
                val turned = ArrayList<String>(route.size)
                for (id in route.asReversed()) {
                    val stop = same[id] ?: id
                    if (turned.lastOrNull() != stop) turned += stop
                }
                // Where it runs past outbound stops without calling (a one-way street), drawn through them:
                // the same street, not a fork with no stop of its own.
                val passed = passedThrough(turned, outbound)
                // Only where it meets the outbound way: one sharing no stop with it (no stop areas to tell
                // the poles apart by) would draw the line twice, side by side. Its stops are still taken as
                // the outbound ones without it, for a closure or the rider's stop named by its poles.
                // What it passes is kept whether or not it's drawn: its closures shut the street it runs
                // along, drawn by the outbound way where it is alone (Codex, #663).
                through += passed
                val meets = turned.size >= 2 && turned.any { it in onOutbound }
                if (meets) {
                    back = true
                    turned.zipWithNext { a, b -> travel += "$b>$a" }
                }
                if (otherWay && meets) kept += turned
            }
            return OneWay(kept.toList(), same, through, travel.takeIf { back })
        }

        /** How near a stop of the way back must be to an outbound stop of its name to be taken as it. */
        private const val ACROSS_METERS = 400.0

        // The outbound stop the way back's [id] is the same place as, where TfL gives the two no stop area
        // in common: the one in its interchange ([LineSequence.stopHubs]), else one of its name within
        // [ACROSS_METERS] (a stand round the corner from the station, a pole across a wide road). Either
        // way of its own name; the nearest where several are; none by name alone where positions aren't
        // known, so two stops that may be miles apart are never joined.
        private fun across(id: String, onOutbound: Set<String>, sequence: LineSequence): String? {
            val at = sequence.stopPositions[id]
            fun meters(other: String): Double? {
                val there = sequence.stopPositions[other] ?: return null
                return at?.let { NearestStops.distanceMeters(it.first, it.second, there.first, there.second) }
            }
            val hub = sequence.stopHubs[id]?.takeIf { it.isNotBlank() }
            if (hub != null) {
                // Only one of its own name: two of the interchange's stops named apart (King's Cross and St
                // Pancras) are two places, and the row keeps the name alerts call it by (Codex, #663). The
                // nearest where several share it and every distance is known, else none rather than a guess.
                val named = onOutbound.filter { sequence.stopHubs[it] == hub && sameStopName(sequence.stopNames[id], sequence.stopNames[it]) }
                val distances = named.map { meters(it) }
                when {
                    named.size == 1 -> return named.single()
                    named.isNotEmpty() && distances.none { it == null } ->
                        return named.zip(distances).minWithOrNull(compareBy<Pair<String, Double?>> { it.second!! }.thenBy { it.first })!!.first
                    named.isNotEmpty() -> return null
                }
            }
            at ?: return null
            val name = sequence.stopNames[id]
            return onOutbound.mapNotNull { other -> meters(other)?.let { other to it } }
                .filter { (other, meters) -> meters <= ACROSS_METERS && sameStopName(name, sequence.stopNames[other]) }
                .minWithOrNull(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first })?.first
        }

        // [turned] with each hop between two outbound stops that no outbound route runs, but the outbound
        // routes run with the same stops between, given those stops: the way back passing them by on the
        // same street. Left as it is where those stops are elsewhere on [turned] already, so no station is
        // drawn twice. Each hop so drawn, with the stops given it.
        private fun passedThrough(turned: MutableList<String>, outbound: List<List<String>>): Map<Pair<String, String>, List<String>> {
            val passed = HashMap<Pair<String, String>, List<String>>()
            val hops = outbound.flatMapTo(HashSet()) { it.zipWithNext() }
            var i = 0
            while (i < turned.size - 1) {
                val from = turned[i]
                val to = turned[i + 1]
                if (from to to !in hops) {
                    // Only where every outbound route between the two passes the same stops: with two
                    // branches between them, which one the way back runs isn't known (Codex, #663).
                    val between = outbound.mapNotNullTo(HashSet()) { route ->
                        val a = route.indexOf(from)
                        val b = route.indexOf(to)
                        if (a >= 0 && b > a + 1) route.subList(a + 1, b).toList() else null
                    }.singleOrNull()
                    if (between != null && between.none { it in turned }) {
                        turned.addAll(i + 1, between)
                        passed[from to to] = between.toList()
                        i += between.size
                    }
                }
                i++
            }
            return passed
        }

        // Turned so the line's starts lie north of its ends where TfL gives their positions: a line read
        // top to bottom as it lies on a map. Left as TfL runs it where it can't tell.
        private fun northUp(routes: List<List<String>>, sequence: LineSequence): List<List<String>> {
            fun meanLatitude(stops: List<String>): Double? =
                stops.mapNotNull { sequence.stopPositions[it]?.first }.takeIf { it.isNotEmpty() }?.average()
            val starts = meanLatitude(routes.map { it.first() }.distinct()) ?: return routes
            val ends = meanLatitude(routes.map { it.last() }.distinct()) ?: return routes
            return if (ends > starts) routes.map { it.asReversed().toList() } else routes
        }

        // Each route's stops as rows: a stop's own id, or its id and a branch number where routes call
        // at it on branches that don't meet there — no route runs from one's way in to the other's way
        // out — so each branch gets a row of its own and no curve claims a train can cross.
        private fun splitStations(routes: List<List<String>>): List<List<String>> {
            val parent = HashMap<String, String>()
            fun find(x: String): String {
                var root = x
                while (true) {
                    val up = parent[root] ?: break
                    if (up == root) break
                    root = up
                }
                parent[x] = root
                return root
            }
            fun union(a: String, b: String) {
                parent.putIfAbsent(a, a)
                parent.putIfAbsent(b, b)
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[rb] = ra
            }
            fun wayIn(route: List<String>, i: Int) = "${route[i]}<${route.getOrNull(i - 1).orEmpty()}"
            fun wayOut(route: List<String>, i: Int) = "${route[i]}>${route.getOrNull(i + 1).orEmpty()}"
            routes.forEach { route -> route.indices.forEach { i -> union(wayIn(route, i), wayOut(route, i)) } }
            // Each stop's ways through, numbered as first met.
            val branchOf = HashMap<String, MutableList<String>>()
            routes.forEach { route ->
                route.indices.forEach { i ->
                    val ways = branchOf.getOrPut(route[i]) { ArrayList() }
                    val root = find(wayIn(route, i))
                    if (root !in ways) ways += root
                }
            }
            return routes.map { route ->
                route.indices.map { i ->
                    val ways = branchOf.getValue(route[i])
                    if (ways.size == 1) route[i] else "${route[i]}$BRANCH${ways.indexOf(find(wayIn(route, i)))}"
                }
            }
        }

        /** The line's tracks: each row-to-be's stations before and after it, in the order first met. */
        private class Graph(routes: List<List<String>>) {
            val nodes = LinkedHashSet<String>()
            val next = HashMap<String, LinkedHashSet<String>>()
            val previous = HashMap<String, LinkedHashSet<String>>()

            init {
                routes.forEach { route ->
                    nodes += route
                    route.zipWithNext { a, b ->
                        next.getOrPut(a) { LinkedHashSet() } += b
                        previous.getOrPut(b) { LinkedHashSet() } += a
                    }
                }
            }

            fun nextOf(node: String): Set<String> = next[node].orEmpty()
            fun previousOf(node: String): Set<String> = previous[node].orEmpty()

            /**
             * The nodes on a shortest way down the map from one of [from] to one of [to], both included,
             * listed from the [to] end back up, by the tracks [takes] lets it (each one's upper and lower
             * node); null where none runs.
             */
            fun between(from: Set<String>, to: Set<String>, takes: (String, String) -> Boolean = { _, _ -> true }): List<String>? {
                val seen = HashSet<String>()
                val back = HashMap<String, String>()
                val queue = ArrayDeque<String>()
                from.forEach { if (seen.add(it)) queue += it }
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (node in to) {
                        val way = arrayListOf(node)
                        var at = node
                        while (true) {
                            at = back[at] ?: break
                            way += at
                        }
                        return way
                    }
                    for (n in nextOf(node)) {
                        if (takes(node, n) && seen.add(n)) {
                            back[n] = node
                            queue += n
                        }
                    }
                }
                return null
            }

            /**
             * The one way down the map from one of [from] to one of [to] that is exactly [hops] tracks
             * long, both ends included, listed from the [to] end back up; null where none is, or more
             * than one.
             */
            fun exactly(from: Set<String>, to: Set<String>, hops: Int): List<String>? {
                if (hops > nodes.size) return null
                // How many ways reach each node in as many tracks, counted to two: that's already too many.
                var layer: Map<String, Int> = from.associateWith { 1 }
                val backs = ArrayList<Map<String, String>>(hops)
                repeat(hops) {
                    val reached = HashMap<String, Int>()
                    val back = HashMap<String, String>()
                    for ((node, ways) in layer) {
                        for (n in nextOf(node)) {
                            reached[n] = minOf(2, (reached[n] ?: 0) + ways)
                            back.putIfAbsent(n, node)
                        }
                    }
                    backs += back
                    layer = reached
                }
                val ends = to.filter { it in layer }
                if (ends.sumOf { layer.getValue(it) } != 1) return null
                // Reached by one way only, so each node on it by one way from the node before.
                val way = arrayListOf(ends.single())
                for (back in backs.asReversed()) way += back.getValue(way.last())
                return way
            }

            /**
             * Each node's longest way on to a line's end, counted in stations, or null when the tracks loop
             * back on themselves and can't be read top to bottom.
             */
            fun remaining(): Map<String, Int>? {
                val waiting = nodes.associateWithTo(HashMap()) { previousOf(it).size }
                val queue = ArrayDeque(nodes.filter { waiting.getValue(it) == 0 })
                val order = ArrayList<String>(nodes.size)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    order += node
                    for (n in nextOf(node)) {
                        val left = waiting.getValue(n) - 1
                        waiting[n] = left
                        if (left == 0) queue += n
                    }
                }
                if (order.size != nodes.size) return null
                val remaining = HashMap<String, Int>()
                for (node in order.asReversed()) remaining[node] = 1 + (nextOf(node).maxOfOrNull { remaining.getValue(it) } ?: 0)
                return remaining
            }
        }

        private class Lane(val from: String, val to: String)

        // The rows, each node once, as `git log --graph` lays out history: a node takes the column of
        // the tracks coming into it (the leftmost where branches meet), its longest way on keeps that
        // column, and any other way on starts a column of its own at the leftmost one free. A branch is
        // drawn whole before the next where it can be — the shortest way on first, so a short spur is
        // drawn and its column freed before the trunk carries on beside it.
        // [closed] gives the track from one node down to the next as a rail closed whichever way round it is. The nodes
        // of [path] (a route page's train's) keep one column from where it starts, each taking the column of the track
        // in from the one before rather than the leftmost, and are drawn as soon as they're ready. Ends and junctions
        // are [flags]'s, the whole line's, where [graph] is a route page's own route alone.
        private fun layOut(
            graph: Graph,
            remaining: Map<String, Int>,
            path: Set<String> = emptySet(),
            flags: Graph = graph,
            closed: (String, String) -> Rail,
        ): List<Row>? {
            val waiting = graph.nodes.associateWithTo(HashMap()) { graph.previousOf(it).size }
            val longestFirst = compareByDescending<String> { remaining.getValue(it) }
            // A stack: the node popped next is the one ready last, so a branch carries on down while it can.
            val ready = ArrayList(graph.nodes.filter { waiting.getValue(it) == 0 }.asReversed())
            val lanes = ArrayList<Lane?>()
            fun free(): Int {
                val at = lanes.indexOfFirst { it == null }
                if (at >= 0) return at
                lanes += null
                return lanes.lastIndex
            }
            val rows = ArrayList<Row>(graph.nodes.size)
            while (ready.isNotEmpty()) {
                val node = ready.removeAt(ready.lastIndex)
                val coming = lanes.indices.filter { lanes[it]?.to == node }
                val column = coming.firstOrNull { node in path && lanes[it]?.from in path } ?: coming.minOrNull() ?: free()
                val top = lanes.mapIndexedNotNull { i, lane ->
                    lane?.let { closed(it.from, it.to).copy(from = i, to = if (it.to == node) column else i, arrives = it.to == node) }
                }
                coming.forEach { lanes[it] = null }
                // The path's way on keeps the column, else the longest.
                val onward = graph.nextOf(node).sortedWith(compareBy<String> { if (node in path && it in path) 0 else 1 }.then(longestFirst))
                val leaving = HashSet<Int>()
                onward.forEachIndexed { i, n ->
                    val at = if (i == 0) column else free()
                    lanes[at] = Lane(node, n)
                    leaving += at
                }
                val bottom = lanes.mapIndexedNotNull { i, lane ->
                    lane?.let { closed(it.from, it.to).copy(from = if (i in leaving) column else i, to = i) }
                }
                val stop = base(node)
                rows += Row(
                    key = node,
                    stopId = stop,
                    name = stop,
                    column = column,
                    top = top,
                    bottom = bottom,
                    end = flags.previousOf(node).isEmpty() || flags.nextOf(node).isEmpty(),
                    junction = flags.previousOf(node).size > 1 || flags.nextOf(node).size > 1,
                )
                // The newly ready, longest first onto the stack, so the shortest comes off first; the path's last, so it
                // comes off before them.
                onward.filter { n -> (waiting.getValue(n) - 1).also { waiting[n] = it } == 0 }
                    .sortedBy { if (it in path) 1 else 0 }
                    .forEach { ready += it }
            }
            return rows.takeIf { it.size == graph.nodes.size }
        }

        private const val BRANCH = "#"

        // A route page's stand-in for another route's way between two of its train's stations: no stop's id.
        private const val STAND_IN = "~"

        private fun base(node: String): String = node.substringBefore(BRANCH)

        // [ids] with the stop areas and interchanges [sequence] puts them in: a starred stop listed under
        // a station's other id still finds the station's row.
        private fun places(ids: Set<String>, sequence: LineSequence): Set<String> {
            if (ids.isEmpty()) return emptySet()
            val out = HashSet<String>(ids)
            for (id in ids) {
                sequence.stopAreas[id]?.takeIf { it.isNotBlank() }?.let { out += it }
                sequence.stopHubs[id]?.takeIf { it.isNotBlank() }?.let { out += it }
            }
            return out
        }
    }
}
