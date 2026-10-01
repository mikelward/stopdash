package app.stopdash.domain

/**
 * The lines a rider can change to at a stop on the route detail's stop list (SPEC D8, route
 * detail): the **rail-type** lines — tube, Overground, DLR, Elizabeth line, tram, and national
 * rail where TfL names it — at the station or its interchange, other than the line being ridden.
 *
 * **Buses are left out** — and so is any other non-rail mode (coach, river bus), by allowlist
 * ([RAIL_MODES]): nearly every station has several buses, and a chip per bus route would swamp
 * the list (maintainer, 2026-09-22 — "buses would swamp it"). A line's mode comes from its stop's
 * own mode when TfL gives that stop exactly one; an interchange mixes modes and names its lines
 * without saying which is which, so there a line counts only when its id is a known rail-type line
 * ([KNOWN_MODES]): the tube, Overground, DLR, Elizabeth line and tram lines, and the National Rail
 * operators TfL has a line for ([NATIONAL_RAIL_LINE_IDS]). Anything else there — a bus, an operator
 * not on that list — is never guessed at.
 */
object Connections {
    /** [lines] at a stop, reduced to the connections other than [ridingLineId], each with a mode. */
    fun of(lines: List<LineRef>, ridingLineId: String): List<LineRef> =
        lines.asSequence()
            .filter { it.id.isNotBlank() && it.id != ridingLineId }
            .mapNotNull { line -> modeOf(line)?.let { line.copy(mode = it) } }
            .distinctBy { it.id }
            .toList()

    private fun modeOf(line: LineRef): String? {
        val mode = line.mode.lowercase().ifBlank { KNOWN_MODES[line.id] } ?: return null
        return mode.takeIf { it in RAIL_MODES }
    }

    /**
     * Whether a line of [mode] (blank when TfL didn't say, then known by [lineId]) is rail-type — the
     * same allowlist as a connection, so a bus, coach or river-bus route never is.
     */
    fun isRail(mode: String, lineId: String): Boolean = modeOf(LineRef(lineId, "", mode)) != null

    /** A rail-type line's mode known from its id alone ("northern" → "tube"), else null. */
    fun knownMode(lineId: String): String? = KNOWN_MODES[lineId]

    /** The modes that count as a connection — an allowlist, so a bus, coach or river-bus never does. */
    private val RAIL_MODES = setOf("tube", "overground", "dlr", "elizabeth-line", "tram", "trams", "national-rail")

    /** Rail-type lines identifiable by id alone, for an interchange's mixed-mode line list. */
    private val KNOWN_MODES: Map<String, String> =
        listOf(
            "bakerloo", "central", "circle", "district", "hammersmith-city", "jubilee",
            "metropolitan", "northern", "piccadilly", "victoria", "waterloo-city",
        ).associateWith { "tube" } +
            listOf("lioness", "mildmay", "windrush", "weaver", "suffragette", "liberty", "london-overground")
                .associateWith { "overground" } +
            mapOf("dlr" to "dlr", "elizabeth" to "elizabeth-line", "tram" to "tram") +
            NATIONAL_RAIL_LINE_IDS.associateWith { "national-rail" }
}
