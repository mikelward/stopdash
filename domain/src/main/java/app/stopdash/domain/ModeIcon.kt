package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.util.Locale

/**
 * A stop's modes as its row shows them, an icon each (maintainer, 2026-10-13), in this order, trains
 * first: National Rail, the Overground and the Elizabeth line share [RAIL]. Worked out once, when a
 * [StationMatch] is made (off the main thread), so a row only reads it (AGENTS *Main thread*).
 */
enum class ModeIcon {
    TUBE, RAIL, DLR, TRAM, RIVER, CABLE_CAR, BUS;

    companion object {
        private val BY_MODE = mapOf(
            "tube" to TUBE,
            "national-rail" to RAIL,
            "overground" to RAIL,
            "elizabeth-line" to RAIL,
            "dlr" to DLR,
            "tram" to TRAM,
            "river-bus" to RIVER,
            "cable-car" to CABLE_CAR,
            "bus" to BUS,
        )

        /** One TfL mode id's icon, or null where none stands for it: one lookup, safe on any thread. */
        fun of(mode: String): ModeIcon? = BY_MODE[mode.lowercase(Locale.ROOT)]

        /** [modes]' icons, each once, in this enum's order. Work that grows with [modes]: off the main thread. */
        @WorkerThread
        fun of(modes: List<String>): List<ModeIcon> =
            modes.mapNotNull { BY_MODE[it.lowercase(Locale.ROOT)] }.distinct().sorted()

        /** The [modes] no icon stands for, each once, for the row to name. Off the main thread. */
        @WorkerThread
        fun others(modes: List<String>): List<String> =
            modes.filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in BY_MODE }.distinct()
    }
}

/**
 * A match's modes as a rider reads them, in TfL's order: "Tube · Bus". TfL's mode ids are lower-case
 * and hyphenated; the known ones get their proper names, any other is spaced and capitalized rather
 * than hidden. Work that grows with [modes]: a row reads [StationMatch.otherModesLabel] instead.
 */
@WorkerThread
fun modesLabel(modes: List<String>): String =
    modes.filter { it.isNotBlank() }.distinct().joinToString(" · ") { modeName(it) }

/** A TfL mode id's name ("elizabeth-line" → "Elizabeth"). */
fun modeName(mode: String): String =
    KNOWN_MODE_NAMES[mode.lowercase(Locale.ROOT)] ?: mode.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

private val KNOWN_MODE_NAMES = mapOf(
    "tube" to "Tube",
    "bus" to "Bus",
    "dlr" to "DLR",
    "overground" to "Overground",
    "elizabeth-line" to "Elizabeth",
    "national-rail" to "National Rail",
    "tram" to "Tram",
    "river-bus" to "River Bus",
    "cable-car" to "Cable car",
)
