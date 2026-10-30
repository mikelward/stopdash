package app.stopdash.ui

import app.stopdash.domain.BundledStops
import app.stopdash.domain.Hubs
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StopArrivals
import java.io.BufferedReader
import java.io.StringReader

/**
 * A station model in which [records] are one station, sharing one code (as St Pancras's records are), for a list that
 * reads them as one ([StationRows]). Synthetic ids and an obviously-fake place; no user data.
 */
internal fun twinStations(vararg records: String): Hubs {
    val index = StationIndex(
        records.map { id ->
            IndexedStation(id, "Station $id", listOf("national-rail"), "HUBTWIN", 51.5, -0.12, icsCode = "1009001", station = "1009001:rail", modelHub = "HUBTWIN")
        } + IndexedStation("HUBTWIN", "Twin", listOf("national-rail")),
        revision = "twin",
    )
    val stops = BundledStops.read(BufferedReader(StringReader("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=twin\nL\t1\tbus\t1")))
    return requireNotNull(Hubs.of(index, stops))
}

/** [stops], with [records] filed under their station's least record, as the nearby lookup places them ([twinStations]). */
internal fun twinPlaced(stops: List<StopArrivals>, vararg records: String): List<StopArrivals> =
    stops.map { if (it.stopId in records) it.copy(clusterId = records.min()) else it }
