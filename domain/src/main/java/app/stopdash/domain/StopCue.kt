package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * What a stop's details look up for the line under From and To (SPEC *Finding a line*): a bus stop's
 * pole (its letter and the way its buses go), a station's fare zone, or nothing (a river bus pier, a
 * cable car station: no zone).
 */
enum class StopCue { POLE, ZONE, NONE }

/** The modes whose stations TfL gives a fare zone: no river bus pier or cable car station has one. */
val ZONED_MODES = setOf("tube", "dlr", "overground", "elizabeth-line", "tram", "national-rail")

/** The cue for a stop on the map of a line of [mode]: a lookup, no walk, so a composable may ask it. */
fun lineStopCue(mode: String): StopCue {
    val lower = mode.lowercase()
    return when {
        lower in ZONED_MODES -> StopCue.ZONE
        lower == "bus" -> StopCue.POLE
        else -> StopCue.NONE
    }
}

/**
 * The cue for a station of [modes] (the index's): a zone where any is zoned, a pole for a bus stop,
 * else nothing. Walks [modes], so it's worked out where the station is built, on the worker.
 */
@WorkerThread
fun stopCueOf(modes: List<String>): StopCue {
    val lower = modes.map { it.lowercase() }
    return when {
        lower.any { it in ZONED_MODES } -> StopCue.ZONE
        "bus" in lower -> StopCue.POLE
        else -> StopCue.NONE
    }
}
