package app.stopdash.domain

import java.io.BufferedReader
import java.io.StringReader

/**
 * A synthetic station model ([Hubs]) around the obviously-fake (51.5, -0.12), as `HubsTest` builds it, for
 * the tests of where a ride gets off (SPEC *Privacy*: no real stop or neighborhood).
 */
internal object HillHubs {
    // Hill: a hub HUBEXA of a rail station (records 910GHILL and 910GHILLLL) and a tube station
    // (940GZZLUHIL), with a bus stop of two poles (area 490G000001); Vale, a station and hub of its own;
    // and an area 490G000005 the model split into two stops, each a hub of its own.
    private val index = StationIndex(
        listOf(
            IndexedStation("910GHILL", "Hill", listOf("national-rail"), "HUBEXA", 51.500, -0.120,
                icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("910GHILLLL", "Hill LL", listOf("national-rail"), "HUBEXA", 51.502, -0.120,
                icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("940GZZLUHIL", "Hill", listOf("tube"), "HUBEXA", 51.501, -0.121,
                icsCode = "1009002", station = "1009002:tube", modelHub = "HUBEXA"),
            IndexedStation("910GVALE", "Vale", listOf("national-rail"), latitude = 51.53, longitude = -0.12,
                station = "910GVALE", modelHub = "910GVALE"),
            IndexedStation("HUBEXA", "Hill", listOf("national-rail", "tube")),
        ),
        revision = "abc",
    )
    val model: Hubs = Hubs.of(
        index,
        BundledStops.read(
            BufferedReader(
                StringReader(
                    listOf(
                        "# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc",
                        "L\t1\tbus\t1",
                        "S\t490000001A\tB\tHill Station\t51.500500\t-0.120000\t490G000001\t\tA\t\t\t1\t1009001\t490G000001\t1009001:rail\tHUBEXA",
                        "S\t490000001B\tB\tHill Station\t51.500700\t-0.120000\t490G000001\t\tB\t\t\t1\t1009001\t490G000001\t1009001:rail\tHUBEXA",
                        "S\t490000005A\tB\tHigh Road\t51.520000\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005A\t\t490000005A",
                        "S\t490000005B\tB\tMill Lane\t51.520300\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005B\t\t490000005B",
                    ).joinToString("\n"),
                ),
            ),
        ),
    )!!
}
