package app.stopdash.data

import kotlinx.serialization.Serializable

/**
 * One station's entry in TfL's live lift disruptions (`/Disruptions/Lifts/v2`): its station id and
 * the lifts out there, by the ids its station data names them by. TfL's message is left unread: the
 * lifts are what the app walks the station's map without (SPEC *Step-free access*). The lifts have no
 * default: an entry without them is a response this code doesn't know, so it fails to decode rather
 * than read as no lifts out, which would put marks back on lifts TfL never said were working.
 */
@Serializable
data class TflLiftDisruptionDto(
    val stationUniqueId: String = "",
    val disruptedLiftUniqueIds: List<String>,
)
