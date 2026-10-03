package app.stopdash.domain

/**
 * Groups the flat departure list into per-**place** sections so the screen can show a small
 * name header above each group of same-place cards (SPEC D8). The flat list interleaves stops
 * soonest-first, which gives a card no boarding location once more than one place is on screen;
 * grouping restores it without putting the name back on every card.
 *
 * **Two levels: the place, then the platform/pole within it** (maintainer, 2026-09-22). A "place" is
 * a cluster of stops that share a [DepartureRow.clusterId] — the two poles of a bus junction, a
 * station's several platforms — so they read as one boarding location, the way a Tube station (a
 * single stop id aggregating its platforms) already did. Grouping by stop id instead split a
 * junction's poles into two identical headers (SPEC *Finding stops*). The place name is the top level
 * (shown once); within it, each group splits on its **platform or pole**, the sub-level. A **rail**
 * place splits on its **platform** ("Platform 2 (Eastbound)") — the physical platform a rider stands
 * on, which the compass alone conflates (King's Cross Eastbound is the Circle/H&C/Met on Platform 2
 * but the Piccadilly on Platform 6). The platform number and its compass are parsed from
 * `platformName` ([PlatformDirection]); a rail direction with no platform number falls to a bare
 * compass. A **bus** pole, which carries no platform in the arrivals feed, splits on its **stop
 * letter** ("King's Cross Station (D) (towards Farringdon)"), else its **compass bearing** ("(Eastbound)"),
 * from the nearby lookup's [DepartureRow.stopLetter]/[DepartureRow.bearing]/[DepartureRow.towards]. A
 * letter-less bus pole falls back to the **"towards"** TfL puts on its sign ("➔ Archway"), then its
 * bearing, else the bare place name — never one bus's destination (maintainer, 2026-10-03). The group's [StopQualifier] carries whichever
 * cue it split on. The cluster (top level) is uniform across modes; the split (sub-level) is
 * mode-aware — rail-family modes (tube, DLR, Overground, rail) carry a `platformName`, trams don't
 * (falling to the compass/bearing/bare chain), buses use the stop letter.
 *
 * The cluster key is TfL's `stationNaptan` where it gives one, else the cleaned display name
 * ([clusterKeyOf]; resolved in the nearby lookup). Keying on TfL's own cluster is what keeps a
 * station it spells several ways together — the display name alone is an unreliable key (King's
 * Cross St. Pancras has several forms) — while still holding two genuinely distinct same-named
 * stops apart where TfL gives them different clusters (maintainer, 2026-09-21).
 *
 * Pure and Android-free so the grouping rule is JVM-testable apart from the screen, and so the
 * widget can adopt the same headers later without the two surfaces drifting.
 */
object StopGrouping {

    /**
     * The rows every platform view of [groups]' stops shows beside its own platform's: each status row
     * (no trains, timed or not) in a group with no platform to split on. A suspended line with no
     * predictions names no platform, so which one it would run from is unknown, and leaving it out of a
     * platform's view would hide a known suspension (SPEC principle 1). Only those rows: a line of trains
     * naming no platform beside them (one whose every train has no time, say) is no platform's, and
     * never keeps them out (Codex).
     */
    fun unplacedStatusRows(groups: List<StopGroup>): List<DepartureRow> =
        groups.filter { it.splitKey.isEmpty() }.flatMap { group -> group.rows.filter { !it.hasTrains } }

    /**
     * The flat [rows] clustered into one [StopGroup] per place (stops sharing a display name).
     * Order is preserved: places come out in the order they first appear in [rows]. Because
     * [rows] arrive already ordered (soonest-first, or closest-stop-first near me, with warnings
     * and starred rows lifted by the caller), the leading group is still the one with the
     * soonest/closest — or most urgent — departure, and a star lifts its whole place with it. A
     * place's poles' rows stay contiguous within its group even where the flat list interleaved
     * them with another place's.
     *
     * [StopGroup.showHeader] is false only when the whole screen is a single place (the place is
     * then implied). Stop-closure alerts are not grouped — the screen renders them as header-less
     * cards ahead of these groups (SPEC *Disruptions*) — but each closure's stop still counts as a
     * place, so a lone departures group beside a closure-only stop still shows its name header.
     */
    fun groupByStop(rows: List<DepartureRow>, warningsLead: Boolean = true): List<StopGroup> {
        // Stop closures form NO group — the screen renders them as header-less cards that title
        // themselves on expand (SPEC *Disruptions*) — but a closure's stop is still a **place on
        // the screen**, so it is counted toward the header decision below (see [placeKeys]).
        // Only the non-closure rows are grouped and returned.
        val (closures, listRows) = rows.partition { it.stopDisruption != null }
        // A stop carrying a line-status "No departures" row (a suspended line with no predictions —
        // a row with no countdown, [DepartureRow.upcoming] empty) keeps its OWN group, never merged
        // into a shared-name cluster: the row names no pole or direction (the header does), so
        // merging it beside a same-named pole's catchable departures would show "141 suspended — No
        // departures" next to live 141 times with nothing to say which pole each is (SPEC principle
        // 2 — never quietly wrong). Clear stops (all rows timed) still cluster by name so a
        // junction's poles read as one place. Carving the warned stop out — rather than adding a
        // per-pole/direction qualifier to the warning row — takes the safe half now; the qualifier
        // is a deferred design (TODO.md). (Codex P1s, PR #83.)
        val warnedStops = warnedStopsOf(listRows)
        // Group by **(place, compass direction)**: the place is the cluster (a junction's poles, a
        // station's platforms) as before, and within it a rail platform's compass (parsed from
        // `platformName` by [PlatformDirection]) splits the cards into one header per direction —
        // "King's Cross – Eastbound" (SPEC D8). A row with no parseable compass (a bus pole, whose
        // bearing lives in stop metadata not the arrivals feed, or a bare "Platform 4") carries
        // none, so it falls to the bare per-place header — the split ships rail-first without
        // regressing buses. A warned stop is not split (its warning names no direction, and its
        // timed rows ride with it, as before); its rows keep the whole-stop key from [clusterKeyOf].
        val byGroup = LinkedHashMap<String, MutableList<DepartureRow>>()
        val infoOf = HashMap<String, GroupInfo>()
        // One canonical display name per place, so a station whose members TfL spells differently
        // (the very reason for clustering by `stationNaptan`) reads the same on every direction
        // header — never "King's Cross – Eastbound" beside "King's Cross St. Pancras – Westbound"
        // (Codex P2, PR #109). The first row's name for the place wins, matching the single-group
        // behavior before the direction split.
        val placeName = HashMap<String, String>()
        for (row in listRows) {
            val place = clusterKeyOf(row, warnedStops)
            placeName.getOrPut(place) { row.stopName }
            // Resolve the compass from ALL the row's predictions, not just the soonest: a row is
            // one (line, direction), and TfL can leave the platform blank on some of its
            // predictions, so the soonest may carry no compass while a later one does. Taking the
            // first that resolves keeps the row under its direction header rather than dropping to
            // the bare header until the blank prediction departs (Codex P2, PR #109).
            //
            // A no-prediction status row (a suspended line) has an empty [upcoming], so it resolves
            // to no compass and stays directionless on its own — but the stop's LIVE timed rows still
            // parse their compass. Gating on `warnedStops` here instead would null every timed row's
            // direction at a stop where any one line is suspended, collapsing a whole interchange's
            // compass sections back into one bare group (Codex P2, PR #109). The place carve-out for
            // a warned stop stays (clusterKeyOf), so a "No departures" row is still not merged with a
            // same-named pole's live same-line departures.
            val split = splitOf(row)
            val key = "$place\u0001${split.keyPart}"
            infoOf.getOrPut(key) { GroupInfo(place, split) }
            byGroup.getOrPut(key) { mutableListOf() }.add(row)
        }
        // Distinct **places** on the whole screen (direction-independent) = the grouped departure
        // places PLUS each header-less closure alert's place. A closure-only stop (its arrivals
        // failed, so it has no departure rows) forms no group here, but it is still a place the
        // departure cards must be told apart from — so without counting it a lone departures stop
        // beside a closure-only stop would read as a single-place list and drop its name header,
        // leaving those departures with no boarding location (Codex P1, PR #91). A closure keys by
        // the same cluster identity a group's place does, so a closure at a stop that also has
        // departures counts as the one shared place, not two — and a place split into several
        // direction groups is still one place, so a single station's directions don't read as
        // several distinct places.
        val closurePlaces = closures.mapTo(HashSet()) { clusterKeyOf(it, emptySet()) }
        val placeKeys = byGroup.keys.mapTo(HashSet()) { infoOf.getValue(it).place } + closurePlaces
        val multiPlace = placeKeys.size > 1
        // How many direction groups each place split into, so a single station that splits into
        // Eastbound/Westbound/… shows a header on each even though it is one place.
        val groupsPerPlace = byGroup.keys.groupingBy { infoOf.getValue(it).place }.eachCount()
        // Order the groups so a **place's direction blocks stay adjacent** (SPEC D8 — a place's
        // cards stay together, led by its soonest): a station that split into Eastbound/Westbound/…
        // must not have another place's block wedged between its directions. So places sort first
        // (by [placeRankOf], then by first appearance — which reflects soonest or, near me, closest,
        // since the caller passes rows already so-ordered), and only within a place do its blocks
        // sort by their own first appearance. A starred place still leads (its starred row already
        // sorts ahead in the caller's list).
        //
        // **[warningsLead] decides whether a warned place is lifted.** On the watched list (the
        // default) a place carrying a "No departures" line-status row leads an ordinary one — a
        // warning the user must see isn't buried, and grouping doesn't rely on the caller pre-sorting
        // warnings first. On the near-me list the caller orders by distance and passes
        // `warningsLead = false`, so a stop is never hoisted above a closer one merely for carrying an
        // alert (maintainer, 2026-09-22); the alert just rides with its stop in the flat (distance)
        // order — no special place lift, and none within the place either. Stop closures are
        // unaffected either way — they render as standalone cards outside grouping.
        val placeRank = HashMap<String, Int>()
        for ((key, groupRows) in byGroup) {
            placeRank.merge(infoOf.getValue(key).place, groupRows.minOf(::rowPriority), ::minOf)
        }
        fun placeRankOf(key: String): Int =
            if (warningsLead) placeRank.getValue(infoOf.getValue(key).place) else 0
        val placeFirstIndex = LinkedHashMap<String, Int>()
        byGroup.keys.forEach { key ->
            placeFirstIndex.getOrPut(infoOf.getValue(key).place) { placeFirstIndex.size }
        }
        val groupIndex = byGroup.keys.withIndex().associate { (i, key) -> key to i }
        return byGroup.entries
            .sortedWith(
                compareBy<Map.Entry<String, MutableList<DepartureRow>>>(
                    { placeRankOf(it.key) },
                    { placeFirstIndex.getValue(infoOf.getValue(it.key).place) },
                    { groupIndex.getValue(it.key) },
                ),
            )
            .map { (key, groupRows) ->
                val info = infoOf.getValue(key)
                // The group's **qualifier** — the cue that tells two blocks of one place apart, under
                // the place name (SPEC D8). A **rail** place splits on its platform ("Platform 2
                // (Eastbound)"), else a bare compass; a **bus** pole on its letter ("Stop D (towards
                // Farringdon)"), else the "towards" on its sign ("➔ Archway"), else its bearing; never
                // one bus's destination, which names where that bus goes, not where the stop heads.
                val qualifier = when (val s = info.split) {
                    // The platform's direction is a consensus across EVERY row in the group, not just
                    // the first row's split: separate line rows share a platform number but TfL can
                    // carry the compass on one line's predictions and omit it on another's, so the
                    // header's direction must not depend on which row landed first (Codex P2, PR #119).
                    is RowSplit.Platform -> StopQualifier.Platform(s.number, platformDirectionOf(groupRows))
                    is RowSplit.Compass -> StopQualifier.Compass(s.label)
                    is RowSplit.Letter -> StopQualifier.BusStop(s.letter, s.towards.ifEmpty { null })
                    is RowSplit.Bearing -> StopQualifier.BusBearing(s.bearing)
                    is RowSplit.Towards -> StopQualifier.Towards(s.towards)
                    RowSplit.None -> null
                }
                // A header is drawn where it tells the reader something: more than one place
                // (counting closures), a place split into several groups, or a qualifier worth naming.
                val showHeader =
                    multiPlace || qualifier != null || (groupsPerPlace[info.place] ?: 1) > 1
                StopGroup(
                    stopName = placeName.getValue(info.place),
                    key = key,
                    showHeader = showHeader,
                    rows = groupRows,
                    qualifier = qualifier,
                    placeKey = info.place,
                    splitKey = info.split.keyPart,
                )
            }
    }

    /**
     * The stops that head their own group because they carry a row with no countdown (a line-status
     * warning): see [groupByStop]. Shared with [DepartureRows.nearbyDeduped], which must key places
     * exactly as the headers do.
     */
    internal fun warnedStopsOf(listRows: List<DepartureRow>): Set<String> =
        listRows.filter { !it.hasTrains }.mapTo(HashSet()) { it.stopId }

    /**
     * The clustering identity: [DepartureRow.clusterId] — TfL's `stationNaptan` where it gives one
     * (a junction's poles and a station's platforms share it), else the cleaned display name
     * (resolved upstream in the nearby lookup). Keying on the cluster rather than the name alone is
     * what keeps a station TfL spells several ways together (King's Cross St. Pancras) while still
     * holding two genuinely distinct stops apart. A blank clusterId (a stop with no cluster known —
     * a future watched stop before its cluster is captured) groups the stop on its own.
     *
     * A stop in [warnedStops] (any stop with a warning row) is keyed by its own id instead, never
     * merged with a same-cluster clear pole — see [groupByStop]. The `\u0000` prefix keeps the
     * per-stop keys from colliding with any real clusterId.
     */
    internal fun clusterKeyOf(row: DepartureRow, warnedStops: Set<String>): String =
        if (row.stopId in warnedStops) "\u0000stop:${row.stopId}"
        else row.clusterId.ifBlank { "\u0000stop:${row.stopId}" }

    /** A group's place (the cluster key) and the [RowSplit] its rows share — the discriminator that
     *  keys the group within its place (a rail compass, a bus letter/bearing, or none). */
    private data class GroupInfo(val place: String, val split: RowSplit)

    /**
     * What a row splits its place on — the per-place group discriminator, resolved per row so rows
     * that share it land in one group. A **rail** row splits on its [Platform] number (the compass
     * direction is resolved later, as a consensus over the whole group — [platformDirectionOf]), else
     * a bare [Compass] when TfL gives a direction but no platform number; a **bus** pole on its
     * [Letter] ("D", carrying the pole's "towards"), else its [Towards] (the sign's "towards Archway"),
     * else its [Bearing] ("E"). Anything else is [None] (a stop with no cue at all, headed by its bare
     * name). The [keyPart] tags each kind so a platform "2", a bus
     * letter "2", and a bearing never collide in the group key.
     */
    private sealed interface RowSplit {
        val keyPart: String

        data class Platform(val number: String) : RowSplit {
            override val keyPart get() = "p\u0001$number"
        }

        data class Compass(val label: String) : RowSplit {
            override val keyPart get() = "c\u0001$label"
        }

        data class Letter(val letter: String, val towards: String) : RowSplit {
            override val keyPart get() = "l\u0001$letter"
        }

        // The bearing stays in the key though the header shows only the towards: two letterless poles
        // whose signs read the same but face different ways are two places to stand (Codex P2, #492).
        data class Towards(val towards: String, val bearing: String) : RowSplit {
            override val keyPart get() = "t\u0001$towards\u0001$bearing"
        }

        data class Bearing(val bearing: String) : RowSplit {
            override val keyPart get() = "b\u0001$bearing"
        }

        data object None : RowSplit {
            override val keyPart get() = ""
        }
    }

    /**
     * The [RowSplit] a row belongs to. A **rail** row splits on its platform number ("Eastbound -
     * Platform 2" → Platform 2), resolved from ALL its predictions, not just the soonest — TfL can
     * leave the platform blank on some, so a later one may carry it (Codex P2, PR #109); the compass
     * of that same platform rides along as the direction cue.
     *
     * A **bus** row is resolved first and on stop metadata alone — its pole [DepartureRow.stopLetter]
     * (carrying its [DepartureRow.towards]), else its [DepartureRow.bearing] — never the rail parser,
     * since a bus prediction can carry a stop-local `platform` that reads like a rail one ("Platform
     * 1") and would otherwise mislabel the pole (Codex P1, PR #119).
     *
     * A **rail** row splits on a platform **only when every numbered prediction agrees on one
     * platform**. [DepartureRows.forStop] already splits a direction that runs from several platforms
     * into a row per platform, so a row normally carries one; the check still guards a row built
     * without that split. Filing a Platform 2 train under a "Platform 1" header would show a departure
     * at a platform it isn't at (SPEC principle 1 — never quietly wrong), so when the numbered
     * platforms diverge the row drops to the [Compass] — but only when **every** prediction that names
     * a direction agrees on one, else it stays [None] (a platform change across directions can't claim
     * a shared compass either) (Codex P1, PR #119). A platform-less rail direction (a bare
     * "Northbound", or the platform-less leftovers of a split direction) also resolves through the
     * [Compass] path. A status row (empty `upcoming`) carries nothing to parse, so a suspended
     * line stays [None] on its own.
     */
    private fun splitOf(row: DepartureRow): RowSplit {
        // Buses split on stop metadata (letter, then bearing), never on the arrivals `platform`: a
        // bus prediction can carry a stop-local "platform" that reads like a rail one ("Platform 1"),
        // so running the rail parser on it would file the pole under "Platform 1" instead of its
        // "Stop D" (Codex P1, PR #119). Resolve the bus cues first and stop: a letter-less pole is
        // headed by the "towards" on its sign, even when several routes serve it (the sign shows it
        // all the same), before its compass bearing (maintainer, 2026-10-03).
        if (row.mode == "bus") {
            row.stopLetter.trim().ifEmpty { null }?.let { return RowSplit.Letter(it, row.towards.trim()) }
            row.towards.trim().ifEmpty { null }?.let { return RowSplit.Towards(it, row.bearing.trim()) }
            row.bearing.trim().ifEmpty { null }?.let { return RowSplit.Bearing(it) }
            return RowSplit.None
        }
        val numberedPlatforms = row.placed
            .mapNotNull { it.platform?.let(PlatformDirection::platformNumber) }
            .distinct()
        val compasses = row.placed.mapNotNull { PlatformDirection.of(it.platform) }.distinct()
        // Claim a platform only when the row is UNAMBIGUOUS: exactly one numbered platform AND no
        // conflicting direction across its predictions. A direction-only prediction that disagrees (a
        // bare "Westbound" beside "Eastbound - Platform 2") would otherwise file that Westbound
        // departure under "Platform 2" — a physical-platform claim its own data contradicts (SPEC
        // principle 1; Codex P1, PR #119). The group resolves the one agreed direction later
        // ([platformDirectionOf]); a numbered platform with no compass at all (compasses empty) is
        // still an unambiguous platform.
        if (numberedPlatforms.size == 1 && compasses.size <= 1) return RowSplit.Platform(numberedPlatforms[0])
        // A row [DepartureRows.forStop] resolved to one platform may also hold "Platform Unknown" trains
        // it folded in past the times the card shows: they don't contradict the named platform, so the
        // row keeps its header.
        val resolved = row.platform.takeIf { p ->
            p.isNotBlank() && numberedPlatforms.all { it == p || it.equals("Unknown", ignoreCase = true) }
        }
        if (resolved != null && compasses.size <= 1) return RowSplit.Platform(resolved)
        // Zero/ambiguous platform, or a conflicting direction — don't claim a platform. Fall to the
        // compass ONLY when every prediction that names a direction agrees on one: a row carrying
        // different compasses (a platform change across directions) stays [None] rather than file the
        // rest under the first compass (Codex P1, PR #119).
        if (compasses.size == 1) return RowSplit.Compass(compasses[0])
        return RowSplit.None
    }

    /**
     * The one compass direction a **platform group** agrees on ("Eastbound"), or null when its rows
     * name none or disagree. A platform group can hold several line rows (a platform shared by lines),
     * and TfL can carry the compass on one line's predictions while omitting it on another's — so the
     * direction is a consensus across every row's predictions, never just the first row's, so the
     * header's direction can't be dropped or flipped by row order (Codex P2, PR #119).
     *
     * Every prediction's direction counts, including a **direction-only** one that names no platform
     * number ("Westbound" with no "- Platform N"): a group led by "Eastbound - Platform 2" but also
     * carrying a bare "Westbound" must NOT read "Platform 2 (Eastbound)" as if the Westbound weren't
     * there — the conflicting directions cancel to no parenthetical (Codex P1, PR #119). The rows are
     * already all this platform's (a row joins a platform group only when its numbered predictions are
     * this one number), so there is no other-platform direction to exclude — [number] is unused.
     */
    private fun platformDirectionOf(rows: List<DepartureRow>): String? =
        rows.asSequence()
            .flatMap { it.placed.asSequence() }
            .mapNotNull { PlatformDirection.of(it.platform) }
            .distinct()
            .singleOrNull()

    // The trains that place [this] row at a platform: its timed ones, else (every train has no time)
    // those, which name their platform as a timed train does.
    private val DepartureRow.placed: List<Departure>
        get() = upcoming.ifEmpty { untimed.map { it.train } }

    // Matches DepartureRows' row ordering: a stop-closure warning outranks a no-prediction
    // status row, which outranks a timed departure.
    private fun rowPriority(row: DepartureRow): Int = when {
        row.stopDisruption != null -> 0
        !row.hasTrains -> 1
        row.upcoming.isEmpty() -> 3
        else -> 2
    }
}

/**
 * The cue a group header carries beside the place name — the one thing that tells two groups of one
 * place apart (SPEC D8). Exactly one kind per group; null on [StopGroup.qualifier] is the bare name.
 * The screen ([app.stopdash.ui]) owns how each renders ("Platform 2", "Eastbound", "Stop D",
 * "Southbound", "➔ Bank" — "Stop" only on a literal letter).
 */
sealed interface StopQualifier {
    /** A rail platform: its [number] ("2", rendered "Platform 2") and the compass [direction]
     *  ("Eastbound") the platform faces, null when the platform name carried none. */
    data class Platform(val number: String, val direction: String?) : StopQualifier

    /** A rail compass with no platform number (a bare "Eastbound"), parsed from `platformName`. */
    data class Compass(val label: String) : StopQualifier

    /** A bus pole: its [letter] ("D", TfL `stopLetter`) and the pole's [towards] direction
     *  description ("Farringdon", TfL `Towards`), null when TfL gave none. */
    data class BusStop(val letter: String, val towards: String?) : StopQualifier

    /** A bus pole's compass bearing ("E", TfL `CompassPoint`), the fallback when it has no letter. */
    data class BusBearing(val bearing: String) : StopQualifier

    /** A letter-less **bus** pole's "towards" as its sign gives it ("Farringdon Or Holborn Circus", TfL
     *  `Towards`), ahead of its bearing; rendered "➔ Farringdon". */
    data class Towards(val towards: String) : StopQualifier
}

/**
 * One group of departure [rows] the screen renders under a single header. A place is a cluster of
 * stops sharing [stopName] (a junction's poles, a station's platforms), so [rows] may span several
 * stop ids. [qualifier] is the group's discriminator within its place — the rail compass, a bus
 * pole's letter/bearing, or a bus place's shared terminus — or null when the group is the whole
 * place under its bare name (see [StopQualifier]).
 * [showHeader] is whether to draw the header at all (see [StopGrouping.groupByStop]). [key] is
 * stable and unique per group for a LazyColumn — the place key plus the split discriminator.
 */
data class StopGroup(
    val stopName: String,
    val key: String,
    val showHeader: Boolean,
    val rows: List<DepartureRow>,
    val qualifier: StopQualifier? = null,
    // The place (cluster) identity this group belongs to, shared by every group of one physical
    // place. The near-me screen resolves one **place-wide** header distance from it — the nearest of
    // all the place's members — so a station's split headers show one consistent distance rather than
    // each its own group's members' nearest (Codex P2, PR #109). Blank only for a default-constructed
    // group.
    val placeKey: String = "",
    // What splits this group from the place's others — its platform, pole letter, bearing or compass
    // (blank for none) — read from each row's own fields, so it holds across refreshes. Unlike [key],
    // it doesn't carry the place, whose key switches to a per-stop one while a stop has a line-status
    // row; a drill-down that already knows its stops matches on this (SPEC D8).
    val splitKey: String = "",
)
