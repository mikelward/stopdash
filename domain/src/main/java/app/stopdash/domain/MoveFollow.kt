package app.stopdash.domain

/**
 * Whether a location update that came while the near-me list is on screen should move the list
 * (SPEC *Finding stops*): the rider has walked far enough from where the list was found that its
 * stops may no longer be the nearest, and the fix is sure enough to say so.
 *
 * - **Sure enough**: a fresh fix (never a fallback or a coarse one, and no older than
 *   [MAX_AGE_MILLIS]) whose reported accuracy is no worse than [MAX_ACCURACY_METERS]. Underground a network fix can place the rider at a different station
 *   with a confident-sounding radius, so an unreported accuracy, or a wide one, never moves the
 *   list; a pull still can.
 * - **Far enough**: [FixRefinement.MOVE_THRESHOLD_METERS] from where the shown list was found, the
 *   same distance a precise follow-up moves it by.
 * - **Not too often**: [MIN_GAP_MILLIS] since the list was last found, so a rider walking briskly
 *   costs TfL a lookup a minute at most (maintainer's rate goal). A fix that is sure and far enough
 *   but comes too soon waits out the gap rather than being dropped: a rider who stops at the next
 *   stop sends no more updates, so dropping it would leave the list where they were.
 */
object MoveFollow {
    /**
     * How often location is asked for while the list is on screen, and how far the rider must have
     * moved for an update to come. The interval is the battery lever (the distance gates callbacks,
     * not the hardware); the foreground-only request is the bound on it. The distance is a quarter
     * of the move threshold, not all of it, so a rider still walking keeps sending fixes and a newer
     * one replaces a fix waiting out [MIN_GAP_MILLIS]: the request measures from the last fix it
     * delivered, not from where the list was found.
     */
    val UPDATE_EVERY: java.time.Duration = java.time.Duration.ofSeconds(12)
    const val UPDATE_DISTANCE_METERS = 25f

    /** The least time between two lookups following the rider. */
    const val MIN_GAP_MILLIS = 60_000L

    /** The oldest a fix may be and still say where the rider is now. */
    const val MAX_AGE_MILLIS = 30_000L

    /** The widest accuracy a fix may report and still move the list. */
    const val MAX_ACCURACY_METERS = 50f

    fun shouldFollow(shownFrom: Coordinates, fix: LocationFix, sinceShownMillis: Long): Boolean =
        followAfterMillis(shownFrom, fix, sinceShownMillis) == 0L

    /**
     * How long until [fix] should move the list found at [shownFrom] [sinceShownMillis] ago: 0 to
     * move it now, the rest of [MIN_GAP_MILLIS] when it came too soon, or null when it never
     * should (not sure enough, or not far enough).
     */
    fun followAfterMillis(shownFrom: Coordinates, fix: LocationFix, sinceShownMillis: Long): Long? {
        if (!isSure(fix)) return null
        if (!FixRefinement.shouldMove(shownFrom, fix.coordinates)) return null
        return (MIN_GAP_MILLIS - sinceShownMillis).coerceAtLeast(0L)
    }

    /**
     * Whether [fix] says where the rider is, near or far: fresh, not coarse or a fallback, and with
     * a reported accuracy within [MAX_ACCURACY_METERS]. A sure fix close to the list also overrules
     * a move still waiting out the minute, since the rider has come back; an unsure one doesn't.
     */
    fun isSure(fix: LocationFix): Boolean {
        if (fix.isFallback || fix.isCoarse) return false
        if ((fix.ageMillis ?: 0) > MAX_AGE_MILLIS) return false
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy <= MAX_ACCURACY_METERS
    }
}
