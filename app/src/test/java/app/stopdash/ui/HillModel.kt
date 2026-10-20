package app.stopdash.ui

import app.stopdash.domain.BundledStops
import app.stopdash.domain.Hubs
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.RideEnds
import app.stopdash.domain.StationIndex
import java.io.BufferedReader
import java.io.StringReader

/**
 * A synthetic station model ([Hubs]) around the obviously-fake (51.5, -0.12) (SPEC *Privacy*): Hill, a hub
 * HUBEXA of a rail station (910GHILL) and a tube station (940GZZLUHIL), and Vale, a station of its own; for
 * where the trip page's rides get off ([RideEnds]).
 */
internal object HillModel {
    val hubs: Hubs = Hubs.of(
        StationIndex(
            listOf(
                IndexedStation("910GHILL", "Hill", listOf("national-rail"), "HUBEXA", 51.500, -0.120,
                    icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
                IndexedStation("940GZZLUHIL", "Hill", listOf("tube"), "HUBEXA", 51.501, -0.121,
                    icsCode = "1009002", station = "1009002:tube", modelHub = "HUBEXA"),
                IndexedStation("910GVALE", "Vale", listOf("national-rail"), latitude = 51.53, longitude = -0.12,
                    station = "910GVALE", modelHub = "910GVALE"),
                IndexedStation("HUBEXA", "Hill", listOf("national-rail", "tube")),
            ),
            revision = "abc",
        ),
        BundledStops.read(
            BufferedReader(StringReader("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc\nL\t1\tbus\t1")),
        ),
    )!!

    /** A trip to Hill: its matchable hub. */
    val toHill = RideEnds.Destination(hubs, setOf("HUBEXA"))
}
