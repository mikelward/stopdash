package app.stopdash.domain

/**
 * A TfL id with its places in the station model, resolved when a check that reads it runs, from the
 * raw id its feed kept; never stored on a feed (dev-docs/station-resolution.md, *Where it's
 * resolved*). Compare [station] or [hub], chosen per question; [tflId] is for what TfL itself needs:
 * requests, predictions and closures.
 *
 * Built by [Hubs.resolve] (one id) or [Hubs.resolveAll] (a call naming several records), or [exact]
 * where there's no model. Read by a ride's end ([LegEnd.of]), which On the way's last ride reads
 * ([OnTheWay.arrivingIds]).
 */
data class Resolved(
    /** As TfL gave it; null for a call naming several records, which names no one id. */
    val tflId: String?,
    /** The model's station, or a bus stop (both directions of its road); null where it can't be told. */
    val station: String?,
    /** The model's hub; null where it can't be told. */
    val hub: String?,
    /** Every stop it names: more than one only for a stop area the bundle split. */
    val stops: Set<String> = setOfNotNull(station),
    /** Every hub those stops are in. */
    val hubs: Set<String> = setOfNotNull(hub),
    /** The exact TfL ids it names: an area's own poles, never its stops' others; every record of a call. */
    val poles: Set<String> = setOfNotNull(tflId),
) {
    companion object {
        /**
         * [id] as its own station and hub: an id the model doesn't hold, or every id with no model.
         * Its station and hub are upper-cased, as [Hubs.sameStation] already matches unbundled ids
         * whatever their case, so one id from two feeds still compares equal; [tflId] keeps TfL's.
         */
        fun exact(id: String): Resolved {
            val key = id.uppercase()
            return Resolved(tflId = id, station = key, hub = key)
        }
    }
}
