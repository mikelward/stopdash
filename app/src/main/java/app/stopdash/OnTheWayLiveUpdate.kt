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
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.PlatformDirection
import app.stopdash.domain.StopDistance
import app.stopdash.domain.StopQualifier
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripMeter
import app.stopdash.domain.TripProgress
import app.stopdash.ui.ARROW
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.busPoleLabel
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
    data class Boarding(val heading: String?, val where: String? = null) {
        /** [heading] as short as a board shortens one, for the chip's few characters where [where] isn't known. */
        val headingShort: String? = heading?.let(DestinationAbbreviations::abbreviate)
    }

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
     * ([boarding]), else where the train goes; stops left on a ride ("Next stop" at one); on a walk
     * how far its end is, in the rider's units ([system]), where a fix has placed them; minutes to
     * the next thing otherwise.
     */
    fun chipText(resources: Resources, progress: TripProgress?, now: Instant, boarding: Boarding? = null, system: DistanceSystem? = null): String? =
        boarding?.let { it.where ?: it.headingShort }
            ?: walkLeft(resources, progress, system)
            ?: countText(resources, progress, now)

    /** A walk's distance left, as the trip's screen writes it ("~450 m" while estimated); null unknown. */
    private fun walkLeft(resources: Resources, progress: TripProgress?, system: DistanceSystem?): String? {
        val walking = progress as? TripProgress.Walking ?: return null
        val meters = walking.metersLeft ?: return null
        // Not until the rider's units are known: never a moment in the wrong ones.
        val label = StopDistance.label(meters, system ?: return null)
        return if (walking.estimated) resources.getString(R.string.on_the_way_walk_left_estimated, label) else label
    }

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
     * Where to board when the step is boarding or a change: the destination of the train followed, as
     * the board names it, else the plan's own heading; and where to stand as the board heads the stop
     * ([groupHeaderLabel]): at a station the train's platform, else the direction its platform faces;
     * at a bus stop its pole's letter, else the "towards" on its sign, else its bearing ([busPoleLabel]).
     * Null on any other step. Off the main thread: it looks through the boarding stop's board.
     */
    @WorkerThread
    fun boarding(trip: ActiveTrip, progress: TripProgress?, board: ActiveTripTracker.NextBoard?, now: Instant): Boarding? {
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
        val heading = followed?.first?.destination?.takeIf { it.isNotBlank() }?.let { DepartureLabels.destinationLabel(it, "") } ?: planned
        // Changing, none is followed yet: the platform is the next catchable train's on the ride's line to
        // where the plan's goes, so a board listing both ways never points the rider at the wrong one.
        val next = followed?.first ?: (progress as? TripProgress.Changing)?.let { changing ->
            read?.departures
                // Not one gone already: the tick can rebuild this between refreshes, past the change's end.
                ?.filter { it.lineId == leg.lineId && !it.expectedArrival.isBefore(maxOf(changing.until, now)) }
                ?.filter { planned != null && DepartureLabels.destinationLabel(it.destination, "") == planned }
                ?.minByOrNull { it.expectedArrival }
        }
        val where = if (ActiveTripTracker.boardsAtPole(leg)) {
            // The pole the bus or tram followed calls at, else the ride's own, as the board heads it.
            (followed?.second ?: read?.pole)?.let { busPoleLabel(it.stopLetter, it.towards, it.bearing) }
        } else {
            next?.platform?.let { platform ->
                val direction = PlatformDirection.of(platform)
                PlatformDirection.platformNumber(platform)?.let { groupHeaderLabel(StopQualifier.Platform(it, direction)) }
                    ?: direction?.let { groupHeaderLabel(StopQualifier.Compass(it)) }
            }
        }
        return Boarding(heading?.takeIf { it.isNotBlank() }, where).takeIf { it.heading != null || it.where != null }
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
