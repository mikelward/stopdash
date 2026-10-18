package app.stopdash.domain

/**
 * A stop's identity, position, and served lines — enough to rank it by distance for the
 * in-app "near me now" list (SPEC *Finding stops*) and then watch it. [lines] carries the
 * stop's served lines (a public fact about the stop, like its position) so a watched stop
 * can surface a disrupted line with no predictions as a status row (SPEC *Departures*),
 * without a second lookup. Coordinates are the user's business: they're used for the on-demand
 * ranking, kept on the device only in the never-backed-up nearby-lookup cache
 * ([NearbyStopsCache]), and never reach a log or any artifact that leaves the device
 * (SPEC *Privacy*).
 */
data class StopLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lines: List<LineRef> = emptyList(),
    // The cluster this stop belongs to (SPEC *Finding stops*): TfL's `stationNaptan` — a bus
    // junction's poles and a station's platforms share it — else the display name when TfL gives
    // none, so same-named poles still group. It keys the per-place header (SPEC D8) instead of the
    // name alone, which TfL spells several ways for one station. Blank means "group this stop on
    // its own". Not a coordinate; safe to carry (SPEC *Privacy*).
    val clusterId: String = "",
    // TfL's `hubNaptanCode` for this stop: the interchange it belongs to (`HUBKGX` ties King's
    // Cross and St Pancras together), above `stationNaptan`. Blank for a stop in no hub. Used to
    // fold an interchange's shared disruption into one alert and title it by the interchange, and
    // to keep two genuinely distinct same-named places apart (SPEC *Disruptions*). A public id,
    // not a coordinate; safe to carry (SPEC *Privacy*).
    val interchangeId: String = "",
    // The bus pole's letter ("D", from TfL's `stopLetter`) and compass bearing ("E", from the
    // `CompassPoint` property): the per-pole cue that splits a bus place into one header per pole
    // ("King's Cross Station (D)" / "(Eastbound)"), the bus analog of a rail platform's compass (SPEC D8).
    // Blank for a station or a bus stop TfL gives neither. Public facts about the stop, never
    // coordinates; safe to carry (SPEC *Privacy*).
    val stopLetter: String = "",
    val bearing: String = "",
    // The pole's "towards" direction description (TfL's `Towards`, e.g. "Farringdon Or Holborn
    // Circus"): the human-readable cue on a bus header ("Stop D (towards Farringdon)"). Blank when
    // TfL gives none. A public fact about the stop, never a coordinate.
    val towards: String = "",
)
