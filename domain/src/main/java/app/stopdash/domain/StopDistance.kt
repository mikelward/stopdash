package app.stopdash.domain

import kotlin.math.roundToInt

/** The units a distance label is written in, named by its short unit; each has a long one. */
enum class DistanceSystem {
    /** Meters, then kilometers ("120 m", "0.6 km"). */
    METERS,

    /** Yards, then miles ("130 yd", "0.4 mi") — the UK's road units. */
    YARDS,

    /** Feet, then miles ("390 ft", "0.4 mi") — the US's. */
    FEET,
}

/**
 * The user's distance-units choice (Settings → *Distance units*). [AUTOMATIC], the default,
 * follows the phone's locale; the others pin a system whatever the locale says.
 */
enum class DistanceUnits {
    AUTOMATIC,
    METERS,
    YARDS,
    FEET,
    ;

    /**
     * The system to draw in. [localeSystem] is what the phone's locale measures in, worked out by
     * the caller (the platform owns that lookup), so this stays pure and JVM-testable.
     */
    fun resolve(localeSystem: DistanceSystem): DistanceSystem = when (this) {
        AUTOMATIC -> localeSystem
        METERS -> DistanceSystem.METERS
        YARDS -> DistanceSystem.YARDS
        FEET -> DistanceSystem.FEET
    }

    companion object {
        /** A stored name read back; anything unrecognized (a newer build's value) is [AUTOMATIC]. */
        fun fromStored(name: String?): DistanceUnits = entries.firstOrNull { it.name == name } ?: AUTOMATIC
    }
}

/** A unit a distance label is written in: the short ones in whole tens, the long ones ([tenths]) to a tenth. */
enum class DistanceUnit(val symbol: String, val tenths: Boolean) {
    METERS("m", false),
    KILOMETERS("km", true),
    YARDS("yd", false),
    FEET("ft", false),
    MILES("mi", true),
}

/**
 * A distance as its label reads it: [count] of [unit], whole up close ("120" m), or tenths of a long
 * unit farther out (6 for "0.6" km). The words are the caller's, in the app's language
 * ([StopDistance.label] is the English, for the bug report).
 */
data class DistanceReading(val unit: DistanceUnit, val count: Int) {
    /** The amount to write, as a long unit's tenths read: 0.6 for six tenths of a km. */
    val amount: Double get() = if (unit.tenths) count / 10.0 else count.toDouble()
}

/**
 * Reads a great-circle distance (meters, the near-me ranking's own input) for the short
 * label shown after a stop's name in its group header — "120 m", "0.6 km", "130 yd", "390 ft",
 * "0.4 mi". Pure and JVM-testable, mirroring [Countdown]'s plain-string labels; the system is
 * handed in, never read from a locale here.
 *
 * Only the near-me list carries a distance; the watched list is location-free and shows no
 * label (D1). Close up the label is in the short unit, rounded to the nearest 10 (a fix on top
 * of the stop reads "10 m", not "0 m"); farther out it is the long unit to one decimal. Meters
 * and yards switch at ~500 m ("0.5 km", "0.3 mi"); feet switch at a tenth of a mile, as US maps
 * do, so a four-digit foot count never appears. Rounding keeps the label from implying
 * precision the fix doesn't have.
 */
object StopDistance {
    private const val METERS_PER_YARD = 0.9144
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344

    fun reading(meters: Double, system: DistanceSystem = DistanceSystem.METERS): DistanceReading {
        val m = meters.coerceAtLeast(0.0)
        val (perShort, shortUnit, longFrom) = when (system) {
            // 495 m would round to "500 m"; from there the label is "0.5 km", so meters top out at 490.
            DistanceSystem.METERS -> Triple(1.0, DistanceUnit.METERS, 495.0)
            // The same ~500 m switch: yards top out at "540 yd", then "0.3 mi".
            DistanceSystem.YARDS -> Triple(METERS_PER_YARD, DistanceUnit.YARDS, 495.0)
            // 160 m is 525 ft, which would round to "530 ft" — past 0.1 mi, so it reads "0.1 mi".
            DistanceSystem.FEET -> Triple(METERS_PER_FOOT, DistanceUnit.FEET, 160.0)
        }
        if (m < longFrom) {
            val rounded = (m / perShort / 10).roundToInt() * 10
            return DistanceReading(shortUnit, rounded.coerceAtLeast(10))
        }
        val (long, longUnit) = when (system) {
            DistanceSystem.METERS -> m / 1000 to DistanceUnit.KILOMETERS
            DistanceSystem.YARDS, DistanceSystem.FEET -> m / METERS_PER_MILE to DistanceUnit.MILES
        }
        // Floored at a tenth so the long unit can never read "0.0".
        return DistanceReading(longUnit, (long * 10).roundToInt().coerceAtLeast(1))
    }

    /** [reading] in English ("120 m", "0.6 km"): the bug report's, which the maintainer reads. */
    fun label(meters: Double, system: DistanceSystem = DistanceSystem.METERS): String {
        val reading = reading(meters, system)
        val amount = if (reading.unit.tenths) reading.amount.toString() else reading.count.toString()
        return "$amount ${reading.unit.symbol}"
    }
}
