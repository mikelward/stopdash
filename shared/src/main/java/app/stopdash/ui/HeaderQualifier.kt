package app.stopdash.ui

import app.stopdash.domain.StopQualifier

/**
 * The "heading to" arrow a label carries as plain text ("➔ Archway", "Victoria ➔ Warren Street").
 * Labels keep this character so they stay plain, testable strings; the phone app draws it as an
 * icon (`withArrowIcons`), the widget and the watch as the glyph.
 */
const val ARROW = "➔"

/**
 * A group header's **qualifier segment** — the title-case cue that follows the place name on the one
 * line header ("Platform 1", "Stop E", "Southbound", "➔ Archway"). "Stop" is reserved for a literal
 * pole letter; a compass reads as a bare direction word, a letter-less pole's "towards" as "➔ Archway". It
 * joins the place name via [groupHeaderJoin] (" – ", or a space before a destination's arrow); null
 * when the group carries no qualifier, so the header is the bare place name. Title case with no small-caps treatment, and it **drops the
 * direction/towards parenthetical** the old two-level sub-header showed — the compass/towards moves
 * into the [groupHeaderSpoken] label a screen reader hears instead (SPEC D8). Pure, so the mapping is
 * unit-testable apart from the composable.
 */
fun groupHeaderLabel(qualifier: StopQualifier?): String? = when (qualifier) {
    null -> null
    is StopQualifier.Platform -> "Platform ${qualifier.number}"
    is StopQualifier.Compass -> qualifier.label
    // uppercase() is locale-invariant (Turkish-ı safe); the letter reads the same case however TfL
    // supplied it.
    // "Stop" is reserved for a literal pole letter ("Stop E"). A compass bearing reads as a bare
    // direction word ("Southbound"), like the rail compass.
    is StopQualifier.BusStop -> "Stop ${qualifier.letter.uppercase()}"
    is StopQualifier.BusBearing -> bearingSpoken(qualifier.bearing.uppercase())
    // The first place the sign names ("Farringdon Or Holborn Circus" → "➔ Farringdon"), the arrow meaning
    // "heading to"; the whole of it is what a screen reader hears ([groupHeaderSpoken]).
    is StopQualifier.Towards -> "$ARROW ${towardsShort(qualifier.towards)}"
}

/**
 * The qualifier a bus pole is headed by, from its own stop data: its letter ("Stop D"), else the
 * "towards" on its sign ("➔ Farringdon"), else its compass bearing ("Westbound"), else null. The
 * same order the grouping heads a pole by (SPEC D8), for a surface that labels one pole on its own
 * (the watch's trip board).
 */
fun busPoleLabel(letter: String, towards: String, bearing: String): String? {
    letter.trim().ifEmpty { null }?.let { return groupHeaderLabel(StopQualifier.BusStop(it, towards.trim().ifEmpty { null })) }
    towards.trim().ifEmpty { null }?.let { return groupHeaderLabel(StopQualifier.Towards(it)) }
    return bearing.trim().ifEmpty { null }?.let { groupHeaderLabel(StopQualifier.BusBearing(it)) }
}

/** A bus pole's own cues for [busPoleLabels]: its letter, the "towards" on its sign, its compass bearing. */
data class BusPoleCues(val letter: String, val towards: String, val bearing: String)

/**
 * [busPoleLabel] for each of [poles], several boarding together: where two would read the same (two
 * letterless poles whose signs point the same way, facing apart), those fall back to their bearings
 * ("Westbound", "Eastbound"), so each pole's trains stay under a label of their own, as the grouping
 * keeps them apart (Codex P2, #492). A clash the bearings can't settle keeps the shared label.
 */
fun busPoleLabels(poles: List<BusPoleCues>): List<String?> {
    val labels = poles.map { busPoleLabel(it.letter, it.towards, it.bearing) }
    val clashing = labels.groupingBy { it }.eachCount().filter { (label, count) -> label != null && count > 1 }.keys
    return poles.mapIndexed { i, pole ->
        if (labels[i] !in clashing || pole.letter.isNotBlank()) return@mapIndexed labels[i]
        val bearings = poles.indices.filter { labels[it] == labels[i] }.map { poles[it].bearing.trim().uppercase() }
        if (bearings.any { it.isEmpty() } || bearings.toSet().size < bearings.size) labels[i]
        else groupHeaderLabel(StopQualifier.BusBearing(pole.bearing.trim()))
    }
}

/**
 * A group header's full one-line text — the place [name], then the qualifier joined by
 * [groupHeaderJoin] when there is one ("King's Cross St. Pancras – Platform 1", "Turnpike Lane ➔
 * Bank"), else the bare name. Shared by the in-app list, its platform view title, and the widget so
 * every surface titles a place the same way. The widget (Glance) and the watch draw the arrow as its
 * glyph; the app draws it as an icon.
 */
fun groupHeaderTitle(name: String, qualifier: StopQualifier?): String =
    groupHeaderLabel(qualifier)?.let { "$name${groupHeaderJoin(it)}$it" } ?: name

/**
 * What joins a place name to its qualifier [label]: " – ", or just a space before a destination,
 * whose arrow already joins them (maintainer, 2026-09-24).
 */
fun groupHeaderJoin(label: String): String = if (label.startsWith(ARROW)) " " else " – "

/**
 * The **spoken** form of a group's qualifier — what a screen reader hears in place of the visible
 * segment's glyphs, keeping the direction/towards the visible label drops ("Platform 1, Northbound",
 * "Stop G, towards Farringdon", "Southwest-bound"). Null when the group carries no qualifier. The
 * composable prepends the place name and appends the distance for the header's full announced label.
 */
fun groupHeaderSpoken(qualifier: StopQualifier?): String? = when (qualifier) {
    null -> null
    is StopQualifier.Platform ->
        qualifier.direction?.let { "Platform ${qualifier.number}, $it" } ?: "Platform ${qualifier.number}"
    is StopQualifier.Compass -> qualifier.label
    is StopQualifier.BusStop -> {
        // Trim TfL's " Or " so "Farringdon Or Holborn Circus" reads "Farringdon" — a short cue, not a
        // paragraph. uppercase() the letter so the spoken "Stop D" matches the display case.
        val towards = qualifier.towards?.substringBefore(" Or ")?.trim()?.ifEmpty { null }
        val letter = qualifier.letter.uppercase()
        towards?.let { "Stop $letter, towards $it" } ?: "Stop $letter"
    }
    is StopQualifier.BusBearing -> bearingSpoken(qualifier.bearing.uppercase())
    is StopQualifier.Towards -> "towards ${qualifier.towards.replace(" Or ", " or ")}"
}

/** A pole's "towards" cut to the first place it names: TfL joins several with " Or ". */
private fun towardsShort(towards: String): String = towards.substringBefore(" Or ").trim().ifEmpty { towards.trim() }

/** A bus pole's compass [bearing] as a direction word ("E" → "Eastbound"), else null when it names
 *  none of the eight compass points — the route page heads its stop list with it. */
fun bearingDirection(bearing: String): String? =
    if (bearing.uppercase() in COMPASS_BEARINGS) bearingSpoken(bearing) else null

private val COMPASS_BEARINGS = setOf("N", "E", "S", "W", "NE", "NW", "SE", "SW")

/** A compass bearing as a direction word ("E" → "Eastbound"): the visible header segment and what a
 *  screen reader hears, one and the same. Intercardinals get the hyphenated "-bound" form. */
private fun bearingSpoken(bearing: String): String = when (bearing.uppercase()) {
    "N" -> "Northbound"
    "E" -> "Eastbound"
    "S" -> "Southbound"
    "W" -> "Westbound"
    "NE" -> "Northeast-bound"
    "NW" -> "Northwest-bound"
    "SE" -> "Southeast-bound"
    "SW" -> "Southwest-bound"
    else -> bearing
}
