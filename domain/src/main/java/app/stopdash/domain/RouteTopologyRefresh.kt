package app.stopdash.domain

import kotlinx.coroutines.CancellationException

/**
 * A line's route patterns as [RouteTopology] models them, from its Route/Sequence [routes] in both
 * directions (SPEC *Branch merging*): each route's two ends and "via" branch read from its name
 * ("Edgware &harr; Morden via Bank", as TfL spells it), its stops in travel order. A route run both
 * ways (the same stops reversed) is one pattern; the two ways are kept apart only where they differ,
 * as the Heathrow Terminal 4 loop does. This is how the bundled asset was made, so the live and
 * bundled patterns compare like for like.
 *
 * Null when a route's name doesn't read as two ends, or it has fewer than two stops: a line TfL
 * words some other way keeps the bundled patterns rather than a partial set, since a missing
 * pattern can make a branching leg look single-path and merge rows it shouldn't.
 */
fun routePatternsOf(routes: List<LineRoute>): List<RoutePattern>? {
    val patterns = ArrayList<RoutePattern>()
    for (route in routes) {
        val pattern = patternOf(route) ?: return null
        val known = patterns.any { it.branch == pattern.branch && (it.stops == pattern.stops || it.stops == pattern.stops.asReversed()) }
        if (!known) patterns += pattern
    }
    return patterns.ifEmpty { null }
}

private fun patternOf(route: LineRoute): RoutePattern? {
    if (route.stopIds.size < 2) return null
    val name = route.name.trim()
    val via = VIA.find(name)
    val branch = via?.let { normalizeBranch(it.groupValues[1]) }
    val ends = (via?.let { name.substring(0, it.range.first) } ?: name).split(ARROW).map { it.trim() }
    if (ends.size != 2 || ends.any { it.isBlank() }) return null
    return RoutePattern(branch = branch, stops = route.stopIds, endA = ends[0], endB = ends[1])
}

// " via Bank" at the end of a route's name.
private val VIA = Regex("""\s+via\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)

// TfL's name separates the two ends with an HTML entity, not the character it stands for; either reads.
private val ARROW = Regex("&harr;|↔")

/**
 * This topology with TfL's current patterns ([live], by line) in place of a line's bundled ones,
 * where they still cover every route the bundled data has ([covers]): an extension, at either end
 * or past a terminus it replaces, is taken, and a new route is taken as it comes. A line whose
 * current data no longer runs a bundled route keeps the bundled patterns: a route missing from one
 * answer could leave a branching leg looking single-path, a confident wrong merge, where a stale
 * pattern at worst keeps a label. A line the bundled data doesn't model is left out, so the
 * grouping rules only ever run on lines they were checked against.
 */
fun RouteTopology.withLive(live: Map<String, List<RoutePattern>>): RouteTopology {
    if (live.isEmpty()) return this
    return RouteTopology(
        patternsByLine.mapValues { (lineId, bundled) ->
            live[lineId]?.takeIf { covers(it, bundled) } ?: bundled
        },
    )
}

/**
 * Whether [live] still runs each of [bundled]'s routes: a pattern on the same branch calling at
 * all its stops, in order, either way round. A longer one covers it (a terminus extended past,
 * where the ends' names no longer match), so each leg the bundled data knew keeps a pattern with
 * its branch through it. A route cut back, renamed or gone doesn't.
 */
internal fun covers(live: List<RoutePattern>, bundled: List<RoutePattern>): Boolean =
    bundled.all { old ->
        live.any { new ->
            new.branch == old.branch &&
                (runs(new.stops, old.stops) || runs(new.stops, old.stops.asReversed()))
        }
    }

// Whether [route] calls at every stop of [path] in order; stops added between them don't matter.
private fun runs(route: List<String>, path: List<String>): Boolean {
    val calls = route.iterator()
    return path.all { stop -> calls.asSequence().any { it == stop } }
}

/**
 * [bundled] with each of its lines' current patterns over it where they still cover it
 * ([withLive]), read from [routes] — the route stops' own cache, kept up to a day, so this asks
 * TfL for a line's routes at most once a day and never twice for what a route page already
 * fetched. A line whose routes can't be fetched or read keeps the bundled patterns, and [warn]
 * says which (the line id and why only). Off every render and decision path: the caller runs it
 * in the background and puts the result in use when it's back (SPEC *Branch merging*).
 */
suspend fun refreshTopology(bundled: RouteTopology, routes: RouteStopsRepository, warn: (String) -> Unit = {}): RouteTopology {
    val live = HashMap<String, List<RoutePattern>>()
    for (lineId in bundled.patternsByLine.keys) {
        val sequence = try {
            routes.load(lineId, "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("line $lineId's routes not fetched (${e::class.simpleName}); keeping the bundled ones")
            continue
        }
        val patterns = routePatternsOf(sequence.routes)
        when {
            patterns == null -> warn("line $lineId's routes unreadable; keeping the bundled ones")
            !covers(patterns, bundled.patternsByLine.getValue(lineId)) ->
                warn("line $lineId's routes no longer run a bundled route; keeping the bundled ones")
            else -> live[lineId] = patterns
        }
    }
    return bundled.withLive(live)
}
