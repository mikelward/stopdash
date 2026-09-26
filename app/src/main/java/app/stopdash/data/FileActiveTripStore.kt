package app.stopdash.data

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The trip on the way (SPEC *On the way*), in one JSON [file] in the app's no-backup directory: it
 * survives the app being closed but not a device transfer, and never leaves the device — where a
 * rider is going is theirs (`docs/PRIVACY.md`). Blocking; call it off the main thread. An unreadable
 * or unparseable file loads as no trip and is deleted; a failed write is logged (a bare reason, never
 * a stop) and the trip is followed in memory only.
 */
internal class FileActiveTripStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
    private val delete: (File) -> Boolean = File::delete,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun load(): ActiveTrip? {
        if (tmp.exists()) recoverTemp()?.let { return it }
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            // An ended trip whose file couldn't be deleted ([save]): no trip.
            if (text.isBlank()) return null
            json.decodeFromString<PersistedActiveTrip>(text).toTrip()
        } catch (e: IOException) {
            discard("unreadable", e)
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

    // The kept trip forgotten: both the file and any temp record behind it ([recoverTemp]) deleted,
    // or emptied when they can't be (an empty one loads as no trip). Whether both are gone.
    private fun forget(): Boolean {
        val staged = invalidate(tmp)
        return invalidate(file) && staged
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
            null
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
    val dueOffAt: String? = null,
    val warnedLeg: Int = -1,
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
        dueOffAt = dueOffAt?.let(Instant::parse),
        warnedLeg = warnedLeg,
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
            dueOffAt = trip.dueOffAt?.toString(),
            warnedLeg = trip.warnedLeg,
        )
    }
}

@Serializable
private data class PersistedTripLeg(
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
) {
    fun toLeg() = TripLeg(
        mode, lineId, lineName, fromId, fromName, toId, toName, Instant.parse(departure), Instant.parse(arrival),
        path, pathNames, Duration.ofSeconds(changeAfterSeconds), headings, fromArea, toArea,
    )

    companion object {
        fun of(leg: TripLeg) = PersistedTripLeg(
            leg.mode, leg.lineId, leg.lineName, leg.fromId, leg.fromName, leg.toId, leg.toName,
            leg.departure.toString(), leg.arrival.toString(), leg.path, leg.pathNames, leg.changeAfter.seconds, leg.headings,
            leg.fromArea, leg.toArea,
        )
    }
}
