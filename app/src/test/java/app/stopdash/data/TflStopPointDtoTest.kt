package app.stopdash.data

import app.stopdash.domain.StationFacility
import app.stopdash.domain.StationFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The nearby-stop mapping's cluster-id rule (SPEC *Finding stops* / D8): a stop's cluster is TfL's
 * `stationNaptan` where it gives one, else the cleaned display name, so a station's poles group by
 * TfL's own cluster (reliable) but same-named poles with no assigned StopArea still merge by name.
 * Public infrastructure names only (SPEC *Privacy*).
 */
class TflStopPointDtoTest {

    @Test
    fun `clusterId is the stationNaptan when TfL gives one`() {
        val stop = TflStopPointDto(
            id = "490013767A",
            commonName = "Trafalgar Sq / Charing Cross Stn",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stationNaptan = "490G000804",
        ).toStopLocationOrNull()
        assertEquals("490G000804", stop?.clusterId)
    }

    @Test
    fun `clusterId falls back to the cleaned name when stationNaptan is blank`() {
        // Many bus poles carry no StopArea; same-named poles still merge by name so a junction
        // isn't split into duplicate headers.
        val stop = TflStopPointDto(
            id = "490000129D",
            commonName = "King's Cross Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stationNaptan = "",
        ).toStopLocationOrNull()
        // The fallback is the cleaned name (the same value as the display name), so two poles named
        // alike still merge; cleanStopName strips the " Station" suffix.
        assertEquals("King's Cross", stop?.clusterId)
        assertEquals(stop?.name, stop?.clusterId)
    }

    @Test
    fun `the stop letter, compass bearing, and towards are captured for the per-pole bus header`() {
        // TfL prints a bus pole's letter (`stopLetter` "D", "Stop D"), its bearing (a CompassPoint in
        // `additionalProperties`), and where it heads (a `Towards`); all feed the per-pole bus
        // sub-header ("Stop D (towards Farringdon)", SPEC D8).
        val stop = TflStopPointDto(
            id = "490000129D",
            commonName = "King's Cross Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stopLetter = "D",
            additionalProperties = listOf(
                TflAdditionalPropertyDto(key = "Towards", value = "Farringdon Or Holborn Circus"),
                TflAdditionalPropertyDto(key = "CompassPoint", value = "E"),
            ),
        ).toStopLocationOrNull()
        assertEquals("D", stop?.stopLetter)
        assertEquals("E", stop?.bearing)
        assertEquals("Farringdon Or Holborn Circus", stop?.towards)
    }

    @Test
    fun `an arrow in stopLetter is treated as the compass, not a pole letter`() {
        // TfL is inconsistent: some poles put the compass in CompassPoint, others jam it into
        // stopLetter as an arrow ("->N") in place of a real letter. An arrow-in-stopLetter is not a
        // pole letter — it drops to the bearing, so the pole renders the one way (a direction word
        // like "Northbound", the bearing path) rather than a raw letter beside a CompassPoint pole (SPEC D8).
        val stop = TflStopPointDto(
            id = "490000000N",
            commonName = "Example Road",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stopLetter = "->N",
            additionalProperties = listOf(TflAdditionalPropertyDto(key = "CompassPoint", value = "N")),
        ).toStopLocationOrNull()
        assertEquals("", stop?.stopLetter)
        assertEquals("N", stop?.bearing)
    }

    @Test
    fun `an arrow-only stopLetter with no CompassPoint still yields the bearing`() {
        // The same arrow form, but TfL omitted CompassPoint — the compass is recovered from the
        // arrow's letters so the pole still renders its direction, not a bare name.
        val stop = TflStopPointDto(
            id = "490000000S",
            commonName = "Example Road",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stopLetter = "->S",
        ).toStopLocationOrNull()
        assertEquals("", stop?.stopLetter)
        assertEquals("S", stop?.bearing)
    }

    @Test
    fun `a stop with no letter or compass point carries neither`() {
        val stop = TflStopPointDto(
            id = "940GZZLUKSX",
            commonName = "King's Cross St. Pancras Underground Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("tube"),
        ).toStopLocationOrNull()
        assertEquals("", stop?.stopLetter)
        assertEquals("", stop?.bearing)
        assertEquals("", stop?.towards)
    }

    @Test
    fun `a stop with no usable identity maps to null`() {
        assertNull(TflStopPointDto(id = "", naptanId = "", commonName = "Somewhere").toStopLocationOrNull())
    }

    @Test
    fun `a station's entrances are found under it in its interchange's tree`() {
        // TfL answers a station with its whole interchange; only the station's own point and its
        // entrances count, not a neighboring station's or a bus stop's.
        val hub = TflStopPointDto(
            id = "HUBEXA", stopType = "TransportInterchange", lat = 51.5, lon = -0.1,
            children = listOf(
                TflStopPointDto(id = "490000000001A", stopType = "NaptanPublicBusCoachTram", lat = 51.501, lon = -0.101),
                TflStopPointDto(
                    id = "940GZZLUEXA", stopType = "NaptanMetroStation", lat = 51.502, lon = -0.102,
                    children = listOf(
                        TflStopPointDto(id = "4900ZZLUEXA1", stopType = "NaptanMetroEntrance", lat = 51.503, lon = -0.103),
                        TflStopPointDto(id = "4900ZZLUEXA2", stopType = "NaptanRailEntrance", lat = 51.504, lon = -0.104),
                        TflStopPointDto(id = "9400ZZLUEXA1", stopType = "NaptanMetroPlatform", lat = 51.505, lon = -0.105),
                        // A position TfL left unset, or half set, is no place to be.
                        TflStopPointDto(id = "4900ZZLUEXA3", stopType = "NaptanMetroEntrance"),
                        TflStopPointDto(id = "4900ZZLUEXA4", stopType = "NaptanMetroEntrance", lat = 51.508),
                        TflStopPointDto(id = "4900ZZLUEXA5", stopType = "NaptanMetroEntrance", lon = -0.108),
                        // On the prime meridian, as London's stations can be: a real place.
                        TflStopPointDto(id = "4900ZZLUEXA6", stopType = "NaptanMetroEntrance", lat = 51.509, lon = 0.0),
                    ),
                ),
                TflStopPointDto(
                    id = "910GEXAMPL", stopType = "NaptanRailStation", lat = 51.506, lon = -0.106,
                    children = listOf(TflStopPointDto(id = "4900EXAMPL1", stopType = "NaptanRailEntrance", lat = 51.507, lon = -0.107)),
                ),
            ),
        )
        // The station's own point apart from its entrances: they're weighed apart (OnTheWay.atStation).
        assertEquals(
            app.stopdash.domain.StationPlaces(
                point = app.stopdash.domain.Coordinates(51.502, -0.102),
                entrances = listOf(
                    app.stopdash.domain.Coordinates(51.503, -0.103),
                    app.stopdash.domain.Coordinates(51.504, -0.104),
                    app.stopdash.domain.Coordinates(51.509, 0.0),
                ),
            ),
            hub.placesOf("940GZZLUEXA"),
        )
        assertEquals(app.stopdash.domain.StationPlaces(), hub.placesOf("940GZZLUNONE"))
        // As TfL sends it: an axis left out reads as absent, not as 0.0.
        val halfSet = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(TflStopPointDto.serializer(), """{"id": "4900ZZLUEXA7", "stopType": "NaptanMetroEntrance", "lat": 51.5}""")
        assertNull(halfSet.lon)
        assertEquals(app.stopdash.domain.StationPlaces(), halfSet.placesOf("4900ZZLUEXA7"))
    }

    @Test
    fun `TfL's West Midlands Trains line comes in named as London Northwestern`() {
        // TfL names the line after the parent company; its route is London Northwestern's.
        val stop = TflStopPointDto(
            id = "910GEXAMPLE",
            commonName = "Example Rail Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("national-rail"),
            lines = listOf(TflStopLineDto("west-midlands-trains", "West Midlands Trains")),
            lineModeGroups = listOf(TflLineModeGroupDto("national-rail", listOf("west-midlands-trains"))),
        ).toStopLocationOrNull()
        assertEquals("London Northwestern Railway", stop?.lines?.single()?.name)
    }

    @Test
    fun `a station's fare zone is its own Zone property`() {
        val station = TflStopPointDto(
            id = "940GZZLUOXC",
            commonName = "Oxford Circus Underground Station",
            additionalProperties = listOf(TflAdditionalPropertyDto("Geo", "Zone", "1")),
        )
        assertEquals("1", station.fareZone("940GZZLUOXC"))
    }

    @Test
    fun `an interchange answering for one of its stations gives the interchange's zone`() {
        // TfL answers /StopPoint/940GZZLUSTD with Stratford's interchange record, zoned "2/3".
        val hub = TflStopPointDto(
            id = "HUBSRA",
            commonName = "Stratford",
            additionalProperties = listOf(TflAdditionalPropertyDto("Geo", "Zone", "2/3")),
            children = listOf(TflStopPointDto(id = "940GZZLUSTD", commonName = "Stratford Underground Station")),
        )
        assertEquals("2/3", hub.fareZone("940GZZLUSTD"))
    }

    @Test
    fun `a zone only on the asked station in the tree is found there`() {
        val hub = TflStopPointDto(
            id = "HUBSRA",
            commonName = "Stratford",
            children = listOf(
                TflStopPointDto(
                    id = "940GZZLUSTD",
                    commonName = "Stratford Underground Station",
                    additionalProperties = listOf(TflAdditionalPropertyDto("Geo", "Zone", "2/3")),
                ),
            ),
        )
        assertEquals("2/3", hub.fareZone("940GZZLUSTD"))
    }

    @Test
    fun `a stop with no Zone property has none`() {
        val pole = TflStopPointDto(
            id = "490000129D",
            commonName = "King's Cross Station",
            additionalProperties = listOf(TflAdditionalPropertyDto("Direction", "Towards", "Euston")),
        )
        assertEquals("", pole.fareZone("490000129D"))
    }

    private val kingsCross by lazy {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<TflStopPointDto>(
            checkNotNull(javaClass.getResource("/fixtures/stoppoint_hubkgx.json")).readText(),
        )
    }

    @Test
    fun `a station's facilities are those TfL says it has, Wi-Fi left out`() {
        // TfL's recorded King's Cross St. Pancras tube station: no toilets of its own, an accessible one
        // at National Rail, nine cash machines, a taxi rank, and Wi-Fi, which isn't read.
        val tube = kingsCross.stationFacts("940GZZLUKSX")
        assertEquals("1", tube.zone)
        assertEquals(
            listOf(StationFacility.ACCESSIBLE_TOILET, StationFacility.CASH_MACHINE, StationFacility.TAXI_RANK),
            tube.facilities,
        )
        assertEquals("National Rail", tube.toiletNote)
        // The National Rail station beside it says yes to toilets, a waiting room, a car park and a cash
        // machine, and no to left luggage.
        assertEquals(
            listOf(StationFacility.TOILETS, StationFacility.WAITING_ROOM, StationFacility.CAR_PARK, StationFacility.CASH_MACHINE),
            kingsCross.stationFacts("910GKNGX").facilities,
        )
    }

    @Test
    fun `a "no" is never a facility, and a count of none isn't a cash machine`() {
        val station = TflStopPointDto(
            id = "940GZZLUTCR",
            additionalProperties = listOf(
                TflAdditionalPropertyDto("Facility", "Toilets", "no"),
                TflAdditionalPropertyDto("Facility", "Waiting Room", "no"),
                TflAdditionalPropertyDto("Facility", "Cash Machines", "0"),
                TflAdditionalPropertyDto("Facility", "WiFi", "no"),
                TflAdditionalPropertyDto("Accessibility", "Toilet", "No"),
                TflAdditionalPropertyDto("Accessibility", "ToiletNote", "(National Rail)"),
            ),
        )
        val facts = station.stationFacts("940GZZLUTCR")
        assertEquals(emptyList<StationFacility>(), facts.facilities)
        // No accessible toilet, so no note about one.
        assertEquals("", facts.toiletNote)
    }

    @Test
    fun `a station with no facilities of its own takes its interchange's`() {
        val hub = TflStopPointDto(
            id = "HUBSRA",
            additionalProperties = listOf(TflAdditionalPropertyDto("Facility", "Toilets", "yes")),
            children = listOf(TflStopPointDto(id = "940GZZLUSTD")),
        )
        assertEquals(listOf(StationFacility.TOILETS), hub.stationFacts("940GZZLUSTD").facilities)
    }

    @Test
    fun `a long toilet note is left out`() {
        val station = TflStopPointDto(
            id = "940GZZLUEUS",
            additionalProperties = listOf(
                TflAdditionalPropertyDto("Accessibility", "Toilet", "Yes"),
                TflAdditionalPropertyDto("Accessibility", "ToiletNote", "Located on the concourse beside the ticket office, ask staff"),
            ),
        )
        val facts = station.stationFacts("940GZZLUEUS")
        assertEquals(listOf(StationFacility.ACCESSIBLE_TOILET), facts.facilities)
        assertEquals("", facts.toiletNote)
    }

    @Test
    fun `a bus stop has no facilities`() {
        val pole = TflStopPointDto(
            id = "490000129D",
            additionalProperties = listOf(TflAdditionalPropertyDto("Direction", "Towards", "Euston")),
        )
        assertEquals(StationFacts(), pole.stationFacts("490000129D"))
    }

    @Test
    fun `a station's own zone wins over its interchange's, and NA is no zone`() {
        // TfL's recorded King's Cross St. Pancras: the interchange is zone 1, St Pancras's high-speed
        // station inside it "NA", as zone fares don't run there.
        val hub = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<TflStopPointDto>(
            checkNotNull(javaClass.getResource("/fixtures/stoppoint_hubkgx.json")).readText(),
        )
        assertEquals("1", hub.fareZone("940GZZLUKSX"))
        // "NA" is TfL's answer for that station: no zone, never "Zone NA" or the interchange's 1.
        assertEquals("", hub.fareZone("910GSTPADOM"))
    }

    @Test
    fun `the asked station's zone is taken before the record's`() {
        val hub = TflStopPointDto(
            id = "HUBX",
            commonName = "Somewhere",
            additionalProperties = listOf(TflAdditionalPropertyDto("Geo", "Zone", "1")),
            children = listOf(
                TflStopPointDto(
                    id = "910GX",
                    commonName = "Somewhere Rail Station",
                    additionalProperties = listOf(TflAdditionalPropertyDto("Geo", "Zone", "2")),
                ),
            ),
        )
        assertEquals("2", hub.fareZone("910GX"))
    }

    @Test
    fun `a station outside the zones has none`() {
        val station = TflStopPointDto(
            id = "910GX",
            commonName = "Somewhere Rail Station",
            additionalProperties = listOf(TflAdditionalPropertyDto("Geo", "Zone", "NA")),
        )
        assertEquals("", station.fareZone("910GX"))
    }
}
