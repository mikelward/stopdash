package app.stopdash.ui

import android.content.res.Resources
import app.stopdash.shared.R

/**
 * A stop header's words in the app's language ([groupHeaderLabel], [groupHeaderSpoken]): a platform
 * ("Platform 2"), a bus pole's letter ("Stop E"), where a pole's buses go as a screen reader hears it
 * ("towards Farringdon"), and a pole's compass bearing as a direction word ("Eastbound"). [ENGLISH] for a
 * caller with no resources: a test, a diagnostic.
 */
class HeaderWords(
    private val platformOf: (number: String) -> String,
    private val stopOf: (letter: String) -> String,
    private val towardsOf: (place: String) -> String,
    // The eight compass points ("N", "NE", ...) as direction words.
    private val bearings: Map<String, String>,
) {
    /** "Platform 2". */
    fun platform(number: String): String = platformOf(number)

    /** "Stop E". */
    fun stop(letter: String): String = stopOf(letter)

    /** "towards Farringdon", as heard after a pole. */
    fun towards(place: String): String = towardsOf(place)

    /** [bearing] as a direction word ("E" → "Eastbound"), else null when it names none of the eight compass points. */
    fun bearing(bearing: String): String? = bearings[bearing.uppercase()]

    companion object {
        /** The English the app has always written. */
        val ENGLISH = HeaderWords(
            platformOf = { "Platform $it" },
            stopOf = { "Stop $it" },
            towardsOf = { "towards $it" },
            bearings = mapOf(
                "N" to "Northbound", "E" to "Eastbound", "S" to "Southbound", "W" to "Westbound",
                "NE" to "Northeast-bound", "NW" to "Northwest-bound", "SE" to "Southeast-bound", "SW" to "Southwest-bound",
            ),
        )
    }
}

/** A stop header's words ([HeaderWords]) in [resources]' language: "Platform 2", "Stop E", "Eastbound". */
fun headerWords(resources: Resources): HeaderWords = HeaderWords(
    platformOf = { resources.getString(R.string.header_platform, it) },
    stopOf = { resources.getString(R.string.header_stop, it) },
    towardsOf = { resources.getString(R.string.header_towards, it) },
    bearings = mapOf(
        "N" to resources.getString(R.string.header_bearing_n),
        "E" to resources.getString(R.string.header_bearing_e),
        "S" to resources.getString(R.string.header_bearing_s),
        "W" to resources.getString(R.string.header_bearing_w),
        "NE" to resources.getString(R.string.header_bearing_ne),
        "NW" to resources.getString(R.string.header_bearing_nw),
        "SE" to resources.getString(R.string.header_bearing_se),
        "SW" to resources.getString(R.string.header_bearing_sw),
    ),
)
