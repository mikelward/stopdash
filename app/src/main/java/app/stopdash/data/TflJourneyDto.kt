package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.TripFare
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.pointName
import app.stopdash.domain.riderLineName
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/**
 * TfL Journey Planner's `/Journey/JourneyResults/{from}/to/{to}` response, trimmed to what a trip
 * needs (SPEC *Trips with a change*): each journey's legs, with their line, ends, timetable times,
 * the stops each ride calls at, and the change time after it, and each journey's fare. Geometry and
 * instructions are ignored ([kotlinx.serialization.json.Json] `ignoreUnknownKeys`).
 */
@Serializable
data class TflJourneyResultsDto(val journeys: List<TflJourneyDto> = emptyList()) {
    /**
     * [now] places a London time the clocks' autumn rollback makes ambiguous ([londonTime]);
     * [stationOf] names the station a train's platform is at ([TflJourneyPointDto.stopId]).
     */
    fun toRoutes(now: Instant = Instant.now(), stationOf: (String) -> String? = { null }): List<TripRoute> =
        journeys.mapNotNull { it.toRouteOrNull(now, stationOf) }
}

@Serializable
data class TflJourneyDto(val legs: List<TflJourneyLegDto> = emptyList(), val fare: TflJourneyFareDto? = null) {
    /** Null for a journey with a leg that can't be read, so a half-understood route is never shown. */
    fun toRouteOrNull(now: Instant = Instant.now(), stationOf: (String) -> String? = { null }): TripRoute? {
        // Each time is read after the one before it (and its change time), so a journey across the
        // autumn rollback runs forward rather than jump back an hour.
        var after: Instant? = null
        val legs = legs.map { dto ->
            dto.toLegOrNull(now, after, stationOf)?.also { after = it.arrival.plus(it.changeAfter) } ?: return null
        }
        return TripRoute(legs, fare?.toFareOrNull()).takeIf { legs.isNotEmpty() }
    }
}

/**
 * A journey's `fare`: [totalCost] in pence for the whole journey, Hopper discounts already taken off,
 * each part's [fares] and the Planner's [caveats] about them. Absent where the Planner can't price the
 * journey (one leaving the pay as you go area).
 */
@Serializable
data class TflJourneyFareDto(
    val totalCost: Int? = null,
    val fares: List<TflFareDto> = emptyList(),
    val caveats: List<TflFareCaveatDto> = emptyList(),
) {
    /**
     * The route's [TripFare], or null where there's no price to stand behind: none given, or 0, which
     * StopDash won't show as a free ride it can't vouch for.
     */
    fun toFareOrNull(): TripFare? {
        val pence = totalCost?.takeIf { it > 0 } ?: return null
        // A level only where every part of the route is charged at it: a bus part has none, and a route
        // charged peak on one part and off-peak on another has no one level to name.
        val levels = fares.map { it.chargeLevel.trim().lowercase().replace('-', ' ') }.distinct()
        val level = when (levels.singleOrNull()) {
            "peak" -> TripFare.Level.PEAK
            "off peak" -> TripFare.Level.OFF_PEAK
            else -> null
        }
        return TripFare(
            pence = pence,
            level = level,
            pinkReader = caveats.any { it.type.equals(PINK_READER, ignoreCase = true) },
        )
    }

    private companion object {
        const val PINK_READER = "pinkReader"
    }
}

@Serializable
data class TflFareDto(val chargeLevel: String = "")

@Serializable
data class TflFareCaveatDto(val type: String = "")

@Serializable
data class TflJourneyLegDto(
    val departureTime: String = "",
    val arrivalTime: String = "",
    val departurePoint: TflJourneyPointDto = TflJourneyPointDto(),
    val arrivalPoint: TflJourneyPointDto = TflJourneyPointDto(),
    val routeOptions: List<TflJourneyRouteOptionDto> = emptyList(),
    val mode: TflJourneyIdentifierDto = TflJourneyIdentifierDto(),
    val path: TflJourneyPathDto = TflJourneyPathDto(),
    val interChangeDuration: String? = null,
    val interChangePosition: String? = null,
) {
    fun toLegOrNull(now: Instant = Instant.now(), after: Instant? = null, stationOf: (String) -> String? = { null }): TripLeg? {
        val modeId = mode.id.ifBlank { return null }
        val walk = modeId.equals(TripLeg.WALKING, ignoreCase = true)
        val departure = londonTime(departureTime, now, after) ?: return null
        // A ride takes time, so an arrival reading the same wall-clock as its departure in the
        // repeated hour is the later one; a walk can take none.
        val arrival = londonTime(arrivalTime, now, departure, strictlyAfter = !walk) ?: return null
        val line = routeOptions.firstOrNull()?.lineIdentifier
        // A ride with no line to follow can't be timed from live trains or checked for status.
        if (!walk && line?.id.isNullOrBlank()) return null
        val fromId = departurePoint.stopId(stationOf)
        val toId = arrivalPoint.stopId(stationOf)
        // Nor one with an end the Planner didn't name: its trains can't be fetched or checked to call there.
        if (!walk && (fromId == null || toId == null)) return null
        val change = interChangeDuration?.trim()?.toLongOrNull()
            ?.takeIf { interChangePosition.equals("AFTER", ignoreCase = true) }
        return TripLeg(
            mode = modeId,
            lineId = line?.id.orEmpty(),
            // Named as a rider knows it, so directions say what the pill does (SPEC *Line pill colors*).
            lineName = riderLineName(line?.name.orEmpty(), modeId),
            fromId = fromId.orEmpty(),
            fromName = pointName(departurePoint.commonName),
            toId = toId.orEmpty(),
            toName = pointName(arrivalPoint.commonName),
            departure = departure,
            arrival = arrival,
            path = path.stopPoints.filter { it.id.isNotBlank() }.map { it.id },
            pathNames = path.stopPoints.filter { it.id.isNotBlank() }.map { pointName(it.name) },
            changeAfter = Duration.ofMinutes(change ?: 0),
            headings = routeOptions.firstOrNull()?.directions.orEmpty()
                .map { signedName(it) }.filter { it.isNotBlank() }.distinct(),
            fromArea = departurePoint.stopPair(),
            toArea = arrivalPoint.stopPair(),
            fromAt = departurePoint.at(),
            // A ride's stop only: a walk's end can be the rider's own place (a favorite, a postcode),
            // which is theirs and isn't kept with the trip (Codex, PR #359).
            toAt = if (walk) null else arrivalPoint.at(),
        )
    }
}

/**
 * A service's terminus as the front of the train or bus shows it: a stop name cleaned
 * ([cleanStopName]), without the branch the Planner names after it ("High Barnet Station via Charing
 * Cross" is "High Barnet"), and a bus station by its place, as its buses' blinds read ("London Bridge
 * Bus Station" is "London Bridge").
 */
private fun signedName(terminus: String): String {
    val name = terminus.substringBefore(" via ").trim().ifBlank { terminus.trim() }
    val place = if (name.endsWith(BUS_STATION, ignoreCase = true)) name.dropLast(BUS_STATION.length).trim() else name
    return cleanStopName(place.ifBlank { name })
}

private const val BUS_STATION = " Bus Station"


@Serializable
data class TflJourneyPointDto(
    val naptanId: String? = null,
    val commonName: String = "",
    // The one stop within [naptanId]: for a bus, the pole the rider stands at.
    val individualStopId: String? = null,
    // The point's coordinate, present on a Planner point (a resolved postcode's origin carries it).
    // Nullable so an omitted axis reads as absent — never confused with a real 0.0 (London sits on the
    // prime meridian, so a valid longitude can be exactly 0.0); the postcode resolver reads these.
    val lat: Double? = null,
    val lon: Double? = null,
) {
    /** Where the Planner places the stop, when it does. */
    fun at(): Coordinates? = if (lat != null && lon != null) Coordinates(lat, lon) else null

    /** The stop pair ("490G…") the Planner names a bus stop by, or empty for any other stop. */
    fun stopPair(): String = naptanId?.takeIf { it.startsWith(STOP_PAIR_PREFIX) }.orEmpty()

    /**
     * The stop a leg boards or leaves at, as the live feed knows it. The Planner names a bus leg's
     * ends by their stop pair ("490G…", both of a road's poles), which TfL gives no arrivals for, and
     * sometimes by nothing; the pole the rider stands at is its [individualStopId]. Any other stop
     * (a station) goes by its [naptanId], or, where the Planner names a train's end by its platform
     * alone ("9100LIVSTLL1", no [naptanId]), by the station [stationOf] places that platform at.
     * Null when none names one.
     */
    fun stopId(stationOf: (String) -> String? = { null }): String? {
        val pole = individualStopId?.takeIf { it.startsWith(BUS_STOP_PREFIX) && !it.startsWith(STOP_PAIR_PREFIX) }
        val id = naptanId?.takeIf { it.isNotBlank() }
        if (pole != null && (id == null || id.startsWith(STOP_PAIR_PREFIX))) return pole
        return id ?: individualStopId?.takeIf { it.isNotBlank() }?.let(stationOf)
    }

    private companion object {
        const val BUS_STOP_PREFIX = "490"
        const val STOP_PAIR_PREFIX = "490G"
    }
}

@Serializable
data class TflJourneyRouteOptionDto(
    val lineIdentifier: TflJourneyIdentifierDto? = null,
    // The terminus the service runs to ("Stanmore Underground Station"), one or more.
    val directions: List<String> = emptyList(),
)

@Serializable
data class TflJourneyIdentifierDto(val id: String = "", val name: String = "")

@Serializable
data class TflJourneyPathDto(val stopPoints: List<TflJourneyIdentifierDto> = emptyList())

// The Planner gives London wall-clock times with no zone.
private val LONDON: ZoneId = ZoneId.of("Europe/London")

// Null for a time that can't be read: its leg, and so its route, is dropped (the client logs how many).
// In the hour the autumn rollback repeats, a wall-clock time is two instants: the earlier one unless
// it falls before [after] — the journey's previous time, or for a journey's first time about [now],
// since the Planner offers journeys from now on — then the later; with [strictlyAfter], also when it
// equals [after].
internal fun londonTime(text: String, now: Instant, after: Instant? = null, strictlyAfter: Boolean = false): Instant? {
    val local = try {
        LocalDateTime.parse(text)
    } catch (e: DateTimeParseException) {
        return null
    }
    val zoned = local.atZone(LONDON)
    val earlier = zoned.withEarlierOffsetAtOverlap().toInstant()
    val later = zoned.withLaterOffsetAtOverlap().toInstant()
    if (earlier == later) return earlier
    // A minute's grace for the Planner's whole-minute times.
    val floor = after ?: now.minus(Duration.ofMinutes(1))
    return if (earlier.isBefore(floor) || (strictlyAfter && after != null && earlier == after)) later else earlier
}
