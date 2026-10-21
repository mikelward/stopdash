package app.stopdash.domain

// A trip's arrival ids ([Hubs.arrivalIdsOf], [ActiveTrip.destinationIds]) hold only bundled ids, each to
// the station or stop it belongs to. TfL can name a train's end by a platform the bundle doesn't hold,
// one added after it was built: that arrives as its station through the same prefix step that places
// it in the model ([Hubs.stationOf]), `9400…` to `940G…` (dev-docs/station-model.md, `destinationIds`).

/**
 * The station or stop [id] arrives at among these arrival ids: its own entry, or for a platform they
 * don't hold, its station record's ([platformRecord]). Null for an id that isn't one of them.
 */
fun Map<String, String>.arrivalOf(id: String): String? = this[id] ?: platformRecord(id)?.let { this[it] }

/** The station record a platform's id names, as NaPTAN names an access area by its station's code. */
fun platformRecord(platform: String): String? = when {
    platform.startsWith("9100") -> "910G" + platform.removePrefix("9100")
    platform.startsWith("9400") -> "940G" + platform.removePrefix("9400")
    else -> null
}

/**
 * Where a trip's last ride gets the rider off on the way, beyond the end the Planner named: by arriving at the
 * destination's hub, at any of [stations], every TfL id of the hub's stations and stops, each to its station or
 * stop ([Hubs.idsOf]), beyond the trip's own arrival ids ([ActiveTrip.destinationIds]); and on a step-free trip at
 * any of those or of the trip's own only where the followed train's line meets the trip's level there ([end],
 * [getsOffAt]). [NONE] where the ride gets off at the destination's own stops, unchecked, as before. A ride before
 * the last reads it too, where the route rides through the destination ([checksAccess], `OnTheWay.endedAtDestination`).
 */
class Arrival(val stations: Map<String, String>, private val end: LegEnd? = null, private val planned: TripLeg? = null) {
    /** The ride's end where it arrives by hub ([LegEnd.Level.HUB]), for its other lines ([RideLines.of]); else null. */
    val hubEnd: LegEnd? get() = end?.takeIf { it.level == LegEnd.Level.HUB }

    /** Whether each call is checked against TfL's step-free table ([getsOffAt]), as on a step-free trip given one. */
    val checksAccess: Boolean get() = end?.checksAccess == true

    /**
     * Whether a train of [line] gets the rider off at [id], the trip's own or one of [stations]: anywhere, but on a
     * step-free trip only where [line] meets the trip's level there today ([LegEnd.meets]), or at the Planner's own
     * call ([planned], the last ride as planned) for its own line, which the Planner checked, as the trip page has
     * it ([LegEnd.offIds]). A platform the end doesn't hold is read as its station record's ([arrivalOf]).
     */
    fun getsOffAt(id: String, line: TripLeg): Boolean {
        val end = end?.takeIf { it.checksAccess } ?: return true
        val at = if (id in end.ids) id else platformRecord(id)?.takeIf { it in end.ids } ?: id
        if (planned != null && at == planned.toId && line.lineId == planned.lineId) return true
        return end.meets(at, line.lineId, line.mode)
    }

    companion object {
        /** Nothing beyond the end the Planner named: the ride gets off at the destination's own stops. */
        val NONE = Arrival(emptyMap())
    }
}
