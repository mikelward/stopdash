package app.stopdash.domain

import java.time.Instant

/**
 * One row of the departures list (SPEC D8): a single **(stop, service, direction)**
 * group — a *service* being a line at a stop — with that group's next departures.
 * The flat list shows one row per direction, so a two-way service at a stop is two
 * rows and nothing is hidden behind a gesture.
 *
 * [direction] is TfL's `inbound`/`outbound` for display (or empty when TfL omits it) —
 * it is *not* a unique key, since a blank direction repeats across rows.
 * [directionKey] is the resolved grouping discriminator (TfL `direction` when present,
 * else the platform, else the destination). [platform] is the rail platform number the row
 * was split on ([DepartureRows.forStop]: one row per platform when a direction runs from
 * several), blank when it has none. **([stopId], [lineId], [directionKey], [platform])
 * uniquely identifies a row**; a persisted star keys on the first three only, so it pins
 * every platform of its direction, even when TfL omits `direction` and two rows would
 * otherwise share a blank one.
 * [destination] is the human-readable headline — the *soonest* upcoming departure's
 * destination; a branching line can run several destinations in one direction, so
 * later entries in [upcoming] may differ, but the headline names what leaves next.
 * [upcoming] holds that group's not-yet-departed departures, soonest-first. It is empty
 * only for a **status row** — a disrupted line with no predictions (a suspended line
 * often returns none), surfaced so it isn't silently dropped for want of a departure
 * (SPEC *Departures*). A status row is direction-independent ([direction] blank,
 * [directionKey] the [STATUS_DIRECTION_KEY] sentinel) and always carries a non-null
 * [status]; a prediction group with nothing upcoming still produces no row at all.
 * [mode] is the group's TfL mode (all its departures share the line, so the mode is
 * one value), carried so the row can be shown in its mode's identity — a tube line's
 * color, London-bus red — without the render layer re-deriving it.
 * [status] is the line's disruption, if any — set only when the line is disrupted, so a
 * non-null value marks the row (SPEC *Disruptions*: a delayed or suspended line's
 * countdowns are flagged rather than shown as if trustworthy). Null when the line has a
 * good service, or when its status was not looked up.
 * [statusDismissed] is true when the line *is* disrupted but the user dismissed that alert, so
 * [status] was cleared for display ([DepartureRows.withoutDismissed]); a surface then shows no
 * warning yet never claims the line is clean.
 * [stopDisruption] marks a **stop-level status row** — a whole-stop disruption (a closure)
 * rather than a line one (SPEC *Disruptions*). When set, the row is *about the stop*: it
 * carries no line (blank [lineId]/[lineName]/[mode]), no [upcoming] and no [status], and
 * the text is TfL's stop-disruption description **normalized** ([normalizeDisruptionText]: `\n`
 * escapes turned into line breaks) but **not** name-stripped — the leading place name is dropped
 * only at display ([cleanDisruptionBody]), so the member-independent normalized text is what the
 * near-me fold dedupes on. Null on every line and timed row.
 * [fetchedAt] is the age of the stop this row came from (the snapshot stamps each stop
 * independently), so the screen withholds *this row's* countdowns when *its* stop is stale
 * — a stop that failed to refresh goes to "—" while a fresh stop beside it still shows live
 * numbers (SPEC D4). Rows from the same stop share one value; it is not part of a row's
 * identity (that is `(stopId, lineId, directionKey, platform)`).
 */
data class DepartureRow(
    val stopId: String,
    val stopName: String,
    // The cluster this stop belongs to — TfL's `stationNaptan` else the display name (see
    // [StopArrivals.clusterId]). The screen groups rows into per-place headers by this, not by
    // [stopName], so a station whose poles TfL spells differently still reads as one place, and two
    // genuinely distinct stops that share a name stay apart (SPEC D8). Blank groups the stop alone.
    val clusterId: String = "",
    // The bus pole's letter ("D"), compass bearing ("E"), and "towards" description ("Farringdon"),
    // from the nearby lookup (see [StopArrivals]). A bus place splits into one header per pole by the
    // letter (else bearing) — "King's Cross Station (D) (towards Farringdon)" — the bus analog of a
    // rail platform (SPEC D8). All blank for a station, a letter-less bus stop, or a watched stop (no
    // letter captured yet). Not part of a row's identity (that is
    // `(stopId, lineId, directionKey, platform)`).
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
    val lineId: String,
    val lineName: String,
    val direction: String,
    val directionKey: String,
    val platform: String = "",
    val destination: String,
    val mode: String,
    val upcoming: List<Departure>,
    val fetchedAt: Instant,
    val status: LineStatus? = null,
    val statusDismissed: Boolean = false,
    // The line's work that hasn't started yet, for the way this row goes ([LineStatus.planned]):
    // noted on the row and spelled out on its page, never flagged as a disruption.
    val plannedAlerts: List<PlannedAlert> = emptyList(),
    val stopDisruption: String? = null,
    // For a National Rail line's status row, where its station's National Rail times stand (see
    // [NoTimes]); null otherwise (SPEC *National Rail*).
    val railFeed: RailFeed? = null,
    // The TfL windows of the notices behind [stopDisruption] ("from..to", ISO instants, blank for an
    // open bound), sorted and joined; blank when none is dated. Part of the dismissal identity, so a
    // dismissed closure whose window TfL extends or moves shows again (SPEC *Disruptions*).
    val stopDisruptionWindows: String = "",
    // The interchange this stop belongs to, TfL's `hubNaptanCode` ([StopLocation.hubId]): `HUBKGX`
    // ties King's Cross and St Pancras together. Blank for a stop in no hub. A near-me stop-status
    // row folds by this so an interchange's shared disruption is one alert, and two genuinely
    // distinct same-named places (different or absent hub) stay apart (SPEC *Disruptions*).
    val hubId: String = "",
    // The interchange's display name (TfL's own, e.g. "King's Cross & St Pancras International"),
    // resolved for a hub with a disruption. Blank when there's no hub or the lookup failed; the
    // alert then titles itself by the stop's own [stopName] instead. Titles a folded alert by the
    // interchange rather than one member stop (SPEC *Disruptions*).
    val hubName: String = "",
    // Every member-station spelling of this stop's interchange (TfL spells King's Cross St. Pancras
    // ~a dozen ways). Used only to strip a redundant leading name from the disruption body when the
    // notice leads with a *different* member's spelling than [stopName] — no one name catches them
    // all, the union does. Empty for a stop in no hub, or when the hub lookup failed. Resolved per
    // refresh alongside [hubName]; not persisted (like the disruption itself).
    val placeAliases: List<String> = emptyList(),
    // The trains of this row's line and way its station's National Rail board listed with no time
    // ([UntimedTrain]: canceled, or delayed with no estimate), soonest scheduled first: drawn in the
    // row's countdowns in their place, never timed (SPEC *National Rail*). Only the in-app list has
    // them ([StopArrivals.untimed]); empty everywhere else.
    val untimed: List<UntimedTrain> = emptyList(),
)

/**
 * Whether [this] row has trains to show — timed ([DepartureRow.upcoming]) or with no time
 * ([DepartureRow.untimed]: a line whose every train its board lists is canceled or delayed) — rather
 * than being a status row, which has neither.
 */
val DepartureRow.hasTrains: Boolean get() = upcoming.isNotEmpty() || untimed.isNotEmpty()

/**
 * When [this] row's soonest train leaves, for ordering rows: its soonest timed train, else, for a row
 * whose every train has no time, its soonest by schedule. Null for a status row.
 */
val DepartureRow.soonestAt: Instant? get() = upcoming.firstOrNull()?.expectedArrival ?: untimed.firstOrNull()?.train?.expectedArrival

/**
 * Whether [this] row holds every train of [other], timed and with no time alike, so showing [this]
 * shows all of [other]'s. Read both lists here rather than [DepartureRow.upcoming] alone: a row's
 * trains with no time can make lines of their own ([DepartureRows.destinationLines]).
 */
fun DepartureRow.covers(other: DepartureRow): Boolean =
    upcoming.containsAll(other.upcoming) && untimed.containsAll(other.untimed)

/**
 * A line's status with no departures to go with it: a suspension's own row, drawn as the status
 * alone. It lasts only as long as the check behind it (SPEC D3/D4).
 */
val DepartureRow.isStatusOnly: Boolean get() = !hasTrains && status != null

/**
 * The [DepartureRow.directionKey] a status row carries — a fixed sentinel, since a status
 * row has no direction. It keeps `(stopId, lineId, directionKey)` unique for a status row
 * (there is one per stop+line, and the line has no prediction rows to collide with).
 */
const val STATUS_DIRECTION_KEY: String = "status"

/**
 * The [DepartureRow.directionKey] a stop-level status row carries — a fixed sentinel, so
 * `(stopId, lineId, directionKey)` is unique for the one stop-status row per stop (its
 * [DepartureRow.lineId] is blank, so it can't collide with a line's rows).
 */
const val STOP_STATUS_DIRECTION_KEY: String = "stop-status"
