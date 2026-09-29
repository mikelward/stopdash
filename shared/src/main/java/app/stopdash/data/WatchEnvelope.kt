package app.stopdash.data

import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineStatus
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredRow
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the phone sends the Wear OS app (dev-docs/wear-os.md *The snapshot over the wire*): the
 * widget's stops as [PersistedStop]s, unchanged, so the watch builds its rows with the widget's
 * own code from the widget's own inputs, plus the starred-row keys the widget pins by (D8).
 *
 * It carries no coordinate and no key ([PersistedStop] has neither), and no starred journeys (an
 * open question in the plan). It does carry the carried stops' line status checks, age-stamped as
 * the widget keeps them ([lineStatuses]); stop closures stay off it, as they aren't persisted.
 * Each stop's nearer-stop
 * lists ([PersistedStop.nearerIds], [PersistedStop.nearerNames]) are location-derived place data,
 * disclosed with the watch sync.
 */
@Serializable
data class WatchEnvelope(
    val version: Int = CURRENT_VERSION,
    val stops: List<PersistedStop> = emptyList(),
    val starred: List<WatchStarKey> = emptyList(),
    /** Stops left out past the transfer ceiling, lowest priority first; the watch says so. */
    val omittedStops: Int = 0,
    /** The widget's stops the last refresh couldn't get ([DeparturesSnapshot.missingStopIds]), so
     *  the watch never shows an incomplete refresh as complete. Past the transfer ceiling, one id
     *  stands for the list: the watch reads only whether it's empty. */
    val missingStopIds: List<String> = emptyList(),
    /** The modes hidden from the near-me list, which the widget leaves out, so the watch does too. */
    val hiddenModes: List<String> = emptyList(),
    /** Each carried line's last status check, stamped ([DeparturesSnapshot.lineStatuses]), so the
     *  watch marks a disrupted service as the widget does and withholds the mark at the same
     *  threshold. Additive: an older watch app ignores it and shows what it did before. */
    val lineStatuses: List<PersistedLineStatus> = emptyList(),
) {
    /** The disruptions to mark at [now], as [DeparturesSnapshot.liveLineStatuses] judges them. */
    fun liveLineStatuses(now: Instant): Map<String, LineStatus> =
        lineStatuses.map { it.toDomain() }
            .filter { it.isLive(now) }
            .mapNotNull { it.shown() }
            .associateBy { it.lineId }

    /** Whether [lineId] has a live check at [now], as [DeparturesSnapshot.statusKnown] judges it. */
    fun statusKnown(lineId: String, now: Instant): Boolean =
        lineId.isNotBlank() && lineStatuses.any { it.lineId == lineId && it.known && it.toDomain().isLive(now) }

    /** When each line check expires (a disruption's mark goes, or the line turns unchecked): a
     *  frame can change there. */
    fun lineStatusExpiries(now: Instant): List<Instant> =
        lineStatuses.map { it.toDomain().checkedAt }
            // A check from the future (the clock moved back) is never live: its expiry changes
            // nothing, and would stretch a timeline toward it.
            .filterNot { it.isAfter(now) }
            .map { it.plus(Staleness.THRESHOLD.toJavaDuration()) }

    /**
     * This envelope with every stamp in it (each stop's fetch, each line check) moved by [by]: as the
     * watch's wall clock reads them after being set by that much since the envelope arrived.
     */
    fun shifted(by: java.time.Duration): WatchEnvelope {
        if (by.isZero) return this
        val millis = by.toMillis()
        return copy(
            stops = stops.map { it.copy(fetchedAtMillis = it.fetchedAtMillis + millis) },
            lineStatuses = lineStatuses.map { it.copy(checkedAtMillis = it.checkedAtMillis + millis) },
        )
    }

    /**
     * This envelope, received in a boot before the one that started at [bootStartMillis] (as the wall
     * clock reads it now): each stop and line check stamped later than that is from before the clock
     * was set back ([PersistedSnapshot.fromEarlierBoot]), so the stop is restamped stale as of the
     * boot's start and the check dropped, the same on every read.
     */
    fun fromEarlierBoot(bootStartMillis: Long): WatchEnvelope =
        copy(
            stops = stops.map { it.notAfter(bootStartMillis) },
            lineStatuses = lineStatuses.filterNot { afterBoot(it.checkedAtMillis, bootStartMillis) },
        )

    /**
     * This envelope as a timeline built at [now] can trust it: without the line checks dated more
     * than a moment after [now] (the clock moved back), and with each stop stamped more than a
     * moment ahead of it restamped stale ([PersistedStop.distrustingFuture]), as the phone's store
     * does on a write. The moment ([Staleness.CLOCK_SKEW]) is the phone's clock running a little
     * ahead of the watch's: a check that's only that far ahead is kept, since the watch keeps what
     * this finds for good, and would otherwise leave the line unchecked until the next envelope
     * (Codex, PR #371). A
     * timeline applies it once, so a frame later in it can't start trusting either when its instant
     * passes, with nothing scheduled to take it away again. A setting of the watch's clock since the
     * envelope arrived is taken out first ([shifted]), and the phone sends its stamps as its wall
     * clock reads them when it sends, so this catches only what neither can: a clock set back
     * across a reboot of either device.
     */
    fun distrustingFuture(now: Instant): WatchEnvelope =
        copy(
            stops = stops.map { it.distrustingFuture(now) },
            lineStatuses = lineStatuses.filterNot { checkAhead(it.checkedAtMillis, now) },
        )

    companion object {
        /** Bump on any change an older watch app would misread; it refuses rather than guesses. */
        const val CURRENT_VERSION = 1
    }
}

/** A starred row's identity, keyed like [StarredRow]: the resolved direction key, not TfL's raw
 *  `direction`, so blank-direction siblings at one stop stay distinct. */
@Serializable
data class WatchStarKey(val stopId: String, val lineId: String, val directionKey: String) {
    fun toDomain(): StarredRow = StarredRow(stopId, lineId, directionKey)

    companion object {
        fun of(row: StarredRow): WatchStarKey = WatchStarKey(row.stopId, row.lineId, row.directionKey)
    }
}

/** An encoded envelope, and whether it's too big for a `DataItem` and must go as an `Asset`. */
class WatchPayload(val envelope: WatchEnvelope, val bytes: ByteArray, val asAsset: Boolean)

/**
 * How a received envelope decoded: the envelope, a version this build refuses, or unreadable. The
 * caller logs a refusal; [Unreadable.reason] names why decoding failed (a failure type, never the
 * payload, which is the user's stops), so wire corruption and a serializer regression can be told
 * apart in the log.
 */
sealed interface WatchDecode {
    data class Ok(val envelope: WatchEnvelope) : WatchDecode
    data class UnsupportedVersion(val version: Int) : WatchDecode
    data class Unreadable(val reason: String) : WatchDecode
}

object WatchEnvelopes {
    /** Comfortably under the Data Layer's ~100 KB `DataItem` limit. */
    const val DATA_ITEM_BUDGET_BYTES = 80_000

    /** A sanity bound on one transfer (and the watch's battery), far above any realistic set. */
    const val TRANSFER_CEILING_BYTES = 1_000_000

    /** Departures kept per destination group past a stop's staleness boundary: the most any
     *  shared renderer shows for one group (the widget's and the app's countdown lists). */
    const val PER_GROUP_CAP = 3

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The envelope for [snapshot] with the user's [starred] rows, encoded and sized.
     *
     * - Journey-only stops are dropped: the widget shows only the journey there, and journeys
     *   don't go to the watch.
     * - Each stop's departures are trimmed to its freshness window: all before its staleness
     *   boundary, plus up to [perGroupCap] per destination group after it, so a fresh snapshot
     *   whose next departures all fall past the boundary still fills every countdown list.
     * - Over [dataItemBudget], the whole envelope goes as an `Asset`. Only past
     *   [transferCeiling] are stops dropped, the lowest-ranked first by the order the widget
     *   renders at [now] (fresh rows ahead of stale, favorites pinned; a stop with no row at all
     *   ranks last), holding back any stop with a starred row or a row a complication is set to
     *   ([selected]) until no other is left. The ceiling is a hard bound: past it even those go,
     *   lowest-ranked first. The count is recorded so the watch can say so. Before any stop goes,
     *   the missing-stop list shrinks to one id, which still marks the envelope incomplete.
     */
    fun build(
        snapshot: DeparturesSnapshot,
        starred: Set<StarredRow>,
        selected: Set<StarredRow> = emptySet(),
        hiddenModes: Set<String> = emptySet(),
        threshold: Duration = Staleness.THRESHOLD,
        perGroupCap: Int = PER_GROUP_CAP,
        dataItemBudget: Int = DATA_ITEM_BUDGET_BYTES,
        transferCeiling: Int = TRANSFER_CEILING_BYTES,
        now: Instant = Instant.now(),
    ): WatchPayload {
        val stops = snapshot.stops
            .filterNot { it.stopId in snapshot.journeyOnlyStopIds }
            .map { stop ->
                // Sent as the wall clock reads the fetch now ([SteadyClock.toWall]): the watch ages it
                // by its own clock, which isn't this one's steady frame.
                val fetchedAt = SteadyClock.toWall(stop.fetchedAt)
                val boundary = fetchedAt.plus(threshold.toJavaDuration())
                stop.toPersisted().let {
                    it.copy(fetchedAtMillis = fetchedAt.toEpochMilli(), departures = trim(it.departures, boundary.toEpochMilli(), now.toEpochMilli(), perGroupCap))
                }
            }
        // Only the keys for stops the envelope carries: a star elsewhere pins nothing on the watch,
        // and leaving it out keeps the non-stop part of the payload bounded by the stops sent.
        val allKeys = starred.map(WatchStarKey::of).sortedWith(compareBy({ it.stopId }, { it.lineId }, { it.directionKey }))
        fun keysFor(kept: List<PersistedStop>): List<WatchStarKey> {
            val ids = kept.mapTo(HashSet()) { it.stopId }
            return allKeys.filter { it.stopId in ids }
        }
        // A star or a complication's pick protects its stop while the widget would still show its
        // row ([DepartureRows.shows]): a stop with no predictions right now still counts (the watch
        // shows the row's empty form), but a hidden mode's row, or one whose services the
        // terminating filter removes, protects nothing: the watch leaves it out.
        fun shown(row: StarredRow): Boolean {
            val stop = snapshot.stops.firstOrNull { it.stopId == row.stopId } ?: return false
            return DepartureRows.shows(stop, row, hiddenModes, now)
        }
        val protectedStops = (starred + selected).filter(::shown).mapTo(HashSet()) { it.stopId }
        val missing = (snapshot.missingStopIds - snapshot.journeyOnlyStopIds).sorted()

        val hidden = hiddenModes.sorted()
        // The line checks for the lines the kept stops show, so a dropped stop's lines go with it.
        // Less the alert fingerprint: only the phone matches a dismissal against it, and the
        // watch reads the dismissed flag the phone already set.
        val allStatuses = snapshot.lineStatuses.toPersistedStatuses()
            .map { status -> status.copy(fingerprint = null, directions = status.directions.map { it.copy(fingerprint = null) }) }
        fun statusesFor(kept: List<PersistedStop>): List<PersistedLineStatus> {
            val lines = linesOfPersisted(kept)
            return allStatuses.filter { it.lineId in lines }
        }
        var envelope = WatchEnvelope(
            stops = stops,
            starred = keysFor(stops),
            missingStopIds = missing,
            hiddenModes = hidden,
            lineStatuses = statusesFor(stops),
        )
        var bytes = encode(envelope)
        if (bytes.size <= dataItemBudget) return WatchPayload(envelope, bytes, asAsset = false)
        if (bytes.size <= transferCeiling) return WatchPayload(envelope, bytes, asAsset = true)
        val ranked = rankedStopIds(snapshot.stops.filterNot { it.stopId in snapshot.journeyOnlyStopIds }, starred, hiddenModes, threshold, now, snapshot.liveLineStatuses(now))
        val dropOrder = ranked.reversed().let { low -> low.filterNot { it in protectedStops } + low.filter { it in protectedStops } }
        // The watch reads only whether any stop is missing, so past the ceiling one id keeps the
        // flag, and the list can't hold the payload over the bound on its own.
        val missingFlag = missing.take(1)
        envelope = envelope.copy(missingStopIds = missingFlag)
        bytes = encode(envelope)
        val dropped = HashSet<String>()
        for (id in dropOrder) {
            if (bytes.size <= transferCeiling) break
            dropped += id
            val kept = stops.filterNot { it.stopId in dropped }
            envelope = WatchEnvelope(
                stops = kept,
                starred = keysFor(kept),
                omittedStops = stops.size - kept.size,
                missingStopIds = missingFlag,
                hiddenModes = hidden,
                lineStatuses = statusesFor(kept),
            )
            bytes = encode(envelope)
        }
        return WatchPayload(envelope, bytes, asAsset = true)
    }

    /** The stops by where their first row lands in the widget's order at [now], best first; a hidden
     *  mode's rows don't count, as the widget leaves them out. */
    private fun rankedStopIds(
        stops: List<StopArrivals>,
        starred: Set<StarredRow>,
        hiddenModes: Set<String>,
        threshold: Duration,
        now: Instant,
        // A suspended line's status row ranks its stop as the widget would.
        lineStatuses: Map<String, LineStatus>,
    ): List<String> {
        val staleStop = stops.associate { it.stopId to Staleness.isStale(it.fetchedAt, now, threshold) }
        // Fresh first as the widget orders them, a live suspension's status row counting as fresh.
        val rows = DepartureRows.freshFirst(
            HiddenModes.rows(
                DepartureRows.across(stops, now, lineStatuses, splitPlatforms = false, statusRowsWhenStale = true),
                hiddenModes,
            ),
        ) { staleStop[it.stopId] == true }
        val byRow = DepartureRows.pinStarred(rows, starred, warningsLead = false).map { it.stopId }.distinct()
        return byRow + stops.map { it.stopId }.filterNot { it in byRow }
    }

    fun encode(envelope: WatchEnvelope): ByteArray = json.encodeToString(WatchEnvelope.serializer(), envelope).encodeToByteArray()

    /** Decodes [bytes], refusing a version this build doesn't know rather than misreading it. */
    fun decode(bytes: ByteArray): WatchDecode {
        val text = bytes.decodeToString()
        return try {
            val version = json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.int
                ?: return WatchDecode.Unreadable("no version")
            if (version != WatchEnvelope.CURRENT_VERSION) return WatchDecode.UnsupportedVersion(version)
            WatchDecode.Ok(json.decodeFromString(WatchEnvelope.serializer(), text))
        } catch (e: SerializationException) {
            WatchDecode.Unreadable(e::class.simpleName ?: "SerializationException")
        } catch (e: IllegalArgumentException) {
            WatchDecode.Unreadable(e::class.simpleName ?: "IllegalArgumentException")
        }
    }

    /**
     * [departures] still to come at [nowMillis]: every one before [boundaryMillis], plus the soonest
     * [cap] at or after it per group, in their original order. One that has already departed is
     * dropped first (no surface shows it), so it can't spend a group's cap. Groups are finer than
     * the renderers' destination groups (line, direction, platform, destination, terminus ID and
     * via branch), so every coarser group keeps at least [cap] too; a branch keeps its own [cap], and
     * a service the terminating filter later hides ([PersistedDeparture.destinationId]) can't crowd
     * out one to a same-named terminus it keeps.
     *
     * The exception: before [boundaryMillis], a row direction (line and direction key) with nothing
     * still to come keeps its departed services. No surface counts them down, but they're the
     * evidence [DepartureRows.shows] judges the row on, so a row the terminating filter removed
     * stays removed on the watch after its services leave, as it does on the phone.
     */
    private fun trim(departures: List<PersistedDeparture>, boundaryMillis: Long, nowMillis: Long, cap: Int): List<PersistedDeparture> {
        fun rowOf(d: PersistedDeparture) = d.lineId to DepartureRows.directionKeyOf(d.toDomain())
        val live = departures.filter { it.expectedArrivalMillis > nowMillis }.mapTo(HashSet(), ::rowOf)
        val evidence = if (nowMillis >= boundaryMillis) emptySet() else departures.withIndex()
            .filter { (_, d) -> d.expectedArrivalMillis <= nowMillis && rowOf(d) !in live }
            .mapTo(HashSet()) { it.index }
        val upcoming = departures.withIndex().filter { it.value.expectedArrivalMillis > nowMillis }
        val afterBoundary = upcoming
            .filter { it.value.expectedArrivalMillis >= boundaryMillis }
            .groupBy { (_, d) -> listOf(d.lineId, d.direction, d.platform, d.destination, d.destinationId, d.branch) }
            .values
            .flatMap { group -> group.sortedBy { it.value.expectedArrivalMillis }.take(cap) }
            .mapTo(HashSet()) { it.index }
        val kept = upcoming.filter { it.value.expectedArrivalMillis < boundaryMillis || it.index in afterBoundary }
            .mapTo(HashSet()) { it.index } + evidence
        return departures.filterIndexed { index, _ -> index in kept }
    }
}
