package app.stopdash.domain

import java.time.Instant

/**
 * A single predicted departure from a watched stop. TfL's feed calls these
 * "arrivals"; stopdash says departures throughout (SPEC *Departures*).
 *
 * Holds the **absolute** [expectedArrival], not the fetch-relative countdown TfL
 * returns, so the countdown recomputes client-side from the current clock as time
 * passes — "3 min" becomes "1 min" between fetches, and a service that has gone
 * drops off — without a new request (SPEC D4).
 *
 * [lineId] is retained (not just the display [lineName]) so a surface can mark a
 * departure whose line is disrupted from the watched-stop→line mapping, even when
 * the prediction itself looks normal (SPEC D3).
 *
 * [direction] is TfL's own `inbound`/`outbound` (empty when TfL gives none). It is
 * the stable key for grouping a stop's departures into per-direction rows (SPEC D8):
 * the human-readable [destination] can't substitute — TfL leaves it blank on some
 * services, and a branching line runs several destinations in one direction.
 *
 * [mode] is TfL's `modeName` (`tube`, `bus`, `dlr`, `overground`, `elizabeth-line`,
 * `tram`, …), kept so a surface can present a service in its mode's identity — a tube
 * line in its own color, a bus in London-bus red — without re-deriving the mode from
 * the line id. Empty when TfL omits it.
 *
 * [destinationId] is TfL's `destinationNaptanId`, the terminus stop's id (blank when TfL gives
 * none), so a service ending where the rider already is can be recognized ([Terminating]).
 *
 * [branch] is the "via" branch TfL names in its `towards` field — normalized to one short
 * label per trunk (`Bank`, `Charing X` on the Northern line), the form its platform boards
 * show to tell a line's two central trunks apart (a rider picks the train by it, not just
 * the terminus). TfL spells these inconsistently (`Bank`/`Bank Branch`/`CX`); [branchOf]
 * folds them. Null when TfL gives no "via" (most services, and buses). Not part of the
 * domain's own direction key, but a display surface groups by terminus *and* branch. See
 * [branchOf].
 *
 * [vehicleId] is TfL's id for the train or bus making this departure (unique within a line), so a
 * trip can follow the one the rider boards ([VehicleSource]); blank when TfL gives none.
 */
data class Departure(
    val lineId: String,
    val lineName: String,
    val direction: String,
    val destination: String,
    val platform: String?,
    val expectedArrival: Instant,
    val mode: String,
    val branch: String? = null,
    val destinationId: String = "",
    val vehicleId: String = "",
)
