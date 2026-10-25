package app.stopdash.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import app.stopdash.R
import java.util.Locale

/**
 * A TfL mode's name in the app's language ("national-rail" → "National Rail"); a mode this build
 * doesn't know keeps its id tidied ([app.stopdash.domain.modeName]), as TfL gave it.
 */
internal fun modeName(mode: String, resources: Resources): String =
    MODE_NAMES[mode.lowercase(Locale.ROOT)]?.let { resources.getString(it) } ?: app.stopdash.domain.modeName(mode)

/**
 * A station's [modes] as a screen reader hears them on its row, in the app's language and TfL's order:
 * "Tube · Elizabeth · National Rail". Worked out where the row draws (2026-10-22; the maintainer left
 * where to the code): a station serves a handful of modes at most, so it's a fixed few lookups, not
 * work that grows with a list, and the words follow the language the row is drawn in. A station match
 * is made in places with no resources to say them (the station index, recents, favorites).
 */
internal fun modesSpoken(modes: List<String>, resources: Resources): String =
    modes.filter { it.isNotBlank() }.distinct().joinToString(" · ") { modeName(it, resources) }

/** [modesSpoken] for [modes], once per language and set of modes rather than on every recomposition. */
@Composable
internal fun rememberModesSpoken(modes: List<String>): String {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(modes, resources, configuration) { modesSpoken(modes, resources) }
}

/** The modes this build names, by TfL's id; the rest keep their tidied id. */
private val MODE_NAMES: Map<String, Int> = mapOf(
    "tube" to R.string.mode_name_tube,
    "bus" to R.string.mode_name_bus,
    "dlr" to R.string.mode_name_dlr,
    "overground" to R.string.mode_name_overground,
    "elizabeth-line" to R.string.mode_name_elizabeth_line,
    "national-rail" to R.string.mode_name_national_rail,
    "tram" to R.string.mode_name_tram,
    "river-bus" to R.string.mode_name_river_bus,
    "cable-car" to R.string.mode_name_cable_car,
    "coach" to R.string.mode_name_coach,
    "international-rail" to R.string.mode_name_international_rail,
    "replacement-bus" to R.string.mode_name_replacement_bus,
)

/**
 * The words a favorite ride's notes and a route page's timetable line name its vehicles by, by its mode's own
 * name (maintainer, 2026-10-25), as a trip on the way does: a bus, a coach, a tram, a boat, a cable car; a train
 * on every rail mode, and on one this build doesn't know.
 */
internal enum class ModeWords(
    val checking: Int,
    val none: Int,
    val notChecked: Int,
    val notCheckedNamed: Int,
    val nextOne: Int,
    val nextMany: Int,
) {
    TRAIN(
        R.string.journey_checking, R.string.journey_none, R.string.journey_not_checked, R.string.journey_not_checked_named,
        R.string.route_detail_next_timetabled, R.string.route_detail_next_trains_timetabled,
    ),
    BUS(
        R.string.journey_checking_bus, R.string.journey_none_bus, R.string.journey_not_checked_bus, R.string.journey_not_checked_bus_named,
        R.string.route_detail_next_bus_timetabled, R.string.route_detail_next_buses_timetabled,
    ),
    COACH(
        R.string.journey_checking_coach, R.string.journey_none_coach, R.string.journey_not_checked_coach, R.string.journey_not_checked_coach_named,
        R.string.route_detail_next_coach_timetabled, R.string.route_detail_next_coaches_timetabled,
    ),
    TRAM(
        R.string.journey_checking_tram, R.string.journey_none_tram, R.string.journey_not_checked_tram, R.string.journey_not_checked_tram_named,
        R.string.route_detail_next_tram_timetabled, R.string.route_detail_next_trams_timetabled,
    ),
    BOAT(
        R.string.journey_checking_boat, R.string.journey_none_boat, R.string.journey_not_checked_boat, R.string.journey_not_checked_boat_named,
        R.string.route_detail_next_boat_timetabled, R.string.route_detail_next_boats_timetabled,
    ),
    CABLE_CAR(
        R.string.journey_checking_cable_car, R.string.journey_none_cable_car, R.string.journey_not_checked_cable_car,
        R.string.journey_not_checked_cable_car_named, R.string.route_detail_next_cable_car_timetabled,
        R.string.route_detail_next_cable_cars_timetabled,
    ),
    ;

    companion object {
        /** By TfL's mode id, as the trip on the way names a leg's vehicle. */
        fun of(mode: String): ModeWords = when (mode.lowercase(Locale.ROOT)) {
            "bus", "replacement-bus" -> BUS
            "coach" -> COACH
            "tram" -> TRAM
            "river-bus", "river-tour" -> BOAT
            "cable-car" -> CABLE_CAR
            else -> TRAIN
        }
    }
}
