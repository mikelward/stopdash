package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.pointName
import kotlinx.serialization.Serializable

/**
 * TfL Journey Planner's **disambiguation** response — the HTTP 300 body it returns when it can't pin
 * an end of a trip to one place and offers look-alikes instead (SPEC D9's postcode resolver). Trimmed
 * to what a place candidate needs: each option's place name and coordinate. Only `fromLocation…` is
 * read — the postcode resolver disambiguates the `from` end (the `to` is a fixed, resolvable anchor).
 * Unknown fields are ignored ([kotlinx.serialization.json.Json] `ignoreUnknownKeys`).
 */
@Serializable
data class TflDisambiguationResultDto(
    val fromLocationDisambiguation: TflLocationDisambiguationDto = TflLocationDisambiguationDto(),
) {
    /** The offered places, in TfL's order, dropping any without a usable coordinate or name. */
    fun toCandidates(): List<PlaceCandidate> =
        fromLocationDisambiguation.disambiguationOptions.mapNotNull { it.place.toCandidateOrNull() }
}

@Serializable
data class TflLocationDisambiguationDto(
    val disambiguationOptions: List<TflDisambiguationOptionDto> = emptyList(),
    val matchStatus: String = "",
)

@Serializable
data class TflDisambiguationOptionDto(val place: TflDisambiguationPlaceDto = TflDisambiguationPlaceDto())

@Serializable
data class TflDisambiguationPlaceDto(
    val commonName: String = "",
    // Nullable so an omitted axis reads as absent; a present 0.0 is a real value (London is on the
    // prime meridian, so a valid longitude can be exactly 0.0).
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * A candidate from a disambiguation place, or null when it carries no usable coordinate or no name.
 * An axis TfL omits is missing; the exact pair (0,0) is its "unset" sentinel (the Gulf of Guinea,
 * never a real UK point) — but a longitude of 0.0 with a real latitude is kept (the prime meridian).
 */
fun TflDisambiguationPlaceDto.toCandidateOrNull(): PlaceCandidate? {
    val coordinate = coordinateOrNull(lat, lon) ?: return null
    val name = pointName(commonName).ifBlank { return null }
    return PlaceCandidate(name, coordinate)
}

/** A coordinate from a lat/lon pair, or null when an axis is missing or the pair is TfL's (0,0) unset. */
internal fun coordinateOrNull(lat: Double?, lon: Double?): Coordinates? {
    val latitude = lat ?: return null
    val longitude = lon ?: return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return Coordinates(latitude, longitude)
}

/**
 * The place a **resolved** (HTTP 200) journey started from — the origin of its first journey's first
 * leg, which for a postcode `from` is the postcode's own geocoded point. Null when no journey was
 * offered or the origin carries no coordinate. A one-item candidate list beside the disambiguation
 * path, so a postcode TfL resolves outright still offers the rider its place rather than "no results".
 */
fun TflJourneyResultsDto.resolvedOriginCandidate(): PlaceCandidate? {
    val origin = journeys.firstOrNull()?.legs?.firstOrNull()?.departurePoint ?: return null
    val coordinate = coordinateOrNull(origin.lat, origin.lon) ?: return null
    val name = pointName(origin.commonName).ifBlank { return null }
    return PlaceCandidate(name, coordinate)
}
