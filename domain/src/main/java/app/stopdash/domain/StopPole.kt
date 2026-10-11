package app.stopdash.domain

import java.util.Locale

/**
 * A bus pole's letter and compass bearing as TfL gives them, read one way whether they come from
 * TfL's live answer or the bundled stops ([BundledStops]). TfL is inconsistent about a pole's
 * compass: some poles carry it in CompassPoint ("S"), others jam it into stopLetter as an arrow
 * ("->N") in place of a real pole letter ("K"). An arrow-in-stopLetter is not a letter — it's
 * dropped and the compass taken as the bearing, so a compass-only pole renders the one way (the
 * bearing path, a direction word like "Northbound") whichever field TfL used, rather than a raw
 * "-> N" down the letter path beside a "Southbound" down the bearing path. A genuine pole letter
 * ("K") is alphanumeric and stays on the letter path ("Stop K", SPEC D8).
 */
object StopPole {
    /** [rawLetter] when it's a real pole letter, else blank. */
    fun letter(rawLetter: String): String = rawLetter.trim().takeIf(::isPoleLetter).orEmpty()

    /** The compass bearing: [compass], else the direction an arrow in [rawLetter] points. */
    fun bearing(rawLetter: String, compass: String): String {
        val raw = rawLetter.trim()
        return compass.trim().uppercase(Locale.ROOT)
            .ifBlank { if (isPoleLetter(raw)) "" else raw.filter { it.isLetter() }.uppercase(Locale.ROOT) }
    }

    private fun isPoleLetter(raw: String) = raw.isNotEmpty() && raw.all { it.isLetterOrDigit() }
}
