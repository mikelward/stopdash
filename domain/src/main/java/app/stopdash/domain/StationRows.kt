package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A list's rows as one station's records read together (SPEC *Grouped by station*). TfL lists some
 * stations under several records sharing one station code: St Pancras's high-speed platforms beside
 * its main record, Paddington's Elizabeth line record beside its main one. They're one place, each
 * placed by its station's code ([StationCodeStopFinder]); here each row is stamped with that code
 * ([DepartureRow.stationCode]), so what two records repeat is shown once:
 * - a train another record of the station already lists on its line is left out of the later row,
 *   and a row left with no trains goes (Paddington's Elizabeth line trains are under both records);
 * - a line's row with no trains (a status row, a quiet "?") goes where another record of the station has
 *   a row for that line: its trains, or its own status, say it there. The one National Rail board a
 *   station's records share is shown under one of them, so the other's empty "No data" row for a
 *   disrupted line said nothing new, under a second heading of the same name (maintainer, 2026-10-30).
 *
 * Rows come in the list's order, nearest first, and the first record to show a line keeps it. A row the rider
 * starred ([kept]) is never left out or thinned, and its trains are its own, so a star stays on the row it was
 * given to (Codex, #1242).
 */
object StationRows {
    @WorkerThread
    fun merged(
        rows: List<DepartureRow>,
        codeOf: (stopId: String) -> String?,
        kept: (DepartureRow) -> Boolean = { false },
    ): List<DepartureRow> {
        val codes = HashMap<String, String>()
        val stamped = rows.map { row ->
            val code = codes.getOrPut(row.stopId) { codeOf(row.stopId).orEmpty() }
            if (code == row.stationCode) row else row.copy(stationCode = code)
        }
        // Each station's lines, by which of its records shows each first: one with trains, else the first.
        val keeper = HashMap<Pair<String, String>, String>()
        val stopsOf = HashMap<Pair<String, String>, MutableSet<String>>()
        for (row in stamped) {
            if (row.stationCode.isEmpty() || row.stopDisruption != null) continue
            val line = row.stationCode to row.lineId
            stopsOf.getOrPut(line) { LinkedHashSet() }.add(row.stopId)
            if (row.hasTrainsListed()) keeper.putIfAbsent(line, row.stopId)
        }
        val starred = stamped.filterTo(HashSet()) { it.stationCode.isNotEmpty() && it.stopDisruption == null && kept(it) }
        if (stopsOf.values.none { it.size > 1 }) return stamped
        // The trains already shown on each station's line, by which record listed them first.
        val seen = HashMap<Pair<String, String>, MutableMap<String, String>>()
        // A starred row's trains are claimed first, so a nearer record's copy of one gives way to it.
        for (row in starred) {
            val shown = seen.getOrPut(row.stationCode to row.lineId) { HashMap() }
            row.upcoming.forEach { shown.putIfAbsent(trainKey(it), row.stopId) }
        }
        return stamped.mapNotNull { row ->
            if (row.stopDisruption != null || row in starred) return@mapNotNull row
            val line = row.stationCode to row.lineId
            val stops = stopsOf[line]
            if (stops == null || stops.size < 2) return@mapNotNull row
            if (!row.hasTrainsListed()) {
                val kept = keeper[line] ?: stops.first()
                return@mapNotNull row.takeIf { it.stopId == kept }
            }
            val shown = seen.getOrPut(line) { HashMap() }
            val trains = row.upcoming.filter { train ->
                val owner = shown.putIfAbsent(trainKey(train), row.stopId)
                owner == null || owner == row.stopId
            }
            when {
                trains.size == row.upcoming.size -> row
                trains.isEmpty() && row.untimed.isEmpty() -> null
                else -> row.copy(upcoming = trains, destination = trains.firstOrNull()?.destination ?: row.destination)
            }
        }
    }

    private fun DepartureRow.hasTrainsListed(): Boolean = upcoming.isNotEmpty() || untimed.isNotEmpty()

    // One train as two records list it: by its vehicle where TfL names one, else by where and when it goes.
    private fun trainKey(train: Departure): String =
        if (train.vehicleId.isNotBlank()) "v\u0001${train.vehicleId}\u0001${train.destination}"
        else "t\u0001${train.destination}\u0001${train.expectedArrival.epochSecond}"
}
