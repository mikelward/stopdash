package app.stopdash.domain

/**
 * A point on Earth — the device's own position, sent to TfL **only** to find nearby stops: on
 * demand in the app, or on a widget refresh when the widget follows the rider, which needs location
 * allowed all the time (SPEC D1 / *Privacy*). The journey alerts' London check and a trip on the
 * way compare it on the device, never sending it (SPEC *Journeys → Alerts*, *On the way*). A plain
 * data class of two doubles so the ranking math ([NearestStops]) and the resolver stay
 * pure and JVM-testable, with no Android `Location` in the domain.
 */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * A resolved position plus how much to trust it. [isFallback] is true when the fresh-fix
 * attempt failed and a bounded last-known fix was used instead (the classic no-signal case, e.g.
 * the Underground): the coordinate is real but is the user's *previous* position, so a surface
 * that re-resolves the nearby set from it may show the wrong stops. A fresh fix — or the recent
 * cached one the fast path returns — has [isFallback] false. The caller decides what a fallback
 * means (SPEC *Finding stops*): don't jump the set to it on a re-locate, and label a set shown
 * from one as "your last-known area."
 *
 * [isCoarse] is true when a fresh fix came only from a coarse source (the network or passive
 * provider) under a precise grant, because GPS/fused didn't answer within the short grace: fast
 * enough to show something at once (underground), but it can be hundreds of meters out. The caller
 * labels a set shown from one as approximate and asks [LocationProvider.precise] for a better fix.
 *
 * [accuracyMeters] is how sure the fix is, as its provider reported it (null when it didn't say): a
 * precise provider's fix can still be vague indoors, which a caller deciding on distance must heed.
 * [ageMillis] is how long before it was handed over the fix was taken (null when not known): a
 * sure fix from a while ago says nothing of where a rider on a moving train is now.
 */
data class LocationFix(
    val coordinates: Coordinates,
    val isFallback: Boolean,
    val isCoarse: Boolean = false,
    val accuracyMeters: Float? = null,
    val ageMillis: Long? = null,
)

/**
 * Supplies the device's current position for the nearby-stops search. A domain seam so
 * the resolver is testable with a fake and never touches Android's `LocationManager`
 * directly (the Android implementation lives in `data`).
 *
 * [current] returns `null` when there is no position to give — location is switched off,
 * the permission isn't held, or no fix is available yet — which the caller renders as an
 * honest "couldn't get your location" state rather than guessing one (SPEC principle 2).
 * It suspends: a fix can take a moment, and it must never block a thread or the first
 * frame (SPEC jank-free UI). The permission itself is the caller's to request; a provider
 * asked without it simply returns `null`.
 */
interface LocationProvider {
    /**
     * The device's current position (with its [LocationFix.isFallback] trust flag), or `null`
     * when none can be given.
     *
     * [forceFresh] governs the recent-cache fast path: by default a very recent cached fix is
     * returned at once (fast, and fine for a first open where the user just arrived). A
     * **re-locate on refresh** passes `true` — the user may have walked since the last fix, so
     * a cached one (even a recent one) would re-query TfL for the *previous* position and show
     * the old area's stops; forcing fresh requests a new fix and falls back to a cached one only
     * within a bounded age if the fresh request fails (that fallback is flagged [LocationFix.isFallback]).
     */
    suspend fun current(forceFresh: Boolean = false): LocationFix?

    /**
     * A fresh fix from the **accurate** sources only (fused/GPS), given longer than [current]'s
     * short grace, or `null` when none arrives in time (or precise location isn't granted). Asked
     * after [current] returned an [LocationFix.isCoarse] fix, so a list shown from a network fix
     * can move to where the rider really is once GPS answers (SPEC *Finding stops*).
     */
    suspend fun precise(): Coordinates? = null

    /**
     * [precise] with its own confidence: the same fix, carrying [LocationFix.accuracyMeters], so a
     * caller that acts only on a close fix (the favorite chips) can tell a vague GPS answer from a
     * sharp one rather than trusting the source (Codex). By default [precise]'s coordinate with no
     * accuracy, which such a caller treats as rough.
     */
    suspend fun preciseWithAccuracy(): LocationFix? = precise()?.let { LocationFix(it, isFallback = false) }
}
