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
 * carries either name. And the Elizabeth line is "Elizabeth", as the Tube's lines go by their names
 * alone, to keep every label short (maintainer, 2026-10-08).
 */
fun riderLineName(lineName: String, mode: String): String = when {
    lineName.trim().equals(ELIZABETH_LINE, ignoreCase = true) -> "Elizabeth"
    (mode.isBlank() || mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true)) &&
        lineName.lowercase().filter { it.isLetterOrDigit() } in LONDON_NORTHWESTERN_NAMES -> "London Northwestern Railway"
    else -> lineName
}

/**
 * Whether [serviceName] reads as "<name> line" — a tube or named Overground line, which TfL names
 * bare ("Victoria", "Mildmay"). A name that already says "line" or is the network
 * itself (London Overground) doesn't take it.
 */
fun takesLineSuffix(lineName: String, mode: String): Boolean {
    val name = lineName.trim()
    return mode.lowercase() in setOf("tube", "overground") &&
        !name.endsWith("line", ignoreCase = true) &&
        !name.contains("Overground", ignoreCase = true)
}

/** TfL's name for the Elizabeth line. */
private const val ELIZABETH_LINE = "Elizabeth line"

/** West Midlands Trains' line under TfL's name and the rail feed's, normalized. */
private val LONDON_NORTHWESTERN_NAMES = setOf("westmidlandstrains", "lnrwmr")

/** A trailing parenthesized alias, e.g. " (LNR)". */
private val TRAILING_ALIAS = Regex("""\s*\([^()]*\)$""")

/**
 * How a line is named on its own in a sentence, as "Hide ‹line›" and its banner say it: a tube or
 * named Overground line as "Northern line" ([takesLineSuffix]), in [line]'s words, anything else by its
 * TfL name ("134", "Elizabeth", "Thameslink"). [ENGLISH_LINE] by default, which is also how a hidden or
 * avoided line's entry keeps it ([HiddenModes.lineKey]): one saved form, whatever the app's language.
 */
fun lineLabel(lineName: String, mode: String, line: (name: String) -> String = ENGLISH_LINE): String =
    LineLabel.of(lineName, mode).text(line)

/**
 * A line as [lineLabel] names it, kept apart so a surface can say it in its own words without reading a
 * label back: TfL's [name] for it ("Northern", "134"), never translated, and whether it [takesLine] the
 * word for "line" ([takesLineSuffix]).
 */
data class LineLabel(val name: String, val takesLine: Boolean) {
    /** "Northern line" in [line]'s words; a name that takes no word as it is. */
    fun text(line: (name: String) -> String = ENGLISH_LINE): String = if (takesLine) line(name) else name

    companion object {
        /** Line [lineName] of [mode], as its riders know it ([riderLineName]). */
        fun of(lineName: String, mode: String): LineLabel {
            val name = riderLineName(lineName, mode).trim()
            return LineLabel(name, takesLineSuffix(name, mode))
        }
    }
}

/** A line's name with "line" ([lineLabel]) in English: "Northern" → "Northern line". */
val ENGLISH_LINE: (name: String) -> String = { "$it$LINE_SUFFIX" }

/**
 * A line's label as an entry keeps it ([lineLabel] in English), in [line]'s words: "Northern line" reads
 * as the app says a line, and a name without the suffix ("134", "Elizabeth") as it is. Renamed on the way
 * back in, like any saved name ([riderLineName]; the entry keeps no mode), so a line saved before a rename
 * isn't named the old way.
 */
fun savedLineLabel(saved: String, line: (name: String) -> String = ENGLISH_LINE): String {
    val label = riderLineName(saved, mode = "")
    val name = label.removeSuffix(LINE_SUFFIX)
    return if (name != label && name.isNotBlank()) line(name) else label
}

/** What [ENGLISH_LINE] adds to a line's name. */
private const val LINE_SUFFIX = " line"
