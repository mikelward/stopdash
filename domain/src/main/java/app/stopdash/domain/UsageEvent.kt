package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration

/**
 * The custom usage events sent with **Help make StopDash better** on (SPEC *Privacy*, maintainer
 * 2026-09-24): what was used and how it went, never what it was used on. Every parameter value is a
 * category or a bucket from the closed vocabulary here, so no stop, line, journey, search text or
 * coordinate can reach one: nothing a caller passes is sent as it is.
 *
 * [name] and each parameter key follow Firebase's rules (letters, digits and underscores, starting
 * with a letter, at most 40 characters); each value is at most 100 characters.
 */
sealed class UsageEvent(val name: String, val params: Map<String, String>) {

    /** A tap, by what was tapped. */
    class Tapped(kind: Tap) : UsageEvent("tap", mapOf("kind" to kind.value))

    /** What a [Tapped] names. */
    enum class Tap(val value: String) {
        JOURNEY_CARD("journey_card"),
        STOP_ROW("stop_row"),
        CHANGE_CARD("change_card"),
        SWAP("swap"),
        STAR("star"),
        UNSTAR("unstar"),
        SEARCH("search"),
        SETTINGS("settings"),

        /** The widget's header, which refreshes it. */
        WIDGET_REFRESH("widget_refresh"),
    }

    /**
     * A farther place opened on the near-me list (the farther cards that took over "More stops"),
     * by its mode group ([modeGroup]): a bus place is "bus", a station the group of its first line
     * (a station's lines share one in all but a few interchanges); one with no lines is "other".
     */
    class FartherPlace(modes: List<String>) :
        UsageEvent("reveal", mapOf("what" to "farther_place", "mode" to (modes.firstOrNull()?.let(::modeGroup) ?: "other")))

    /** The near-me list's "Faraway favorites" reveal. */
    object FarawayFavorites : UsageEvent("reveal", mapOf("what" to "faraway_favorites"))

    /** The location permission the rider left the app with, after it asked. */
    class LocationPermission(grant: Grant) : UsageEvent("location_permission", mapOf("grant" to grant.value))

    /** What a [LocationPermission] names. */
    enum class Grant(val value: String) {
        PRECISE("precise"),
        APPROXIMATE("approximate"),
        DENIED("denied");

        companion object {
            /** The grant an answer left: precise with fine location, approximate with coarse alone. */
            fun of(fine: Boolean, coarse: Boolean): Grant = when {
                fine -> PRECISE
                coarse -> APPROXIMATE
                else -> DENIED
            }
        }
    }

    /**
     * How a near-me fix went: a fresh one, the last known one, or none; its accuracy and how long it
     * took, each in bands ([accuracyBand], [timeBand]), unknown where there was no fix.
     */
    class LocationFix(outcome: FixOutcome, accuracyMeters: Float?, took: Duration?) : UsageEvent(
        "location_fix",
        mapOf(
            "outcome" to outcome.value,
            "accuracy" to accuracyBand(accuracyMeters.takeIf { outcome != FixOutcome.FAILED }),
            "time_to_fix" to timeBand(took),
        ),
    )

    /** What a [LocationFix] names. */
    enum class FixOutcome(val value: String) { FRESH("fresh"), LAST_KNOWN("last_known"), FAILED("failed") }

    /**
     * How many stops a near-me lookup found, per mode group ([ModeGroups.ALL]), each in a bucket
     * ([countBucket]): every group present, a group with none as "0". A stop counts once for each
     * group any of its lines runs in (a station with the Tube and the Overground, for both).
     */
    class NearbyStops(stops: List<StopLocation>) : UsageEvent(
        "nearby_stops",
        ModeGroups.ALL.associate { group ->
            group.key to countBucket(stops.count { stop -> stop.lines.any { modeGroup(it.mode) == group.key } })
        },
    )

    /**
     * A screen coming up ([Screen]): each time it opens, and again each time the app returns to it. Sent
     * as Analytics' own `screen_view`, so its screen reports count it.
     */
    class ScreenView(screen: Screen) : UsageEvent("screen_view", mapOf("screen_name" to screen.value, "screen_class" to screen.value))

    /** What a [ScreenView] names. */
    enum class Screen(val value: String) {
        /** The near-me list, or the location question in its place. */
        HOME("home"),

        /** A row's route page. */
        ROUTE("route"),

        /** A searched station's page. */
        STATION("station"),

        /** The station search (a trip's *From…* included). */
        SEARCH("search"),

        /** A trip's *To…* search. */
        TRIP_TO("trip_to"),

        /** A trip's routes. */
        TRIP("trip"),
        ON_THE_WAY("on_the_way"),
        SETTINGS("settings"),
        FAVORITE_PLACES("favorite_places"),
        FAVORITE_JOURNEYS("favorite_journeys"),
        LICENSES("licenses"),

        /** *Lines…*: the line search. */
        LINES("lines"),

        /** A line's page: picked from *Lines…*, opened from a route's page (*View line*) or the On the way board. */
        LINE("line"),

        /** A stop's details, tapped on a line's map. */
        LINE_STOP("line_stop"),
    }

    /**
     * What opened the app ([OpenedFrom]), where an intent names it: a cold start from its icon, or a tap
     * that brought it up. A return to the app already running (from Recents, or its icon) brings no intent
     * to tell the two apart, so it isn't one of these: the [ScreenView] it shows and Analytics' own
     * `session_start` count those.
     */
    class Opened(from: OpenedFrom) : UsageEvent("open", mapOf("from" to from.value))

    /** What an [Opened] names. */
    enum class OpenedFrom(val value: String) {
        LAUNCHER("launcher"),
        WIDGET("widget"),
        NOTIFICATION("notification"),
        SHORTCUT("shortcut"),
        OTHER("other"),
    }

    /**
     * A trip planned ([PlanOutcome]): how many routes it found ([countBucket]), whether it started from
     * where the rider is or a stop, whether it went to a stop or a place, and whether it was the trip's
     * first plan or a later one (a pull, a changed option, or one past its reuse). Never which stops or
     * place. [to] is any one of the trip's destinations: a place is planned to on its own, a complex's
     * stops together.
     */
    class TripPlanned(outcome: PlanOutcome, routes: Int, from: TripOrigin, to: TripDestination?, again: Boolean) : UsageEvent(
        "trip_plan",
        mapOf(
            "outcome" to outcome.value,
            "routes" to countBucket(routes),
            "from" to if (from is TripOrigin.Here) "here" else "stop",
            "to" to if (to is TripDestination.Place) "place" else "stop",
            "plan" to if (again) "again" else "first",
        ),
    )

    /** How a [TripPlanned] went: routes, none, some of its requests failing, or all of them, by why. */
    enum class PlanOutcome(val value: String) {
        ROUTES("routes"),
        NO_ROUTES("no_routes"),
        PARTIAL("partial"),
        OFFLINE("offline"),
        RATE_LIMITED("rate_limited"),
        NETWORK("network"),
        KEY_REJECTED("key_rejected"),
        SERVER("server");

        companion object {
            /**
             * The outcome of a plan that failed with [e], as the trip says it ([TflException]): one that
             * reached TfL and still failed ([TflException.Unreachable]: a non-2xx, a decode failure) is TfL's
             * side, not the rider's network.
             */
            fun failed(e: TflException): PlanOutcome = when (e) {
                is TflException.Offline -> OFFLINE
                is TflException.RateLimited -> RATE_LIMITED
                is TflException.Network -> NETWORK
                is TflException.KeyRejected -> KEY_REJECTED
                else -> SERVER
            }

            /** The outcome of a plan that answered: [failed] when some of its requests didn't. */
            fun answered(routes: Int, failed: Boolean): PlanOutcome = when {
                failed -> PARTIAL
                routes > 0 -> ROUTES
                else -> NO_ROUTES
            }
        }
    }

    /**
     * A trip's route opened from its card: how the card was labeled ([RouteChoice]) and where it was in
     * the list ([rankBucket]).
     */
    class RouteOpened(choice: RouteChoice, rank: Int) : UsageEvent("route_open", mapOf("choice" to choice.value, "rank" to rankBucket(rank)))

    /** A route started on the way: how its card was labeled ([RouteChoice]) and how many changes it takes ([changesBucket]). */
    class TripStarted(choice: RouteChoice, rides: Int) : UsageEvent("trip_start", mapOf("choice" to choice.value, "changes" to changesBucket(rides)))

    /**
     * How a trip's card was labeled ([RouteLabel]): its labels, "other" for a card none of them heads
     * where others are labeled, "unlabeled" where no card is (a lone route, or routes alike in every way
     * labeled), or "unknown" for a route started without being opened from a card here.
     */
    enum class RouteChoice(val value: String) {
        FASTEST("fastest"),
        SIMPLEST("simplest"),
        LEAST_WALKING("least_walking"),
        FASTEST_SIMPLEST("fastest+simplest"),
        FASTEST_LEAST_WALKING("fastest+least_walking"),
        SIMPLEST_LEAST_WALKING("simplest+least_walking"),
        FASTEST_SIMPLEST_LEAST_WALKING("fastest+simplest+least_walking"),
        OTHER("other"),
        UNLABELED("unlabeled"),
        UNKNOWN("unknown");

        companion object {
            /**
             * The choice a card's [header] ([HeadedCard.header]) makes, given the list's first card's
             * ([firstHeader]): labeled cards come first ([headedCards]), so with none heading the first,
             * none is labeled. Worked out with the cards, never on a tap.
             */
            @WorkerThread
            fun of(header: List<RouteLabel>, firstHeader: List<RouteLabel>): RouteChoice {
                val fastest = RouteLabel.FASTEST in header
                val simplest = RouteLabel.SIMPLEST in header
                val leastWalking = RouteLabel.LEAST_WALKING in header
                return when {
                    fastest && simplest && leastWalking -> FASTEST_SIMPLEST_LEAST_WALKING
                    fastest && simplest -> FASTEST_SIMPLEST
                    fastest && leastWalking -> FASTEST_LEAST_WALKING
                    simplest && leastWalking -> SIMPLEST_LEAST_WALKING
                    fastest -> FASTEST
                    simplest -> SIMPLEST
                    leastWalking -> LEAST_WALKING
                    firstHeader.isNotEmpty() -> OTHER
                    else -> UNLABELED
                }
            }
        }
    }

    /**
     * A setting changed, from Settings or atop a trip: which one, and its new value as a category or a
     * band. A line is never named: avoiding one, hiding one or adding one to the disruptions row says
     * only that one was.
     */
    class SettingChanged private constructor(setting: String, value: String) :
        UsageEvent("setting_change", mapOf("setting" to setting, "value" to value)) {
        companion object {
            fun walkingSpeed(speed: WalkingSpeed) = SettingChanged("walking_speed", walkingSpeedValue(speed))
            fun maxWalk(maxWalk: MaxWalk) = SettingChanged("max_walk", "${maxWalk.minutes}")
            fun stepFree(stepFree: StepFree) = SettingChanged("step_free", stepFreeValue(stepFree))

            /** Each group a change of trip modes turned on or off. */
            @WorkerThread
            fun tripModes(before: TripModes, after: TripModes): List<SettingChanged> =
                ModeGroups.ALL.filter { after.rides(it) != before.rides(it) }
                    .map { SettingChanged("trip_mode", "${it.key}_${if (after.rides(it)) "on" else "off"}") }

            /** A line or a stop avoided or no longer avoided, never which. */
            @WorkerThread
            fun avoidedLines(before: Set<String>, after: Set<String>): List<SettingChanged> {
                fun kind(entry: String) = if (AvoidedLines.isStopKey(entry)) "avoided_stop" else "avoided_line"
                return (after - before).map { SettingChanged(kind(it), "added") } + (before - after).map { SettingChanged(kind(it), "removed") }
            }

            /**
             * Each group (or, unnamed, line) hidden or shown again; several shown at once, leaving none
             * hidden, as one.
             */
            @WorkerThread
            fun hiddenModes(before: Set<String>, after: Set<String>): List<SettingChanged> {
                val hidden = (after - before).map(::hiddenEntry).distinct()
                val shown = (before - after).map(::hiddenEntry).distinct()
                if (after.isEmpty() && shown.size > 1) return listOf(SettingChanged("hidden_mode", "all_shown"))
                return hidden.map { SettingChanged("hidden_mode", "${it}_hidden") } + shown.map { SettingChanged("hidden_mode", "${it}_shown") }
            }

            fun distanceUnits(units: DistanceUnits) = SettingChanged("distance_units", distanceUnitsValue(units))
            fun disruptionsRow(shown: Boolean) = SettingChanged("disruptions_row", onOff(shown))

            /** A line added to the disruptions row or taken off, never which. */
            @WorkerThread
            fun disruptionLines(before: Set<String>, after: Set<String>): List<SettingChanged> =
                List((after - before).size) { SettingChanged("disruption_line", "added") } +
                    List((before - after).size) { SettingChanged("disruption_line", "removed") }

            fun liveWidget(on: Boolean) = SettingChanged("live_widget", onOff(on))
            fun textSize(scale: Float) = SettingChanged("text_size", textSizeBand(scale))
            fun pinchToResize(on: Boolean) = SettingChanged("pinch_resize", onOff(on))

            /** The rider's own TfL key pasted or cleared; never the key. */
            fun tflKey(set: Boolean) = SettingChanged("tfl_key", if (set) "set" else "cleared")

            /** The rider's National Rail key pasted or cleared; never the key. */
            fun railKey(set: Boolean) = SettingChanged("rail_key", if (set) "set" else "cleared")

            /** "Don't ask again" ticked on the bug report's consent screen. */
            fun bugReportConsentSkipped() = SettingChanged("bug_report_consent", "skipped")
        }
    }

    override fun toString(): String = "$name$params"

    companion object {
        /** A mode's group key ([ModeGroups.ALL]), or "other" for one no group names, never the raw id. */
        fun modeGroup(mode: String): String =
            ModeGroups.ALL.firstOrNull { g -> g.modes.any { it.equals(mode, ignoreCase = true) } }?.key ?: "other"

        /** A card's place in a trip's list, from 1: "1", "2", "3" or "4+". */
        fun rankBucket(rank: Int): String = when {
            rank <= 1 -> "1"
            rank == 2 -> "2"
            rank == 3 -> "3"
            else -> "4+"
        }

        /** A route's changes from its rides: "0", "1", "2" or "3+". */
        fun changesBucket(rides: Int): String = when {
            rides <= 1 -> "0"
            rides == 2 -> "1"
            rides == 3 -> "2"
            else -> "3+"
        }

        /** A hidden-modes entry as a group key, or "line" for a hidden line; never the line. */
        fun hiddenEntry(entry: String): String = when {
            HiddenModes.isLineKey(entry) -> "line"
            ModeGroups.ALL.any { it.key == entry } -> entry
            else -> modeGroup(entry)
        }

        fun walkingSpeedValue(speed: WalkingSpeed): String = speed.name.lowercase()

        fun stepFreeValue(stepFree: StepFree): String = stepFree.name.lowercase()

        fun distanceUnitsValue(units: DistanceUnits): String = units.name.lowercase()

        /** The app's own text size ([FontSizeSettings.scale]) in bands. */
        fun textSizeBand(scale: Float): String = when {
            scale.isNaN() -> "unknown"
            scale < 0.95f -> "0.8-0.95"
            scale <= 1.05f -> "0.95-1.05"
            scale <= 1.25f -> "1.05-1.25"
            else -> "1.25+"
        }

        fun onOff(on: Boolean): String = if (on) "on" else "off"

        /** A count as "0", "1", "2-3" or "4+". */
        fun countBucket(count: Int): String = when {
            count <= 0 -> "0"
            count == 1 -> "1"
            count <= 3 -> "2-3"
            else -> "4+"
        }

        /** A fix's accuracy radius in bands; "unknown" with none. */
        fun accuracyBand(meters: Float?): String = when {
            meters == null || meters.isNaN() || meters < 0f -> "unknown"
            meters <= 10f -> "0-10m"
            meters <= 25f -> "10-25m"
            meters <= 50f -> "25-50m"
            meters <= 100f -> "50-100m"
            meters <= 500f -> "100-500m"
            else -> "500m+"
        }

        /** How long a fix took, in bands; "unknown" with no time. */
        fun timeBand(took: Duration?): String = when {
            took == null || took.isNegative -> "unknown"
            took < Duration.ofSeconds(1) -> "0-1s"
            took < Duration.ofSeconds(3) -> "1-3s"
            took < Duration.ofSeconds(10) -> "3-10s"
            took < Duration.ofSeconds(30) -> "10-30s"
            else -> "30s+"
        }
    }
}
