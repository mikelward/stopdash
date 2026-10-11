package app.stopdash.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Directions on foot (SPEC *On the way*): the bearing from where the rider is to where their walk
 * ends, as the eight compass points a card names ("NE"), and how far the phone must turn to face
 * it. Pure math on coordinates and angles the caller supplies, so it is JVM-testable.
 */
object Compass {
    /** The eight points, clockwise from north, as the card and the status-bar chip write them. */
    enum class Point { N, NE, E, SE, S, SW, W, NW }

    /**
     * The initial great-circle bearing from the first point to the second, in degrees clockwise from
     * true north, in [0, 360). Over a walk's few hundred meters it is the straight line the distance
     * left is measured along ([NearestStops.distanceMeters]).
     */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** The compass point nearest [bearing] (degrees from north), each covering 45°: 22.5° is NE. */
    fun point(bearing: Double): Point = Point.entries[(normalize(bearing) / 45.0).roundToInt() % 8]

    /**
     * How far, in degrees, the phone facing [heading] must turn to face [bearing]: clockwise positive,
     * in (-180, 180], so an arrow drawn rotated by it points at the walk's end.
     */
    fun relative(bearing: Double, heading: Double): Double {
        val d = normalize(bearing - heading)
        return if (d > 180.0) d - 360.0 else d
    }

    /**
     * Which way the screen points, degrees clockwise from north, from a rotation matrix in screen
     * coordinates (row-major, device to world, world axes east, north, up; already remapped for the
     * display's turn): the screen's top where the phone lies flatter than 45°, else the direction its
     * back faces. The top alone points at the sky as the phone is raised upright, where its heading is
     * undefined and swings (Codex, #775); held normally, tilted toward the rider, both point the same
     * way, so the switch doesn't jump.
     */
    fun screenAzimuth(matrix: FloatArray): Double {
        // A device axis in world coordinates is the matrix's column: the screen's top is the y axis, its
        // back the negative z axis.
        val upEast = matrix[1].toDouble()
        val upNorth = matrix[4].toDouble()
        val backEast = -matrix[2].toDouble()
        val backNorth = -matrix[5].toDouble()
        val (east, north) = if (upEast * upEast + upNorth * upNorth >= backEast * backEast + backNorth * backNorth) {
            upEast to upNorth
        } else {
            backEast to backNorth
        }
        return normalize(Math.toDegrees(atan2(east, north)))
    }

    /**
     * Whether a compass reading whose own estimated error is [errorRadians] (the rotation-vector
     * sensor's fifth value; null or negative where the sensor doesn't say) is steady enough to point
     * with: within [MAX_HEADING_ERROR_DEGREES]. Near steel or a magnet the error grows while the
     * sensor still calls itself accurate (Codex, #775).
     */
    fun headingUsable(errorRadians: Float?): Boolean =
        errorRadians == null || errorRadians < 0f || Math.toDegrees(errorRadians.toDouble()) <= MAX_HEADING_ERROR_DEGREES

    /** The most a reading may be off and still draw the arrow: more and it could point down the wrong street. */
    const val MAX_HEADING_ERROR_DEGREES = 30.0

    /** [degrees] in [0, 360). */
    fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}

/**
 * Steadies the phone's compass for an arrow in the hand: each reading moves the heading a [share]
 * of the way toward it, the shorter way round, and a change smaller than [step] degrees isn't
 * passed on, so the arrow neither jitters nor redraws for a wobble. One per sensor stream, used
 * from one thread.
 */
class HeadingFilter(private val share: Double = 0.25, private val step: Double = 2.0) {
    private var smoothed: Double? = null
    private var shown: Double? = null

    /** The heading to show after [raw] (degrees from north), or null where it hasn't moved enough to show. */
    fun next(raw: Double): Double? {
        val was = smoothed
        val now = if (was == null) Compass.normalize(raw) else Compass.normalize(was + share * Compass.relative(raw, was))
        smoothed = now
        val last = shown
        if (last != null && kotlin.math.abs(Compass.relative(now, last)) < step) return null
        shown = now
        return now
    }

    /** Starts afresh, as after the compass was unreliable: the next reading shows at once. */
    fun reset() {
        smoothed = null
        shown = null
    }
}
