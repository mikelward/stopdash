package app.stopdash.domain

/**
 * Chooses which nearby stops the "near me now" list shows (SPEC *Finding stops → Near me
 * now*). Pure math over coordinates the caller supplies — no Android, no I/O — so it stays
 * JVM-testable and off any render path.
 *
 * Stops group into **clusters** (a station's platforms, a bus junction's poles — keyed on
 * TfL's `stationNaptan`, SPEC D8). The nearest [CLUSTERS_PER_MODE] clusters of each mode are
 * **eager** (fetched and shown at once); the rest are the *more* tier, not fetched, which the
 * list's farther cards offer instead ([selectClusters] returns the two tiers as [Result]).
 * Per-mode selection guarantees the nearest station of a sparse mode — a Tube up to the ~1 mile
 * outer radius — is always eager, without a separate reserve rule.
 *
 * The per-mode cap replaces an "all of the inner ring" set: a dense corner returns many bus
 * poles, and each pole is its own arrivals request (TfL doesn't aggregate a junction), so
 * expanding them all is both long to scan and many fetches. The cap keeps the list short, the
 * farther cards keep the extra options reachable rather than silently hidden (SPEC principle 2),
 * and it caps how many *clusters* are fetched — sharply fewer at a dense corner. It bounds the
 * cluster count, not the request count: one large junction cluster is still one arrivals
 * request per pole, so it isn't a hard fetch bound independent of density (a per-cluster fetch
 * budget is a `TODO.md` follow-up). TfL's `/StopPoint` geo query takes the 1609 m radius
 * (verified 2026-09-19: HTTP 200); it returns stop metadata only, and only the selected
 * clusters' stops are then fetched for arrivals.
 */
object NearbySelection {
    /**
     * Outer ring: ~1 mile (1609 m). A mode with a stop this close is guaranteed a
     * representative — a Tube station up to a mile off still shows. TfL's `/StopPoint` geo
     * query accepts this radius (verified 2026-09-19: 1609 m → HTTP 200).
     */
    const val OUTER_RADIUS_METERS = 1609

    /**
     * How many clusters of each mode the near-me list fetches and expands at once (SPEC
     * *Finding stops → Near me now*). The rest of that mode's clusters are the *more* tier. Two keeps a busy corner scannable and the eager fetch small — a bus cluster is one
     * arrivals request per lettered pole, so a low cap matters most for the densest mode. (The
     * cap is on cluster count, not request count — a large junction cluster is still many poles;
     * a hard per-cluster fetch budget is a `TODO.md` follow-up.)
     */
    const val CLUSTERS_PER_MODE = 2

    /**
     * The walking reach of the eager tier (maintainer, 2026-09-23): a mode's clusters are fetched
     * up front only within this distance, up to [CLUSTERS_PER_MODE] of them. A mode with nothing
     * this close still gets its single nearest cluster out to [OUTER_RADIUS_METERS], so a sparse
     * mode keeps a representative; everything else is the *more* tier. Without it, "the nearest two
     * of each mode" reached a mile out at a big interchange — a second Overground station 1.3 km off
     * — and spent the keyless rate budget on stops nobody would walk to.
     */
    const val EAGER_RADIUS_METERS = 500

    // Selection bucket for a served cluster whose routes TfL gave no mode for, so it isn't dropped
    // from every mode's top-N and made to vanish. Internal to selection — never a real mode.
    private const val UNKNOWN_MODE = "\u0000unknown"

    /**
     * The modes that pick as **one** here — London's metro rail (maintainer, 2026-09-30): the Tube,
     * the Overground, the DLR and the Elizabeth line are all turn-up-and-go rail across the city, so
     * a station of any of them within reach stands for all four. Picked apart, each needed its own
     * station however far: at King's Cross, with the Tube a few hundred meters off, Euston (800 m)
     * was fetched every refresh as the nearest Overground, for one line, bringing its whole National
     * Rail board with it. A metro line no loaded stop serves still gets a farther card
     * (*Farther stations*), which costs no request until tapped. Only picking folds them: a
     * cluster's [NearbyCluster.modes] stay TfL's, and hiding a mode keeps its own groups
     * ([ModeGroups]). Trams stay apart — a separate network, not an alternative to these.
     */
    internal val METRO_MODES = setOf("tube", "overground", "dlr", "elizabeth-line")

    // The key a mode picks under: one shared key for every metro mode, else the mode itself.
    private const val METRO = "\u0000metro"

    /**
     * A cluster of nearby stops that share a [StopLocation.clusterId] — a station's platforms or
     * a junction's poles — ranked by its nearest member's distance. The unit the near-me list
     * expands and fetches as one place (SPEC *Finding stops → Near me now*, D8).
     */
    data class NearbyCluster(
        // Grouping key: the shared clusterId, else a per-stop key so an unclustered stop stands
        // alone (mirrors StopGrouping's keyless fallback). Its identity within a [Result]'s tiers.
        val key: String,
        // The cluster's stops, nearest-first. A Tube station is one stop id (its platforms are
        // already aggregated behind it); a bus junction is one stop per lettered pole.
        val stops: List<StopLocation>,
        // The nearest member's distance from the fix — how the cluster ranks against others.
        val distanceMeters: Double,
    ) {
        /** The distinct modes served across this cluster's stops. */
        val modes: Set<String> = stops.flatMapTo(linkedSetOf()) { it.modes() }
    }

    /**
     * The two tiers of the near-me list (SPEC *Finding stops → Near me now*):
     * - [eager]: the nearest [CLUSTERS_PER_MODE] clusters of each mode, fetched and shown at
     *   once — so the nearest of a sparse mode (a lone Tube station out to the radius) is always
     *   in, without a separate reserve rule.
     * - [more]: the remaining clusters, globally distance-ordered, not fetched: places the rider
     *   may be at (a service terminating there), and what the list's farther cards draw on.
     *
     * The per-mode cap replaces the old "show everything in the inner ring" set: a dense corner
     * returns many bus poles, and expanding them all is both long to scan and many arrivals
     * fetches (each bus pole is its own request — TfL doesn't aggregate a junction). The cap
     * keeps the list short, and caps how many clusters are fetched (the count, not the request
     * total — a large junction cluster is still many poles).
     */
    data class Result(
        val eager: List<NearbyCluster>,
        val more: List<NearbyCluster>,
    )

    /**
     * Group the nearby [stops] into clusters and split them into the eager and "more" tiers
     * (see [Result]). Pure over the coordinates the caller supplies — no Android, no I/O — so it
     * stays JVM-testable and off any render path.
     */
    fun selectClusters(
        stops: List<StopLocation>,
        latitude: Double,
        longitude: Double,
        clustersPerMode: Int = CLUSTERS_PER_MODE,
        outerRadiusMeters: Int = OUTER_RADIUS_METERS,
        eagerRadiusMeters: Int = EAGER_RADIUS_METERS,
    ): Result {
        val clusters = stops
            .mapNotNull { stop ->
                val meters = NearestStops.distanceMeters(latitude, longitude, stop.latitude, stop.longitude)
                if (meters <= outerRadiusMeters) stop to meters else null
            }
            // A blank clusterId groups the stop alone (a per-stop key), so two same-named stops
            // TfL never clustered don't merge — the same keyless fallback StopGrouping uses. A
            // route-less stop is split from its cluster's served poles into a cluster of its own
            // kind, so a junction with one disused pole doesn't pull that pole in when the junction
            // is eager — route-less stops are never eager (below).
            .groupBy { (stop, _) ->
                val key = stop.clusterId.ifBlank { "\u0000stop:${stop.id}" }
                if (stop.lines.isEmpty()) "$key\u0000no-routes" else key
            }
            .map { (key, members) ->
                val sorted = members.sortedWith(compareBy({ it.second }, { it.first.id }))
                NearbyCluster(key = key, stops = sorted.map { it.first }, distanceMeters = sorted.first().second)
            }
            .sortedWith(compareBy({ it.distanceMeters }, { it.stops.first().id }))
        if (clusters.isEmpty()) return Result(emptyList(), emptyList())

        // Each mode's nearest [clustersPerMode] clusters within [eagerRadiusMeters] are eager; a mode
        // with none that close contributes just its single nearest cluster (out to the outer radius),
        // so a sparse mode keeps a representative without the eager set reaching a mile out. Their
        // union is the eager set. The metro modes pick as one mode ([METRO_MODES]), and an
        // interchange's metro stations as one place: Canary Wharf's Tube, DLR and Elizabeth line
        // stations are separate clusters in one hub, which picked apart would crowd each other out of
        // the shared cap — and the one left over gets no farther card either, since its interchange
        // is already on the list. So a hub takes one of the cap's places and brings all its metro
        // stations. A cluster serving two modes is eager if either mode picks it, so the nearest
        // station of a sparse mode is never crowded out by a denser one. A cluster whose routes TfL
        // gave no mode for buckets under [UNKNOWN_MODE], so a served stop with thin
        // metadata is still selected rather than vanishing. A **route-less** cluster — TfL lists no
        // routes at it, a disused or unserved stop — is never eager: it has no departures to show,
        // so auto-fetching it only spends the rate budget (two requests a pole) the stops that do
        // run need. It stays in the *more* tier, which only buses page through a button: a route-less
        // stop has no departures a tap could show.
        fun modesOf(cluster: NearbyCluster): Set<String> = when {
            cluster.modes.isNotEmpty() -> cluster.modes.mapTo(HashSet()) { if (it.lowercase() in METRO_MODES) METRO else it }
            cluster.stops.any { it.lines.isNotEmpty() } -> setOf(UNKNOWN_MODE)
            else -> emptySet()
        }
        val eagerKeys = HashSet<String>()
        // The place a cluster counts as toward its mode's cap: its interchange for a metro station in
        // one, else the cluster itself. Places keep the nearest-first order of their nearest member.
        fun placeOf(cluster: NearbyCluster, mode: String): String =
            cluster.stops.firstNotNullOfOrNull { it.hubId.ifBlank { null } }
                ?.takeIf { mode == METRO }?.let { "\u0000hub:$it" }
                ?: cluster.key
        for (mode in clusters.flatMapTo(sortedSetOf()) { modesOf(it) }) {
            val places = clusters.filter { mode in modesOf(it) }.groupBy { placeOf(it, mode) }.values
            val walkable = places.filter { it.first().distanceMeters <= eagerRadiusMeters }.take(clustersPerMode)
            (walkable.ifEmpty { places.take(1) }).forEach { place -> place.forEach { eagerKeys += it.key } }
        }
        // Both tiers keep the global nearest-first order.
        return Result(
            eager = clusters.filter { it.key in eagerKeys },
            more = clusters.filterNot { it.key in eagerKeys },
        )
    }

    /** The distinct transport modes a stop serves, from its lines (blank modes ignored). */
    private fun StopLocation.modes(): Set<String> =
        lines.mapNotNullTo(mutableSetOf()) { it.mode.takeIf(String::isNotBlank) }
}
