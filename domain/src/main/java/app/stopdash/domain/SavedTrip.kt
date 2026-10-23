package app.stopdash.domain

/**
 * A trip being planned as the activity keeps it across process death: plain lists a saved-state
 * Bundle holds, which stay on the device (SPEC *Privacy*). Here rather than beside the savers so a
 * shape an older build saved is tested as it restores: the first thing a new build does after an
 * update can be to restore what the old one wrote.
 */
object SavedTrip {
    /**
     * A [ToChoice]: whether its search is up, then a picked stop's id and name, or a place's coordinate,
     * name and [PlaceArt.code], then the stop's [Pick.Level.code] (null where the row didn't say).
     */
    fun toChoiceFields(to: ToChoice): List<Any?> = listOf(
        to.picking,
        to.stopId,
        to.name,
        to.place?.coordinate?.latitude,
        to.place?.coordinate?.longitude,
        to.place?.name,
        to.place?.art?.code,
        to.level?.code,
    )

    /**
     * What [toChoiceFields] saved, or the builds before it: one that kept no level (read through
     * [Hubs.picked] where it's used, as a row that didn't say), or before that no place's art (a plain place).
     */
    fun toChoiceOf(saved: List<Any?>): ToChoice {
        val lat = saved[3] as Double?
        val lon = saved[4] as Double?
        val placeName = saved[5] as String?
        val place = if (lat != null && lon != null && placeName != null) {
            TripDestination.Place(Coordinates(lat, lon), placeName, PlaceArt.ofCode(saved.getOrNull(TO_FIELDS_WITHOUT_ART) as String?))
        } else {
            null
        }
        val level = Pick.Level.ofCode(saved.getOrNull(TO_FIELDS_WITHOUT_LEVEL) as String?)?.takeIf { saved[1] != null }
        return ToChoice(saved[0] as Boolean, saved[1] as String?, saved[2] as String, place, level)
    }

    /**
     * An [OriginChange]: the station's id and name (nulls for near me), then its To…, then the
     * station's lead ([OriginChange.Station.lead], empty for near me) and its [Pick.Level.code] (null
     * for near me, or a row that didn't say); empty for none.
     */
    fun originChangeFields(change: OriginChange?): List<Any?> = when (change) {
        null -> emptyList()
        is OriginChange.NearMe -> listOf(null, null) + toChoiceFields(change.to) + listOf(ArrayList<String>(), null)
        is OriginChange.Station -> listOf(change.id, change.name) + toChoiceFields(change.to) + listOf(ArrayList(change.lead), change.level?.code)
    }

    /**
     * What [originChangeFields] saved, or what the builds before it did: a station without its level, a
     * To… without its level, or its place's art,
     * with or without the station's lead; or a station's id and name alone, or "" for near me. Those
     * changes could only begin at a *To…* search, so they keep one open. Anything else is no change
     * under way.
     */
    fun originChangeOf(saved: List<Any?>): OriginChange? = when (saved.size) {
        ORIGIN_FIELDS, ORIGIN_FIELDS_WITHOUT_STATION_LEVEL -> originChange(saved, TO_FIELDS)
        ORIGIN_FIELDS_WITHOUT_LEVEL -> originChange(saved, TO_FIELDS_WITHOUT_LEVEL)
        ORIGIN_FIELDS_WITHOUT_ART, ORIGIN_FIELDS_WITHOUT_LEAD -> originChange(saved, TO_FIELDS_WITHOUT_ART)
        LEGACY_STATION_FIELDS -> OriginChange.Station(saved[0] as String, saved[1] as String, ToChoice.NONE.startPicking())
        LEGACY_NEAR_ME_FIELDS -> OriginChange.NearMe(ToChoice.NONE.startPicking())
        else -> null
    }

    // A change saved as its station's id and name, [toFields] of To…, then (unless an older build's) its lead
    // and its level.
    private fun originChange(saved: List<Any?>, toFields: Int): OriginChange {
        val to = toChoiceOf(saved.subList(2, 2 + toFields))
        val id = saved[0] as String?
        @Suppress("UNCHECKED_CAST")
        val lead = (saved.getOrNull(2 + toFields) as? List<String>).orEmpty()
        val level = Pick.Level.ofCode(saved.getOrNull(3 + toFields) as String?)
        return if (id == null) OriginChange.NearMe(to) else OriginChange.Station(id, saved[1] as String, to, lead, level)
    }

    private const val TO_FIELDS = 8
    // A To… before its level was kept.
    private const val TO_FIELDS_WITHOUT_LEVEL = 7
    // A To… before a place's art was kept.
    private const val TO_FIELDS_WITHOUT_ART = 6
    private const val ORIGIN_FIELDS = 2 + TO_FIELDS + 2
    // The shape before the station's own level was kept.
    private const val ORIGIN_FIELDS_WITHOUT_STATION_LEVEL = 2 + TO_FIELDS + 1
    // The shape before a To…'s level was kept.
    private const val ORIGIN_FIELDS_WITHOUT_LEVEL = 2 + TO_FIELDS_WITHOUT_LEVEL + 1
    // The shape before a place's art was kept.
    private const val ORIGIN_FIELDS_WITHOUT_ART = 2 + TO_FIELDS_WITHOUT_ART + 1
    // The shape before a station's lead was kept.
    private const val ORIGIN_FIELDS_WITHOUT_LEAD = 2 + TO_FIELDS_WITHOUT_ART
    private const val LEGACY_STATION_FIELDS = 2
    private const val LEGACY_NEAR_ME_FIELDS = 1

    /**
     * The stations open on the back stack, by id ([OpenStation]): five fields for each, its id, its name, its lead (the
     * [StationLead.name], then its modes; empty for none), its level's code and its To… ([toChoiceFields]).
     */
    fun openStationsFields(stations: Map<String, OpenStation>): List<Any?> = stations.flatMap { (id, station) ->
        listOf(
            id,
            station.name,
            ArrayList(station.lead?.let { listOf(it.name) + it.modes }.orEmpty()),
            station.level,
            ArrayList(toChoiceFields(station.to)),
        )
    }

    /** What [openStationsFields] saved. */
    fun openStationsOf(saved: List<Any?>): Map<String, OpenStation> = saved.chunked(OPEN_STATION_FIELDS).associate { fields ->
        val lead = (fields[2] as List<*>).map { it as String }
        @Suppress("UNCHECKED_CAST")
        val to = toChoiceOf(fields[4] as List<Any?>)
        fields[0] as String to OpenStation(
            name = fields[1] as String,
            lead = lead.firstOrNull()?.let { StationLead(it, lead.drop(1)) },
            level = fields[3] as String?,
            to = to,
        )
    }

    private const val OPEN_STATION_FIELDS = 5
}
