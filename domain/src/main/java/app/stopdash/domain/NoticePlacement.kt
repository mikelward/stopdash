package app.stopdash.domain

/**
 * Where each near-me stop notice goes (SPEC *Disruptions*), given the list's [groups] and the ones
 * drawn in the loaded places' run ([listed]). Each notice arrives once per interchange (the fold
 * upstream), so each is drawn once:
 * - **on its pole** ([onPole], by the pole's group key) when it's filed against one bus pole
 *   alone, and that pole has its own section — "Bus Stop Closed" at Stop E says nothing about Stop F;
 * - **as its place's own group directly above the place's first listed section** ([aboveGroup], by
 *   that section's index in [listed]) — a station-wide notice, one heading for every platform;
 * - **as its own group, placed by distance** ([placeless]) when the place has no section listed: a
 *   closed station with nothing running, or a pole with no buses listed.
 */
data class NoticePlan(
    val onPole: Map<String, DepartureRow> = emptyMap(),
    val aboveGroup: Map<Int, List<DepartureRow>> = emptyMap(),
    val placeless: List<DepartureRow> = emptyList(),
)

fun planNotices(
    notices: List<DepartureRow>,
    groups: List<StopGroup>,
    listed: List<StopGroup>,
    // Each stop's interchange (TfL `hubNaptanCode`), which a departure row doesn't carry: an
    // interchange's notice goes above whichever member's sections come first.
    hubOf: Map<String, String> = emptyMap(),
    // The notices filed against more than one stop of their place, as ([stopPlaceKey], text): about
    // the place, not the one pole the fold kept them on.
    shared: Set<Pair<String, String>> = emptySet(),
    // The groups not drawn right now (a held loading card's, until it's tapped open): a pole's notice
    // there goes with the card instead ([placeless]), so it isn't hidden behind the tap.
    hiddenGroupKeys: Set<String> = emptySet(),
    // A list grouped by station, whose groups go by mode, names each group's station: a notice goes above the first
    // group of the station holding its own stop where one is listed, so it heads its own mode's station, never the
    // first of its place's (maintainer, 2026-10-23), and the list reads it there with no gathering of its own.
    stationOf: ((StopGroup) -> String)? = null,
): NoticePlan {
    if (notices.isEmpty()) return NoticePlan()
    val onPole = LinkedHashMap<String, DepartureRow>()
    val aboveGroup = LinkedHashMap<Int, MutableList<DepartureRow>>()
    val placeless = ArrayList<DepartureRow>()
    for (notice in notices) {
        if (isPole(notice) && (stopPlaceKey(notice) to notice.stopDisruption.orEmpty()) !in shared) {
            val drawn = groups.filter { g -> g.key !in hiddenGroupKeys && g.rows.any { it.stopId == notice.stopId } }
            // Only a section that is this pole's alone: a pole with no letter or bearing can share a
            // section with its neighbors, whose heading must not read as closed too — its notice
            // then heads the place as its own group (below).
            val pole = drawn.firstOrNull { g -> g.rows.all { it.stopId == notice.stopId } }
            if (pole != null || drawn.isEmpty()) {
                if (pole != null && pole.key !in onPole) onPole[pole.key] = notice else placeless += notice
                continue
            }
        }
        val place = noticePlaceOf(notice)
        val own = if (stationOf != null) listed.indexOfFirst { g -> g.rows.any { it.stopId == notice.stopId } } else -1
        val first = if (own >= 0) listed.indexOfFirst { stationOf!!(it) == stationOf(listed[own]) } else listed.indexOfFirst { g ->
            g.rows.any { r ->
                r.stopId == notice.stopId ||
                    noticePlaceOf(r) == place ||
                    (notice.interchangeId.isNotBlank() && (r.interchangeId == notice.interchangeId || hubOf[r.stopId] == notice.interchangeId))
            }
        }
        if (first >= 0) aboveGroup.getOrPut(first) { ArrayList() } += notice else placeless += notice
    }
    return NoticePlan(onPole, aboveGroup, placeless)
}

/**
 * A notice about one bus pole ("Stop E", or a letter-less pole TfL marks only by its bearing), not a
 * whole station or junction — told by the stop's id, as the disruption fetch does, since an
 * arrow-only pole carries no letter.
 */
fun isPole(notice: DepartureRow): Boolean = StopDisruptionBatch.isPole(notice.stopId)

/**
 * A stop's place for matching its notice: a real StopArea cluster, else the stop alone. The
 * display-name fallback cluster doesn't count (as in [stopPlaceKey]) — two unrelated stops sharing a
 * name would otherwise swap closures.
 */
private fun noticePlaceOf(row: DepartureRow): String =
    row.clusterId.takeUnless { it.isBlank() || it == row.stopName } ?: "\u0000stop:${row.stopId}"
