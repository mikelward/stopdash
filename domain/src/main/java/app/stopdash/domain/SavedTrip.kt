package app.stopdash.domain

/**
 * A trip being planned as the activity keeps it across process death: plain lists a saved-state
 * Bundle holds, which stay on the device (SPEC *Privacy*). Here rather than beside the savers so a
 * shape an older build saved is tested as it restores: the first thing a new build does after an
 * update can be to restore what the old one wrote.
 */
object SavedTrip {
    /** A [ToChoice]: whether its search is up, then a picked stop's id and name, or a place's coordinate and name. */
    fun toChoiceFields(to: ToChoice): List<Any?> =
        listOf(to.picking, to.stopId, to.name, to.place?.coordinate?.latitude, to.place?.coordinate?.longitude, to.place?.name)

    /** What [toChoiceFields] saved. */
    fun toChoiceOf(saved: List<Any?>): ToChoice {
        val lat = saved[3] as Double?
        val lon = saved[4] as Double?
        val placeName = saved[5] as String?
        val place = if (lat != null && lon != null && placeName != null) {
            TripDestination.Place(Coordinates(lat, lon), placeName)
        } else {
            null
        }
        return ToChoice(saved[0] as Boolean, saved[1] as String?, saved[2] as String, place)
    }

    /**
     * An [OriginChange]: the station's id and name (nulls for near me), then its To…, then the
     * station's lead ([OriginChange.Station.lead], empty for near me); empty for none.
     */
    fun originChangeFields(change: OriginChange?): List<Any?> = when (change) {
        null -> emptyList()
        is OriginChange.NearMe -> listOf(null, null) + toChoiceFields(change.to) + listOf(ArrayList<String>())
        is OriginChange.Station -> listOf(change.id, change.name) + toChoiceFields(change.to) + listOf(ArrayList(change.lead))
    }

    /**
     * What [originChangeFields] saved, or what the build before it did: a station's id and name alone,
     * or "" for near me. Those changes could only begin at a *To…* search, so they keep one open.
     * Anything else is no change under way.
     */
    fun originChangeOf(saved: List<Any?>): OriginChange? = when (saved.size) {
        ORIGIN_FIELDS, ORIGIN_FIELDS_WITHOUT_LEAD -> {
            val to = toChoiceOf(saved.subList(2, ORIGIN_FIELDS_WITHOUT_LEAD))
            val id = saved[0] as String?
            @Suppress("UNCHECKED_CAST")
            val lead = (saved.getOrNull(ORIGIN_FIELDS_WITHOUT_LEAD) as? List<String>).orEmpty()
            if (id == null) OriginChange.NearMe(to) else OriginChange.Station(id, saved[1] as String, to, lead)
        }
        LEGACY_STATION_FIELDS -> OriginChange.Station(saved[0] as String, saved[1] as String, ToChoice.NONE.startPicking())
        LEGACY_NEAR_ME_FIELDS -> OriginChange.NearMe(ToChoice.NONE.startPicking())
        else -> null
    }

    private const val ORIGIN_FIELDS = 9
    // The shape before a station's lead was kept.
    private const val ORIGIN_FIELDS_WITHOUT_LEAD = 8
    private const val LEGACY_STATION_FIELDS = 2
    private const val LEGACY_NEAR_ME_FIELDS = 1
}
