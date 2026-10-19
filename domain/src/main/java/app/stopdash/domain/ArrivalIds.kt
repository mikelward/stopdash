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
