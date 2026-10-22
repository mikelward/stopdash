package app.stopdash

import android.content.Context
import android.content.res.Resources
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import app.stopdash.data.DistanceUnitsSetting
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.DistanceSystem
import app.stopdash.domain.LineSequence
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.PlatformDirection
import app.stopdash.domain.StopQualifier
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripMeter
import app.stopdash.domain.TripProgress
import app.stopdash.ui.ARROW
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.HeaderWords
import app.stopdash.ui.busPoleLabel
import app.stopdash.ui.compassLetters
import app.stopdash.ui.distanceText
import app.stopdash.ui.groupHeaderLabel
import app.stopdash.ui.localeDistanceSystem
import app.stopdash.ui.lineFillColor
import app.stopdash.ui.overgroundAccentColor
import app.stopdash.ui.railOperatorColor
import java.time.Instant

/**
 * The trip's ongoing notification as an Android Live Update (SPEC *On the way*): a progress bar of
 * the trip's legs in their lines' colors, and a status-bar chip counting down the step. Android
 * decides whether it's promoted (the rider can turn Live Updates off for the app); either way the
 * notification reads the same.
 */
internal object OnTheWayLiveUpdate {
    // A walk's stretch of the bar: neutral, as the trip's own walks are drawn without a line color.
    private const val WALK_COLOR = 0xFF9E9E9E.toInt()

    /**
     * Where to board the next ride: the train's destination ([heading]), and where to stand ([where]) as
     * the board heads that stop ("Platform 8", "Southbound", "Stop G", "➔ Archway"; [groupHeaderLabel]).
     */
    data class Boarding(val heading: String?, val where: String? = null)

    /** The bar for [trip] at [progress], or null with nothing to draw. Grows with the route, so off the main thread. */
    @WorkerThread
    fun style(trip: ActiveTrip, progress: TripProgress?, now: Instant): NotificationCompat.ProgressStyle? {
        val meter = TripMeter.meter(trip, progress, now) ?: return null
        return NotificationCompat.ProgressStyle()
            .setProgressSegments(meter.segments.map { NotificationCompat.ProgressStyle.Segment(it.length).setColor(segmentColor(it.leg)) })
            .setProgress(meter.progress)
    }

    /**
     * The chip's text at [progress]: where to stand when the step is boarding or a change
     * ([boarding]), else where the train goes, each shortened only as far as the chip needs
     * ([fitChip]); stops left on a ride ("Next stop" at one); on a walk how far its end is, in the
     * rider's units ([system]), where a fix has placed them; minutes to the next thing otherwise.
     */
    fun chipText(resources: Resources, progress: TripProgress?, now: Instant, boarding: Boarding? = null, system: DistanceSystem? = null): String? =
        boarding?.let { it.where ?: it.heading }?.let(::fitChip)
            ?: walkLeft(resources, progress, system)
            ?: countText(resources, progress, now)

    /**
     * [label] in the longest of the forms every shortened name steps through
     * ([DestinationAbbreviations.forms]) that fits the chip ([CHIP_CHARS]), then the shortest closed up
     * as the walk's chip closes its distance ("Plat10A"), else the shortest: Android cuts the rest off,
     * so "Platform 8" showed as "Platform" and lost the number ("Plat 8"), and "Plat 10A" could read as
     * platform 10 (Codex on #1037). A sign's "towards" keeps its arrow and shortens only the place after
     * it: taken as the name's first word, the arrow left "➔ Archway" at its floor, "➔ A." (Codex on #1037).
     */
    private fun fitChip(label: String): String {
        val arrow = label.takeIf { it.startsWith("$ARROW ") }?.let { "$ARROW " }.orEmpty()
        val forms = DestinationAbbreviations.forms(label.removePrefix(arrow)).map { arrow + it }
        return (forms + forms.last().replace(" ", "")).firstOrNull { it.length <= CHIP_CHARS } ?: forms.last()
    }

    /**
     * A walk's distance left, as the trip's screen writes it ("~450 m" while estimated), after the
     * compass point its end lies in where there's one: "NE 450m", the distance closed up to fit the
     * chip ([CHIP_CHARS]), and the space after the point dropped too where even that doesn't ("NE~450m",
     * "NE1.2km"; maintainer, 2026-10-11). Null unknown. The chip can't follow the phone turning, so it
     * names the way rather than point it, beside the app's route arrow as ever (maintainer, 2026-10-13).
     */
    private fun walkLeft(resources: Resources, progress: TripProgress?, system: DistanceSystem?): String? {
        val walking = progress as? TripProgress.Walking ?: return null
        val meters = walking.metersLeft ?: return null
        // Not until the rider's units are known: never a moment in the wrong ones.
        val label = distanceText(meters, system ?: return null, resources)
            .let { if (walking.estimated) resources.getString(R.string.on_the_way_walk_left_estimated, it) else it }
        val point = walking.point?.let { resources.getString(compassLetters(it)) } ?: return label
        val closed = label.replace(" ", "")
        return resources.getString(R.string.on_the_way_chip_walk_direction, point, closed)
            .takeIf { it.length <= CHIP_CHARS }
            ?: resources.getString(R.string.on_the_way_chip_walk_direction_tight, point, closed)
    }

    /** The most characters Android suggests for a Live Update's chip; more may be cut off. */
    const val CHIP_CHARS = 7

    /**
     * The rider's distance units ([DistanceUnitsSetting], warmed in memory at process start) in
     * [context]'s locale, as the trip's screen takes them; null while the choice is still being read.
     */
    fun distanceSystem(context: Context): DistanceSystem? =
        if (DistanceUnitsSetting.isLoaded.value) DistanceUnitsSetting.changes.value.resolve(localeDistanceSystem(context.resources.configuration.locales[0])) else null

    private fun countText(resources: Resources, progress: TripProgress?, now: Instant): String? = when (val chip = TripMeter.chip(progress, now)) {
        // The stop to get off at is next: said so, as the trip's own line says it.
        is TripMeter.Chip.Stops if chip.count == 1 -> resources.getString(R.string.on_the_way_next_stop)
        is TripMeter.Chip.Stops -> resources.getQuantityString(R.plurals.on_the_way_stops_left, chip.count, chip.count)
        is TripMeter.Chip.Minutes -> resources.getString(R.string.on_the_way_chip_minutes, chip.count)
        null -> null
    }

    /** A ride's line color on the bar, as its pill takes it; a walk's neutral. Null leaves Android's own. */
    fun segmentColor(leg: TripLeg): Int {
        if (leg.isWalk) return WALK_COLOR
        val color = overgroundAccentColor(leg.lineId) ?: railOperatorColor(leg.mode, leg.lineName, leg.lineId) ?: lineFillColor(leg.lineId, leg.mode)
        return color?.toArgb() ?: NotificationCompat.COLOR_DEFAULT
    }

    /**
     * Where to board when the step is boarding or a change: where the train goes as its front shows it,
     * the board's own destination for the train followed, else for the next of the step's own line the
     * rider can catch that takes them where they get off, as the trip's board keeps them
     * ([OnTheWay.placeTrains]) and the tracker would pick one ([OnTheWay.nextTrain]): the step names that
     * line, so another's blind beside it would mislead. With none on the board, the stop they get off at;
     * never the plan's heading, which names the route's last stop rather than a bus's blind. And where to
     * stand as the board heads the stop ([groupHeaderLabel]): at a station that same train's platform,
     * else the direction its platform faces;
     * at a bus stop its pole's letter, else the "towards" on its sign, else its bearing
     * ([busPoleLabel]). [route] is a line's route where it's already held, never fetched here; the board
     * is routed once by [placements] and read again at each [now]. Null on any other step. Off the main
     * thread: it routes the boarding stop's board.
     */
    @WorkerThread
    fun boarding(
        trip: ActiveTrip,
        progress: TripProgress?,
        board: ActiveTripTracker.NextBoard?,
        now: Instant,
        placements: Placements = Placements(),
        // The platform's or pole's words ("Platform 8", "Stop G"), in the app's language.
        words: HeaderWords = HeaderWords.ENGLISH,
        route: (String) -> LineSequence? = { null },
    ): Boarding? {
        val leg = when (progress) {
            is TripProgress.Waiting -> progress.leg
            is TripProgress.Changing -> progress.leg
            else -> return null
        }
        val read = board?.takeIf { it.ride == leg && !it.failed }
        // The train followed, on the board of the stop the rider boards at, with the pole it calls at. A
        // change's ride has none followed yet: the trip's train is still the one before it.
        val followed = trip.vehicleId.takeIf { progress is TripProgress.Waiting && it.isNotBlank() }?.let { id ->
            val line = OnTheWay.followedLine(trip).ifBlank { leg.lineId }
            read?.let { b ->
                b.departures.firstOrNull { it.vehicleId == id && it.lineId == line }?.let { it to b.pole }
                    ?: b.others.firstNotNullOfOrNull { pole ->
                        pole.departures.firstOrNull { it.vehicleId == id && it.lineId == line }?.let { it to pole.pole }
                    }
            }
        }
        val planned = leg.headings.firstOrNull()?.let { DepartureLabels.destinationLabel(it, "") ?: it }
        // The ride's own line's trains on the board that take the rider where they get off. Without the line's
        // route held, those to where the plan's goes, by name, so a board listing both ways never points the
        // rider at the wrong one.
        val trains = read?.let { b ->
            val fetchedAt = b.fetchedAt
            val sequence = route(leg.lineId)
            if (fetchedAt != null && sequence != null) {
                placements.of(b, leg, sequence, fetchedAt, now).trainsAt(now).trains.filter { it.lineId == leg.lineId }
            } else {
                b.departures.filter { it.lineId == leg.lineId && planned != null && DepartureLabels.destinationLabel(it.destination, "") == planned }
            }
        }.orEmpty()
        // The train the step names, its heading and its platform both: the one followed, else the next the
        // rider can catch as the tracker would pick it ([OnTheWay.nextTrain]), not one gone before they can be
        // there (the tick can rebuild this between refreshes, past a change's end).
        val train = followed?.first ?: OnTheWay.nextTrain(trains, maxOf(OnTheWay.readyAt(trip, progress) ?: now, now))
        // As its front shows it; with no train known, where the rider gets off (maintainer, 2026-10-15). Never
        // the plan's heading: the Planner names the route's last stop, which is often not what a bus's blind says.
        val heading = train?.destination?.takeIf { it.isNotBlank() }
            ?.let { DepartureLabels.destinationLabel(it, "") } ?: leg.toName.takeIf { it.isNotBlank() }
        val where = if (ActiveTripTracker.boardsAtPole(leg)) {
            // The pole the bus or tram followed calls at, else the ride's own, as the board heads it.
            (followed?.second ?: read?.pole)?.let { busPoleLabel(it.stopLetter, it.towards, it.bearing, words) }
        } else {
            train?.platform?.let { platform ->
                val direction = PlatformDirection.of(platform)
                PlatformDirection.platformNumber(platform)?.let { groupHeaderLabel(StopQualifier.Platform(it, direction), words) }
                    ?: direction?.let { groupHeaderLabel(StopQualifier.Compass(it), words) }
            }
        }
        return Boarding(heading?.takeIf { it.isNotBlank() }, where).takeIf { it.heading != null || it.where != null }
    }

    /**
     * The ride's board as [OnTheWay.placeTrains] routes it on the ride's line, worked out once and kept
     * until the board, the ride or the line's route changes: the notification is shown again every tick,
     * and a long route isn't walked again each time for the same answer (Codex on #882). One for each
     * notification flow, which reads it one tick at a time.
     */
    class Placements {
        private var board: ActiveTripTracker.NextBoard? = null
        private var leg: TripLeg? = null
        private var sequence: LineSequence? = null
        private var from: Instant? = null
        private var placed: OnTheWay.PlacedTrains? = null

        /**
         * [board]'s trains for [leg] on [sequence], fetched at [fetchedAt]: as placed before for these, else
         * from [now]. Placed again when the clock has gone back before the last placing, which dropped the
         * trains gone by then: a countdown follows the wall clock, as the trip's screen rebuilds on a rewind.
         */
        @WorkerThread
        fun of(board: ActiveTripTracker.NextBoard, leg: TripLeg, sequence: LineSequence, fetchedAt: Instant, now: Instant): OnTheWay.PlacedTrains {
            placed?.takeIf { board === this.board && sequence === this.sequence && leg == this.leg && from?.isAfter(now) == false }
                ?.let { return it }
            return OnTheWay.placeTrains(leg, board.departures, fetchedAt, mapOf(leg.lineId to sequence), now).also {
                this.board = board
                this.leg = leg
                this.sequence = sequence
                from = now
                placed = it
            }
        }
    }

    /**
     * [boarding] put before the step's own [detail]: "➔ Stratford · Platform 2 · Due in 4 min". A pole
     * headed by its sign's "towards" is left to the chip: beside the train's own destination it reads
     * as a second one.
     */
    fun withBoarding(resources: Resources, boarding: Boarding?, detail: String): String {
        if (boarding == null) return detail
        val heading = boarding.heading?.let { resources.getString(R.string.on_the_way_towards, it) }
        val where = boarding.where?.takeUnless { heading != null && it.startsWith(ARROW) }
        return listOfNotNull(heading, where, detail.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
}
