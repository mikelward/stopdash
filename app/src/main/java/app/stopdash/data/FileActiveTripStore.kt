package app.stopdash.data

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Coordinates
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.riderLineName
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The trip on the way (SPEC *On the way*), in one JSON [file] in the app's no-backup directory: it
 * survives the app being closed but not a device transfer, and never leaves the device — where a
 * rider is going is theirs (`docs/PRIVACY.md`). Blocking; call it off the main thread. An unparseable
 * file loads as no trip and is deleted; one that can't be read is kept, and [load] throws, to be tried
 * again. A failed write is logged (a bare reason, never a stop) and the trip is followed in memory only.
 */
internal class FileActiveTripStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
    private val delete: (File) -> Boolean = File::delete,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    private val tmp = File(file.path + ".tmp")

    // The mark of a trip ended ([forget]), in a file of its own: while it says so, no trip loads,
    // whatever the kept or temp file still holds. Emptied, it's cleared, as when it can't be deleted.
    private val ended = File(file.path + ".ended")

    // Where the mark is written before it's renamed in ([forget]).
    private val endedAside = File(ended.path + ".tmp")

    private fun isEnded(): Boolean = ended.exists() && ended.length() > 0

    // A mark written but not yet renamed in when the app died: whole, it counts, as the mark
    // would; cut short, it says nothing and is dropped. One that can't be read now is kept, and the
    // failure passed on, to be read again: it may be whole.
    private fun stagedEnd(): Boolean {
        if (!endedAside.exists()) return false
        val whole = try {
            endedAside.readText() == ENDED
        } catch (e: IOException) {
            warn("active trip: staged ended mark unreadable, kept: ${e::class.simpleName}")
            throw e
        }
        if (!whole && !endedAside.delete()) warn("active trip: partial ended mark not deleted")
        return whole
    }

    @Synchronized
    fun load(): ActiveTrip? {
        if (isEnded() || stagedEnd()) {
            // A trip ended, its forgetting cut short: finished now, and no trip.
            finishForgetting()
            return null
        }
        if (tmp.exists()) recoverTemp()?.let { return it }
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            // An ended trip whose file couldn't be deleted ([save]): no trip.
            if (text.isBlank()) return null
            json.decodeFromString<PersistedActiveTrip>(text).toTrip()
        } catch (e: IOException) {
            // Storage that couldn't be read now may be read later: kept, and the failure passed on.
            warn("active trip unreadable (${e::class.simpleName}), kept for another try")
            throw e
        } catch (e: SerializationException) {
            discard("unparseable", e)
        } catch (e: IllegalArgumentException) {
            // A time or duration that doesn't parse.
            discard("unparseable", e)
        } catch (e: DateTimeParseException) {
            discard("unparseable", e)
        }
    }

    /**
     * Keep [trip], or forget the kept one when null (the trip ended): whether that worked. A trip
     * that can't be kept forgets the one kept before, so a restart never brings back a trip ended or
     * moved on since.
     */
    @Synchronized
    fun save(trip: ActiveTrip?): Boolean {
        if (trip == null) return forget()
        // A trip ended before this one that couldn't be wholly forgotten: finished first, so the mark
        // can go without the old trip coming back; failing that, this one can't be kept either.
        val endedBefore = try {
            isEnded() || stagedEnd()
        } catch (e: IOException) {
            // A staged mark that can't be read may be whole: this trip isn't kept over it.
            warn("active trip not saved: ${e::class.simpleName}")
            return false
        }
        if (endedBefore && !finishForgetting()) {
            warn("active trip not saved: an ended trip is still to be forgotten")
            return false
        }
        try {
            val text = json.encodeToString(PersistedActiveTrip.of(trip))
            tmp.writeText(text)
            // Replace in one step, so a reader never sees a half-written file; failing that, in
            // place (a torn write loads as no trip).
            if (!rename(tmp, file)) {
                warn("active trip saved in place: rename failed")
                file.writeText(text)
            }
            return true
        } catch (e: IOException) {
            warn("active trip not saved: ${e::class.simpleName}")
            // The one kept before is out of date now; one that can't be forgotten either may come
            // back so on the next start, which the caller says as it says any failed save.
            if (!forget()) warn("active trip: out-of-date copy left")
            return false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // The kept trip forgotten. First the mark of a trip ended ([ended]): while it's there no trip
    // loads, so dying partway through never brings the trip back. Then the temp record and the
    // file: deleted, or emptied when they can't be (an empty file loads as no trip); then the mark.
    // Whether no trip will load.
    private fun forget(): Boolean {
        // Already marked (an earlier end that couldn't finish): left as it is, not rewritten, which
        // dying partway through could cut short.
        val alreadyMarked = try {
            isEnded() || stagedEnd()
        } catch (e: IOException) {
            // Unreadable: a fresh mark is written over it below, which ends the trip either way.
            false
        }
        if (alreadyMarked) {
            finishForgetting()
            return true
        }
        val marked = try {
            // Written aside and renamed in, so dying mid-write can't leave an empty mark, which
            // reads as cleared.
            endedAside.writeText(ENDED)
            // Failing the rename, the whole mark stands where it was written ([stagedEnd]): rewriting
            // it in place could leave it empty if the app died mid-write.
            if (!rename(endedAside, ended)) warn("active trip: ended mark left staged: rename failed")
            true
        } catch (e: IOException) {
            warn("active trip: ended mark not written: ${e::class.simpleName}")
            false
        }
        return finishForgetting() || marked
    }

    // The temp record and the kept file cleared, then the ended mark: whether both were cleared.
    // The mark stays while either couldn't be, standing in for them.
    private fun finishForgetting(): Boolean {
        val staged = invalidate(tmp)
        val kept = invalidate(file)
        if (!staged || !kept) return false
        // A staged mark too, or it would end the next trip; emptied when it can't go, it says nothing.
        if (endedAside.exists() && !delete(endedAside)) {
            try {
                endedAside.writeText("")
            } catch (e: IOException) {
                warn("active trip: staged ended mark not cleared: ${e::class.simpleName}")
                return false
            }
        }
        if (ended.exists() && !delete(ended)) {
            // One left saying so would forget every trip after it: emptied, it says nothing.
            try {
                ended.writeText("")
            } catch (e: IOException) {
                warn("active trip: ended mark not cleared: ${e::class.simpleName}")
                return false
            }
        }
        return true
    }

    private fun invalidate(f: File): Boolean {
        if (!f.exists() || delete(f)) return true
        return try {
            f.writeText("")
            warn("active trip emptied: delete failed")
            true
        } catch (e: IOException) {
            warn("active trip not forgotten: delete and ${e::class.simpleName}")
            false
        }
    }

    // A temp file left by a process that died between writing it and moving it into place ([save]):
    // the newest trip when it's whole, so it's moved into place; one cut short mid-write is dropped.
    // One that can't be moved (nor written in place) is kept for next time, and read from here:
    // the kept file behind it is older. Returns that trip, or null to read the kept file.
    private fun recoverTemp(): ActiveTrip? {
        val recovered = try {
            json.decodeFromString<PersistedActiveTrip>(tmp.readText()).toTrip()
        } catch (e: IOException) {
            // Storage that couldn't be read now: the newest trip may be here, so it's kept and the
            // failure passed on, to be read again, never dropped as partial.
            warn("active trip: temp file unreadable, kept: ${e::class.simpleName}")
            throw e
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: DateTimeParseException) {
            null
        }
        if (recovered == null) {
            warn("active trip: partial temp file dropped")
            if (!tmp.delete()) warn("active trip: stale temp file not deleted")
            return null
        }
        if (rename(tmp, file)) {
            warn("active trip: recovered from temp file")
            return null
        }
        // As [save] does when the rename fails: in place.
        return try {
            file.writeText(tmp.readText())
            warn("active trip: recovered from temp file in place")
            if (!tmp.delete()) warn("active trip: stale temp file not deleted")
            null
        } catch (e: IOException) {
            warn("active trip: temp file not moved into place: ${e::class.simpleName}")
            recovered
        }
    }

    private fun discard(why: String, e: Exception): ActiveTrip? {
        val deleted = file.delete()
        warn("active trip $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return null
    }

    private companion object {
        const val ENDED = "ended"
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedActiveTrip(
    val legs: List<PersistedTripLeg>,
    val destinationName: String,
    val startedAt: String,
    val legIndex: Int,
    val legStartedAt: String,
    val vehicleId: String = "",
    val boardsAt: String? = null,
    val boarded: Boolean = false,
    val boardedAt: String? = null,
    val dueOffAt: String? = null,
    val warnedLeg: Int = -1,
    val alertLeft: Boolean = false,
    val vehicleOffId: String = "",
    val waitFrom: String? = null,
    // Absent from a trip kept before it was stored: taken as not yet seen on board, so its ride's
    // step and board show until location or the rider says otherwise, as for any trip now.
    val onBoardSeen: Boolean = false,
    // Absent from a trip kept before it was stored: not on board by where the rider was seen.
    val seenAlongStop: Int = -1,
    // Absent from a trip kept before it was stored: none said yet, so a train due soon is said for.
    val boardWarned: String = "",
    // Absent from a trip kept before it was stored: nothing heard yet, so what's known is said.
    val disruptionsHeard: List<String> = emptyList(),
    // Absent from a trip kept before it was stored: its train is on the ride's own line, the only
    // one followed then, and named and checked as it.
    val vehicleLeg: PersistedTripLeg? = null,
    // Absent from a trip kept before it was stored: no call yet taken only because the rider was seen
    // at the stop, so the next such reading starts the hold from the train's due time then.
    val heldFrom: String? = null,
    // Absent from a trip kept before they were: where to as chosen is unknown, so a re-plan asks for
    // the stop the route ends at.
    val destinations: List<PersistedTripDestination> = emptyList(),
    val destinationIds: Map<String, String> = emptyMap(),
    // Absent from a trip kept before it was: planning again opens the trip list from the route's
    // own stops alone.
    val destinationStopId: String = "",
    // The walks decided to be changes on foot when the trip started, by leg index. Absent from a trip
    // kept before it was: its walks go by their names alone, as that trip was shown.
    val onFootChanges: List<Int>? = null,
) {
    fun toTrip() = ActiveTrip(
        route = TripRoute(legs.map { it.toLeg() }),
        destinationName = destinationName,
        startedAt = Instant.parse(startedAt),
        legIndex = legIndex,
        legStartedAt = Instant.parse(legStartedAt),
        vehicleId = vehicleId,
        boardsAt = boardsAt?.let(Instant::parse),
        boarded = boarded,
        boardedAt = boardedAt?.let(Instant::parse),
        dueOffAt = dueOffAt?.let(Instant::parse),
        warnedLeg = warnedLeg,
        alertLeft = alertLeft,
        vehicleOffId = vehicleOffId,
        waitFrom = waitFrom?.let(Instant::parse),
        onBoardSeen = onBoardSeen,
        seenAlongStop = seenAlongStop,
        boardWarned = boardWarned,
        disruptionsHeard = disruptionsHeard.toSet(),
        vehicleLeg = vehicleLeg?.toLeg(),
        heldFrom = heldFrom?.let(Instant::parse),
        // One this build can't read (a kind a later one added) is dropped rather than failing the trip.
        destinations = destinations.mapNotNull { it.toDestination() },
        destinationIds = destinationIds,
        destinationStopId = destinationStopId,
        onFootChanges = onFootChanges?.toSet(),
    )

    companion object {
        fun of(trip: ActiveTrip) = PersistedActiveTrip(
            legs = trip.route.legs.map { PersistedTripLeg.of(it) },
            destinationName = trip.destinationName,
            startedAt = trip.startedAt.toString(),
            legIndex = trip.legIndex,
            legStartedAt = trip.legStartedAt.toString(),
            vehicleId = trip.vehicleId,
            boardsAt = trip.boardsAt?.toString(),
            boarded = trip.boarded,
            boardedAt = trip.boardedAt?.toString(),
            dueOffAt = trip.dueOffAt?.toString(),
            warnedLeg = trip.warnedLeg,
            alertLeft = trip.alertLeft,
            vehicleOffId = trip.vehicleOffId,
            waitFrom = trip.waitFrom?.toString(),
            onBoardSeen = trip.onBoardSeen,
            seenAlongStop = trip.seenAlongStop,
            boardWarned = trip.boardWarned,
            disruptionsHeard = trip.disruptionsHeard.sorted(),
            vehicleLeg = trip.vehicleLeg?.let { PersistedTripLeg.of(it) },
            heldFrom = trip.heldFrom?.toString(),
            destinations = trip.destinations.map { PersistedTripDestination.of(it) },
            destinationIds = trip.destinationIds,
            onFootChanges = trip.onFootChanges?.sorted(),
            destinationStopId = trip.destinationStopId,
        )
    }
}

/** A [TripDestination]: a stop by [id], or a place at [latitude], [longitude] named [name]. */
@Serializable
internal data class PersistedTripDestination(
    val kind: String,
    val id: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String = "",
) {
    fun toDestination(): TripDestination? = when (kind) {
        STOP -> id.takeIf { it.isNotBlank() }?.let { TripDestination.Stop(it) }
        PLACE -> if (latitude != null && longitude != null) TripDestination.Place(Coordinates(latitude, longitude), name) else null
        else -> null
    }

    companion object {
        const val STOP = "stop"
        const val PLACE = "place"

        fun of(destination: TripDestination) = when (destination) {
            is TripDestination.Stop -> PersistedTripDestination(STOP, id = destination.id)
            is TripDestination.Place -> PersistedTripDestination(
                PLACE,
                latitude = destination.coordinate.latitude,
                longitude = destination.coordinate.longitude,
                name = destination.name,
            )
        }
    }
}

@Serializable
internal data class PersistedTripLeg(
    val mode: String,
    val lineId: String,
    val lineName: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val departure: String,
    val arrival: String,
    val path: List<String> = emptyList(),
    val pathNames: List<String> = emptyList(),
    val changeAfterSeconds: Long = 0,
    val headings: List<String> = emptyList(),
    val fromArea: String = "",
    val toArea: String = "",
    // Where the leg boards: a public stop position, not the rider's.
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    // And where it gets off, likewise public.
    val toLat: Double? = null,
    val toLon: Double? = null,
    // The stops the Planner named, where an end was moved to the one its bus uses.
    val plannedFromId: String = "",
    val plannedToId: String = "",
) {
    // Renamed on the way back in, like a fresh leg ([riderLineName]), so a trip saved before the rename
    // doesn't resume saying the old name beside the new pill.
    fun toLeg() = TripLeg(
        mode, lineId, riderLineName(lineName, mode), fromId, fromName, toId, toName, Instant.parse(departure), Instant.parse(arrival),
        path, pathNames, Duration.ofSeconds(changeAfterSeconds), headings, fromArea, toArea,
        fromAt = if (fromLat != null && fromLon != null) Coordinates(fromLat, fromLon) else null,
        toAt = if (toLat != null && toLon != null) Coordinates(toLat, toLon) else null,
        plannedFromId = plannedFromId,
        plannedToId = plannedToId,
    )

    companion object {
        fun of(leg: TripLeg) = PersistedTripLeg(
            leg.mode, leg.lineId, leg.lineName, leg.fromId, leg.fromName, leg.toId, leg.toName,
            leg.departure.toString(), leg.arrival.toString(), leg.path, leg.pathNames, leg.changeAfter.seconds, leg.headings,
            leg.fromArea, leg.toArea, leg.fromAt?.latitude, leg.fromAt?.longitude, leg.toAt?.latitude, leg.toAt?.longitude,
            leg.plannedFromId, leg.plannedToId,
        )
    }
}
