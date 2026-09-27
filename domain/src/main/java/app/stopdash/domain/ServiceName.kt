package app.stopdash.domain

/**
 * The service's full name, shown at the top of the route detail page so a short pill code (LNWR,
 * AWC, HAM) can always be looked up; `null` when the pill already says it all (a bus number, DLR,
 * RB1), so the page doesn't repeat it. A trailing bracketed alias ("… Railway (LNR)") is dropped:
 * the pill already carries the short form, and a second, different abbreviation beside it only
 * confuses.
 */
fun serviceName(lineName: String, mode: String): String? {
    val name = lineName.trim().replace(TRAILING_ALIAS, "").trim()
    return if (name.isEmpty() || name.equals(lineCode(lineName, mode), ignoreCase = true)) null else name
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

/** A trailing parenthesized alias, e.g. " (LNR)". */
private val TRAILING_ALIAS = Regex("""\s*\([^()]*\)$""")

/**
 * How a line is named on its own in a sentence, as "Hide ‹line›" and its banner say it: a tube or
 * named Overground line as "Northern line" ([takesLineSuffix]), anything else by its TfL name ("134",
 * "Elizabeth line", "Thameslink").
 */
fun lineLabel(lineName: String, mode: String): String {
    val name = lineName.trim()
    return if (takesLineSuffix(name, mode)) "$name line" else name
}
