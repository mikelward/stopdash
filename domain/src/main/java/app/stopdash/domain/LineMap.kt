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
    // False where the closure the page shows can't be put anywhere on the map: none of its track is drawn
    // and its words name no station drawn (a bus's way back left off). The page then says so, rather
    // than show a map that looks unaffected (Codex, #606).
    val closurePlaced: Boolean = true,
) {
    /**
     * One half of a row's rail: from column [from] at its top to column [to] at its foot, straight
     * where they're the same (a branch going by) and curving where they aren't (one meeting or leaving
     * this row's). [closedGoingDown] and [closedGoingUp] where TfL places a closure on the track it
     * stands for, for trains going down the map and up it: TfL shuts a stretch one way or both.
     */
    data class Rail(val from: Int, val to: Int, val closedGoingDown: Boolean = false, val closedGoingUp: Boolean = false) {
        /** Closed one way or both: drawn closed. */
        val closed: Boolean = closedGoingDown || closedGoingUp
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
        // One of the rider's starred stops.
        val starred: Boolean = false,
        // A stop of the rider's trip on this line.
        val riding: Boolean = false,
    ) {
        private val own: List<Rail> = top.filter { it.to == column } + bottom.filter { it.from == column }

        // Every track to it closed going down the map, or going up it: no train calls going that way.
        private val noneDown: Boolean = own.isNotEmpty() && own.all { it.closedGoingDown }
        private val noneUp: Boolean = own.isNotEmpty() && own.all { it.closedGoingUp }

        /** Every track to it closed both ways: no train calls. */
        val unserved: Boolean = noneDown && noneUp

        /** Every track to it closed going one way only: trains call going the other way. */
        val servedOneWay: Boolean = noneDown != noneUp

        /** A closed track to it while it's still served both ways: where a closure begins (Kennington's). */
        val besideClosure: Boolean = !unserved && !servedOneWay && own.any { it.closed }

        /** What the alert places here: a closed track to it, or its name in the alert's words. */
        val alerted: Boolean = marked || own.any { it.closed }

        /**
         * On the page however the map is folded: the closure (its stations and the open ones at its
         * ends, where the rider changes or turns back), what the alert names, and the rider's own stops.
         */
        val kept: Boolean = alerted || starred || riding

        /** Folds into a run with its neighbors: one track through and nothing of its own to say. */
        val plain: Boolean = !kept && !end && !junction
    }

    /** One line of the map as the page draws it: a station, or a fold standing in for several. */
    sealed interface Item {
        val key: String

        data class Station(val row: Row) : Item {
            override val key: String get() = row.key
        }

        /**
         * Several stations folded into one line, which a tap opens ([folded]'s `opened`): a [section]
         * of the line the alert doesn't reach, or a run of stations on one track. [ends] are the line's
         * ends folded into it, top first, by name — where it leads; [first] and [last] its first and
         * last stations; [count] how many it holds.
         */
        data class Fold(
            override val key: String,
            val rails: List<FoldRail>,
            val ends: List<String>,
            val first: String,
            val last: String,
            val count: Int,
            val section: Boolean,
        ) : Item {
            /** [ends] as one line, "Edgware · High Barnet · Mill Hill East", joined here on the worker. */
            val endsText: String = ends.joinToString(" · ")
        }
    }

    /**
     * A fold's rail in [column]: dotted where its stations are [folded] into it, solid for a branch only
     * passing by; running in from the row above where [fromTop], on to the row below where [toBottom].
     */
    data class FoldRail(val column: Int, val folded: Boolean, val fromTop: Boolean, val toBottom: Boolean, val closed: Boolean = false)

    /** Whether the map holds anything the alert places: then the rest of the line folds away ([folded]). */
    val alerted: Boolean = rows.any { it.alerted }

    /**
     * The map as the page draws it, with the folds keyed in [opened] open, or every station with [all].
     * With an alert placed on it, every stretch the alert doesn't reach that leads to an end of the line
     * folds to one line naming where it leads, so the page opens on where the alert is with the rest of
     * the line around it; an open stretch, or one between two kept stations leading nowhere, shows its
     * ends and junctions with its plain runs still folded. With none, only the plain runs fold. The rider's
     * own stops never fold (SPEC *Line page → Map*).
     */
    @WorkerThread
    fun folded(opened: Set<String>, all: Boolean = false): List<Item> {
        if (all) return rows.map { Item.Station(it) }
        val items = ArrayList<Item>()
        if (!alerted) {
            runs(0, rows.size, opened, items)
            return items
        }
        var i = 0
        while (i < rows.size) {
            if (rows[i].kept) {
                items += Item.Station(rows[i])
                i++
                continue
            }
            var end = i
            while (end < rows.size && !rows[end].kept) end++
            val key = "section:${rows[i].key}"
            // A stretch holding none of the line's ends leads nowhere to name it by, and may run down
            // two trunks: shown by its runs, each on one track, rather than a "from A to B" that
            // jumps between them.
            val leadsSomewhere = (i until end).any { rows[it].end }
            if (key in opened || !leadsSomewhere) runs(i, end, opened, items) else items += sectionFold(key, i, end)
            i = end
        }
        return items
    }

    /** Whether [folded] at first, nothing opened, folds anything: else the page offers nothing to unfold. */
    @WorkerThread
    fun foldable(): Boolean = folded(emptySet()).any { it is Item.Fold }

    // Rows [from] until [until], each plain run of two or more on one track folded unless [opened] holds it.
    private fun runs(from: Int, until: Int, opened: Set<String>, into: MutableList<Item>) {
        var i = from
        while (i < until) {
            val row = rows[i]
            var end = i + 1
            if (row.plain) while (end < until && rows[end].plain && rows[end].column == row.column) end++
            val key = "run:${row.key}"
            if (end - i >= 2 && key !in opened) {
                into += runFold(key, i, end)
            } else {
                for (k in i until end) into += Item.Station(rows[k])
            }
            i = end
        }
    }

    private fun sectionFold(key: String, from: Int, until: Int): Item.Fold {
        val span = rows.subList(from, until)
        val columns = sortedSetOf<Int>()
        span.forEach { row ->
            columns += row.column
            (row.top + row.bottom).forEach { columns += it.from; columns += it.to }
        }
        val rails = columns.map { c ->
            FoldRail(
                column = c,
                folded = true,
                fromTop = span.first().top.any { it.from == c },
                toBottom = span.last().bottom.any { it.to == c },
                closed = span.any { row -> (row.top + row.bottom).any { it.closed && (it.from == c || it.to == c) } },
            )
        }
        return fold(key, rails, span, section = true)
    }

    private fun runFold(key: String, from: Int, until: Int): Item.Fold {
        val span = rows.subList(from, until)
        val column = span.first().column
        val passing = span.first().top.filter { it.from == it.to && it.from != column }
            .map { FoldRail(it.from, folded = false, fromTop = true, toBottom = true, closed = it.closed) }
        return fold(key, listOf(FoldRail(column, folded = true, fromTop = true, toBottom = true)) + passing, span, section = false)
    }

    private fun fold(key: String, rails: List<FoldRail>, span: List<Row>, section: Boolean) = Item.Fold(
        key = key,
        rails = rails.sortedBy { it.column },
        ends = span.filter { it.end }.map { it.name }.distinct(),
        first = span.first().name,
        last = span.last().name,
        count = span.mapTo(HashSet()) { it.stopId }.size,
        section = section,
    )

    companion object {
        /** More branches side by side than a phone's width gives room for: no map. */
        const val MAX_COLUMNS = 5

        /**
         * [sequence]'s line as a map, or null where its routes can't be laid out top to bottom (a loop,
         * a line drawn in more than [MAX_COLUMNS]) or there's none. [closures] are the stretches TfL
         * names an alert as shutting ([PartClosure.sections]), each its stops in the order trains run
         * through it, so a stretch TfL shuts both ways is listed once each way round; the stations
         * [alertText] names are marked, and those each of the [shownClosures]' own words name (the closures
         * the page names, each one of [closures]) where none of its own track lands on the map. [starred] and [riding] are
         * the rider's own stops, matched by stop, stop area or interchange.
         *
         * The routes are taken one way (TfL's outbound, else each kept once whichever way TfL runs it),
         * turned so the line reads north to south where TfL gives positions, as a map would. Where the
         * other way calls at stops of its own (a bus's poles across the road, a one-way street), it's
         * drawn too, each of its stops as the outbound stop in the same stop area where there is one;
         * where that can't be laid out, or would draw a station twice that the outbound routes draw once
         * (the way back calling in another order), the outbound routes alone are. A station two branches call at without
         * meeting there (TfL lists both under one stop, as the Northern line's two Euston platforms) is
         * drawn once on each, since no train goes from one to the other there.
         */
        @WorkerThread
        fun of(
            sequence: LineSequence,
            closures: List<List<String>> = emptyList(),
            alertText: String? = null,
            starred: Set<String> = emptySet(),
            riding: Set<String> = emptySet(),
            shownClosures: List<PartClosure> = emptyList(),
        ): LineMap? {
            val alone = oneWay(sequence, otherWay = false)
            val both = oneWay(sequence, otherWay = true)
            val outbound = laidOut(sequence, alone, closures, alertText, shownClosures, starred, riding)
            if (both.routes == alone.routes) return outbound
            val drawn = laidOut(sequence, both, closures, alertText, shownClosures, starred, riding) ?: return outbound
            fun LineMap.twice() = rows.size - rows.mapTo(HashSet()) { it.stopId }.size
            return if (outbound != null && drawn.twice() > outbound.twice()) outbound else drawn
        }

        private fun laidOut(
            sequence: LineSequence,
            way: OneWay,
            closures: List<List<String>>,
            alertText: String?,
            shownClosures: List<PartClosure>,
            starred: Set<String>,
            riding: Set<String>,
        ): LineMap? {
            fun same(id: String) = way.same[id] ?: id
            val routes = northUp(way.routes, sequence)
            if (routes.isEmpty()) return null
            val nodeRoutes = splitStations(routes)
            val graph = Graph(nodeRoutes)
            val remaining = graph.remaining() ?: return null

            // Each closed track the way round trains can't run it.
            val closed = closures.flatMapTo(HashSet()) { section -> section.zipWithNext { a, b -> "${same(a)}>${same(b)}" } }
            fun named(text: String?): Set<String> = if (text.isNullOrBlank()) {
                emptySet()
            } else {
                routes.flatMapTo(HashSet()) { route ->
                    AlertStops.affected(text, route.map { RouteStop(it, sequence.stopNames[it].orEmpty()) })
                }
            }
            val starredPlaces = places(starred.mapTo(HashSet(starred)) { same(it) }, sequence)
            val ridingPlaces = places(riding.mapTo(HashSet(riding)) { same(it) }, sequence)

            val rows = layOut(graph, remaining) { from, to ->
                Rail(0, 0, closedGoingDown = "${base(from)}>${base(to)}" in closed, closedGoingUp = "${base(to)}>${base(from)}" in closed)
            } ?: return null
            val columns = rows.maxOf { row -> maxOf(row.column, (row.top + row.bottom).maxOfOrNull { maxOf(it.from, it.to) } ?: 0) } + 1
            if (columns > MAX_COLUMNS) return null
            // A closure the page names, none of its own track on the map (the way back it shuts left off), is
            // placed by its words, as an alert TfL doesn't place, rather than leave the map showing only
            // another closure, or looking unaffected (Codex, #606).
            val tracks = graph.nodes.flatMapTo(HashSet()) { node -> graph.nextOf(node).map { "${base(node)}>${base(it)}" } }
            fun landed(closure: PartClosure) = closure.sections.any { section ->
                section.zipWithNext().any { (a, b) -> "${same(a)}>${same(b)}" in tracks || "${same(b)}>${same(a)}" in tracks }
            }
            val byWords = shownClosures.filterNot { landed(it) }.map { named(it.fullText) }
            val marked = named(alertText) + byWords.flatten()
            return LineMap(
                rows.map { row ->
                    val stop = row.stopId
                    row.copy(
                        name = sequence.stopNames[stop]?.takeIf { it.isNotBlank() } ?: stop,
                        marked = stop in marked,
                        starred = places(setOf(stop), sequence).any { it in starredPlaces },
                        riding = places(setOf(stop), sequence).any { it in ridingPlaces },
                    )
                },
                columns,
                closurePlaced = byWords.none { it.isEmpty() },
            )
        }

        /**
         * [sequence]'s map ([of]) with [status]'s alerts placed on it: every closure TfL places under way,
         * the line's and each direction's, and the stations named by the alert shown ([LineStatus.fullText])
         * unless it is one of those closures, whose words name where it already is drawn, and the
         * stations it sends riders to instead, which no alert is at. A [quieted] alert, worse than
         * [status] but dismissed, which the page still names, has its closures drawn too (Codex, #606).
         */
        @WorkerThread
        fun forStatus(
            sequence: LineSequence,
            status: LineStatus?,
            starred: Set<String> = emptySet(),
            riding: Set<String> = emptySet(),
            quieted: LineStatus? = null,
        ): LineMap? {
            val placed = placed(status)
            val shown = shown(status, placed)
            val quietedPlaced = placed(quieted)
            val shownClosures = listOfNotNull(
                status?.takeIf { shown == null }?.let { shownClosure(it, placed) },
                quieted?.let { shownClosure(it, quietedPlaced) },
            )
            val sections = (placed + quietedPlaced).flatMap { it.sections }.distinct()
            return of(sequence, sections, shown, starred, riding, shownClosures)
        }

        // The closure among [placed] that [status] shows, the line's and each direction's copy of it as one,
        // whose words place it should its own track not land on the map, or, with none, say it isn't there.
        private fun shownClosure(status: LineStatus, placed: List<PartClosure>): PartClosure? {
            if (!status.disrupted) return null
            val own = placed.filter { it.shownBy(status) }
            return own.firstOrNull()?.copy(sections = own.flatMap { it.sections }.distinct())
        }

        /**
         * What [forStatus] draws of [status] and [quieted], as one value: equal for two statuses whose maps
         * would be the same, so a status rebuilt with the same alert never sets the map to work out again.
         */
        @WorkerThread
        fun alertKey(status: LineStatus?, quieted: LineStatus? = null): String {
            fun of(status: LineStatus?): String {
                val sections = placed(status).flatMap { it.sections }.distinct().joinToString(";") { it.joinToString(",") }
                val shown = status?.takeIf { it.disrupted }
                // With no words, the closure shown is known by its severity and label ([shownBy]).
                val label = shown?.takeIf { it.fullText.isNullOrBlank() }?.let { "${it.severity} ${it.description}" }.orEmpty()
                return "$sections|${shown?.fullText.orEmpty()}|$label"
            }
            return if (quieted == null) of(status) else "${of(status)}#${of(quieted)}"
        }

        // Whether [status] shows this closure: by its words, or for one TfL gave no reason for, by the
        // severity and label it's shown with ([LineStatus.naming]; Codex, #606).
        private fun PartClosure.shownBy(status: LineStatus): Boolean {
            val words = fullText?.ifBlank { null }
            return words == status.fullText?.ifBlank { null } && (words != null || severity == status.severity && description == status.description)
        }

        // Every closure TfL places under way on [status]'s line, the line's own and each direction's.
        private fun placed(status: LineStatus?): List<PartClosure> =
            status?.let { it.closures + it.byDirection.values.flatMap { way -> way.closures } }.orEmpty().filter { it.sections.isNotEmpty() }

        // The words of the alert [status] shows, unless it's one of the [placed] closures.
        private fun shown(status: LineStatus?, placed: List<PartClosure>): String? =
            status?.takeIf { it.disrupted }?.fullText?.takeIf { text -> placed.none { it.fullText == text } }

        /** The line's [routes] all one way, and the stops of the other way taken as an outbound stop ([same]). */
        private class OneWay(val routes: List<List<String>>, val same: Map<String, String>)

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
            val inbound = routes.filter { it.direction == "inbound" }.map { it.stopIds }.filter { it.size >= 2 }
            for (route in inbound) {
                for (id in route) {
                    if (id in onOutbound) continue
                    sequence.stopAreas[id]?.let { byArea[it] }?.let { same[id] = it }
                }
                // Turned to run the outbound way, a stop taken as one already just before it dropped.
                val turned = ArrayList<String>(route.size)
                for (id in route.asReversed()) {
                    val stop = same[id] ?: id
                    if (turned.lastOrNull() != stop) turned += stop
                }
                // Only where it meets the outbound way: one sharing no stop with it (no stop areas to tell
                // the poles apart by) would draw the line twice, side by side. Its stops are still taken as
                // the outbound ones without it, for a closure or the rider's stop named by its poles.
                if (otherWay && turned.size >= 2 && turned.any { it in onOutbound }) kept += turned
            }
            return OneWay(kept.toList(), same)
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
        // [closed] gives the track from one node down to the next as a rail closed whichever way round it is.
        private fun layOut(graph: Graph, remaining: Map<String, Int>, closed: (String, String) -> Rail): List<Row>? {
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
                val column = coming.minOrNull() ?: free()
                val top = lanes.mapIndexedNotNull { i, lane ->
                    lane?.let { closed(it.from, it.to).copy(from = i, to = if (it.to == node) column else i) }
                }
                coming.forEach { lanes[it] = null }
                val onward = graph.nextOf(node).sortedWith(longestFirst)
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
                    end = graph.previousOf(node).isEmpty() || graph.nextOf(node).isEmpty(),
                    junction = graph.previousOf(node).size > 1 || graph.nextOf(node).size > 1,
                )
                // The newly ready, longest first onto the stack, so the shortest comes off first.
                onward.filter { n -> (waiting.getValue(n) - 1).also { waiting[n] = it } == 0 }.forEach { ready += it }
            }
            return rows.takeIf { it.size == graph.nodes.size }
        }

        private const val BRANCH = "#"

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
