package app.stopdash.domain

/**
 * The service's full name, shown at the top of the route detail page so a short pill code (LNR,
 * AWC, HAM) can always be looked up; `null` when the pill already says it all (a bus number, DLR,
 * RB1), so the page doesn't repeat it. A trailing bracketed alias ("… Railway (LNR)") is dropped:
 * the pill already carries the short form.
 */
fun serviceName(lineName: String, mode: String, lineId: String = ""): String? {
    val name = riderLineName(lineName, mode).trim().replace(TRAILING_ALIAS, "").trim()
    return if (name.isEmpty() || name.equals(lineCode(lineName, mode, lineId), ignoreCase = true)) null else name
}

/**
 * The name a rider knows a service by, applied where names come into the app (from TfL, the rail
 * feed and a saved copy) so every title, direction and spoken label agrees with the pill: the
 * source's own name, except for West Midlands Trains' line. TfL names it after the parent company
 * and the rail feed "LNR & WMR" for both its brands, but every train it runs from London is
 * publicly branded London Northwestern Railway, and its pill reads LNR (maintainer, 2026-09-30;
 * SPEC *Line pill colors*). For National Rail, or a line whose mode TfL didn't say (an interchange
 * lists its lines without one, and a hidden line's entry keeps none): no line of any other mode
 * carries either name.
 */
fun riderLineName(lineName: String, mode: String): String =
    if ((mode.isBlank() || mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true)) &&
        lineName.lowercase().filter { it.isLetterOrDigit() } in LONDON_NORTHWESTERN_NAMES
    ) {
        "London Northwestern Railway"
    } else {
        lineName
    }

/**
 * Whether [serviceName] reads as "<name> line" — a tube or named Overground line, which TfL names
 * bare ("Victoria", "Mildmay"). A name that already says "line" (Elizabeth line) or is the network
 * itself (London Overground) doesn't take it.
 */
fun takesLineSuffix(lineName: String, mode: String): Boolean {
    val name = lineName.trim()
    return mode.lowercase() in setOf("tube", "overground") &&
        !name.endsWith("line", ignoreCase = true) &&
        !name.contains("Overground", ignoreCase = true)
}

/** West Midlands Trains' line under TfL's name and the rail feed's, normalized. */
private val LONDON_NORTHWESTERN_NAMES = setOf("westmidlandstrains", "lnrwmr")

/** A trailing parenthesized alias, e.g. " (LNR)". */
private val TRAILING_ALIAS = Regex("""\s*\([^()]*\)$""")

/**
 * How a line is named on its own in a sentence, as "Hide ‹line›" and its banner say it: a tube or
 * named Overground line as "Northern line" ([takesLineSuffix]), anything else by its TfL name ("134",
 * "Elizabeth line", "Thameslink").
 */
fun lineLabel(lineName: String, mode: String): String {
    val name = riderLineName(lineName, mode).trim()
    return if (takesLineSuffix(name, mode)) "$name line" else name
}
