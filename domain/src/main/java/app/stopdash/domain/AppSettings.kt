package app.stopdash.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The user's app-level settings, behind a seam (interface) so a surface depends on the
 * capability, not on DataStore, and a JVM/Robolectric test can supply a fake. The concrete
 * DataStore-backed implementation lives in the `data` layer.
 *
 * Unlike the starred rows or (later) watched stops, a setting is not irreplaceable user work:
 * a lost or unreadable settings file resets to the documented defaults rather than being
 * preserved as [StarredRowSet.Unavailable]. Each accessor is a cold [Flow] that emits the
 * current value at once and again on every change, so a screen collecting it reflects a
 * toggle immediately and a later launch reads it back.
 */
interface AppSettings {
    /**
     * Whether the widget refreshes its data on its own roughly once a minute in the
     * background (SPEC D5's opt-in "live widget" cadence — deferrable and Doze-gated, not a
     * screen-on-only guarantee). Off by default — the widget otherwise follows the app's own
     * refresh (D6) and ages honestly to `?` when stale (D4).
     */
    fun liveWidgetRefresh(): Flow<Boolean>

    /** Set [liveWidgetRefresh]. Suspending, meant to run off the main thread; best-effort. */
    suspend fun setLiveWidgetRefresh(enabled: Boolean)

    /**
     * The app's own text size and whether a pinch may change it (SPEC *Display size*): the scale
     * factor multiplies the system font scale, and the pinch switch gates the two-finger gesture.
     * One flow so a warmed cache reads both together and a screen re-renders on either change.
     */
    fun fontSize(): Flow<FontSizeSettings>

    /** Set the font [scale]; clamped on read. Suspending, off the main thread; best-effort. */
    suspend fun setFontScale(scale: Float)

    /** Set whether a pinch may resize text. Suspending, off the main thread; best-effort. */
    suspend fun setPinchEnabled(enabled: Boolean)

    /**
     * Whether the user has opted out of the bug-report consent screen ("don't ask again"), so a
     * later "Send bug report" goes straight to the share sheet. Off by default — the report carries
     * the exact location, so consent is asked every time until the user themselves turns it off
     * (SPEC *Privacy*, `TODO.md`). Read before the dialog is shown.
     */
    fun skipBugReportConsent(): Flow<Boolean>

    /** Set [skipBugReportConsent]. Suspending, off the main thread; best-effort. */
    suspend fun setSkipBugReportConsent(enabled: Boolean)

    /**
     * Whether the user has dismissed the route page's tip on starring a journey (SPEC *Journeys*).
     * Off by default, so the tip shows until dismissed; a store that keeps no settings never shows it.
     */
    fun journeyTipDismissed(): Flow<Boolean> = flowOf(true)

    /** Set [journeyTipDismissed]. Suspending, off the main thread; best-effort. */
    suspend fun setJourneyTipDismissed(dismissed: Boolean) {}

    /**
     * The user's own free TfL `app_key`, pasted in Settings for the higher request budget
     * (SPEC D7), or null when keyless — the default. StopDash ships no baked-in key; a
     * per-user key raises the limit ~50→~500 req/min. Blank is normalized to null on write,
     * so the accessor emits either a non-blank key or null, never an empty string. It is a
     * credential: sent only with the user's own TfL requests (its purpose), persisted in a
     * private app file that rides Android backup like any setting, and never logged or placed
     * in any other off-device artifact (SPEC *Privacy*, `docs/PRIVACY.md`).
     */
    fun userApiKey(): Flow<String?>

    /**
     * Set [userApiKey]; a null or blank value clears it (back to keyless). Suspending, off the
     * main thread; best-effort.
     */
    suspend fun setUserApiKey(key: String?)

    /**
     * The user's own Rail Data Marketplace key for National Rail's live departures (SPEC *National
     * Rail*), or null: without one, a National Rail line's status row says "No key". A credential
     * like [userApiKey]: sent only with the user's own National Rail requests, persisted privately, never
     * logged or placed in any other off-device artifact. Blank reads as null.
     */
    fun railApiKey(): Flow<String?> = flowOf(null)

    /** Set [railApiKey]; a null or blank value clears it. Suspending, off the main thread. */
    suspend fun setRailApiKey(key: String?) {}

    /**
     * The transport modes hidden from the near-me list (SPEC *Finding stops → Hiding a mode*),
     * empty by default: everything shows.
     */
    fun hiddenModes(): Flow<Set<String>> = flowOf(emptySet())

    /** Set [hiddenModes] to [modes]. Suspending, off the main thread; best-effort. */
    suspend fun setHiddenModes(modes: Set<String>) {}

    /**
     * The units the near-me distances are written in (SPEC *Finding stops*): follow the phone's
     * locale ([DistanceUnits.AUTOMATIC], the default), or always metric or imperial.
     */
    fun distanceUnits(): Flow<DistanceUnits> = flowOf(DistanceUnits.AUTOMATIC)

    /** Set [distanceUnits]. Suspending, off the main thread; best-effort. */
    suspend fun setDistanceUnits(units: DistanceUnits) {}

    /** How fast the rider walks, for a trip's walks ([WalkingSpeed]); the Planner's average until chosen. */
    fun walkingSpeed(): Flow<WalkingSpeed> = flowOf(WalkingSpeed.AVERAGE)

    /** Stores the walking-speed choice. */
    suspend fun setWalkingSpeed(speed: WalkingSpeed) {}

    /** The longest walk a trip may ask of the rider ([MaxWalk]); [MaxWalk.DEFAULT] until chosen. */
    fun maxWalk(): Flow<MaxWalk> = flowOf(MaxWalk.DEFAULT)

    /** Stores the max-walk choice. */
    suspend fun setMaxWalk(maxWalk: MaxWalk) {}

    companion object {
        /** A store that persists nothing and always reads the defaults — the default for tests
         *  and a build with no wired DataStore, so the app runs identically minus persistence. */
        val NONE: AppSettings = object : AppSettings {
            override fun liveWidgetRefresh(): Flow<Boolean> = flowOf(false)
            override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
            override fun fontSize(): Flow<FontSizeSettings> = flowOf(FontSizeSettings())
            override suspend fun setFontScale(scale: Float) {}
            override suspend fun setPinchEnabled(enabled: Boolean) {}
            override fun skipBugReportConsent(): Flow<Boolean> = flowOf(false)
            override suspend fun setSkipBugReportConsent(enabled: Boolean) {}
            override fun userApiKey(): Flow<String?> = flowOf(null)
            override suspend fun setUserApiKey(key: String?) {}
        }
    }
}
