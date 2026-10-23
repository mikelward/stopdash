package app.stopdash.data

import androidx.annotation.WorkerThread
import app.stopdash.StopdashDebugLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Favorite rides as the sync engine's entries (dev-docs/drive-sync.md; TODO.md *Rides through the
 * engine*): the rides, their alert times and the grayed rides, each kind by its own id, each value the
 * entry as its file stores it, so [app.stopdash.domain.Sync] compares and merges them without knowing
 * what a ride is. Unused until the sync runner calls it; nothing leaves the phone.
 *
 * A ride is one entry by its segment, either way round, as the store keeps one ride per segment; an
 * alert time by its direction (`JourneyAlerts.directionKey`); a grayed ride by its two ends. Values are
 * read and written as this build's file reads and writes them: a newer build's extra fields are read
 * past ([same]), and a value in a shape this build can't read is left alone ([understood]).
 */
internal object RideSync {
    const val RIDES = "rides"
    const val ALERTS = "ride-alerts"
    const val GRAYED = "grayed-rides"
    val KINDS = listOf(RIDES, ALERTS, GRAYED)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The entries [stored] holds, by kind and id; null for a file from a newer build, which this one
     * neither reads nor syncs. Nothing stored is no entries of any kind.
     */
    @WorkerThread
    fun entriesOf(stored: PersistedFavoriteJourneys?): Map<String, Map<String, String>>? {
        if (stored == null) return KINDS.associateWith { emptyMap() }
        if (stored.version > PersistedFavoriteJourneys.CURRENT_VERSION) return null
        // One ride per segment, the first standing for both, as the store reads them.
        val rides = LinkedHashMap<String, String>()
        for (ride in stored.journeys) rides.putIfAbsent(keyOf(ride), encode(PersistedFavoriteJourney.serializer(), ride))
        // A ride's alert times only, as the store reads them: one of a ride not saved can't be found.
        val alerts = stored.alerts
            .filterKeys { rideKeyOf(it) in rides }
            .mapValues { (_, schedule) -> encode(PersistedAlertSchedule.serializer(), schedule) }
        val grayed = LinkedHashMap<String, String>()
        for (ride in stored.pending) grayed.putIfAbsent(keyOf(ride), encode(PersistedPendingJourney.serializer(), ride))
        return mapOf(RIDES to rides, ALERTS to alerts, GRAYED to grayed)
    }

    /**
     * [stored] holding [merged]'s entries ([app.stopdash.domain.Sync.valuesOf] of each kind) and no
     * others: a ride kept stays where it was in the list, one new to this phone joins the end, and alert
     * times go with their ride. A value this build can't read never replaces what's stored, nor is added.
     */
    @WorkerThread
    fun applied(stored: PersistedFavoriteJourneys?, merged: Map<String, Map<String, String>>): PersistedFavoriteJourneys {
        val base = stored ?: PersistedFavoriteJourneys()
        val rides = mergedList(base.journeys, merged[RIDES].orEmpty(), PersistedFavoriteJourney.serializer(), ::keyOf)
        val rideKeys = rides.mapTo(HashSet(), ::keyOf)
        val alerts = LinkedHashMap<String, PersistedAlertSchedule>()
        for ((key, value) in merged[ALERTS].orEmpty().toSortedMap()) {
            if (rideKeyOf(key) !in rideKeys) continue
            (decode(PersistedAlertSchedule.serializer(), value) ?: base.alerts[key])?.let { alerts[key] = it }
        }
        val grayed = mergedList(base.pending, merged[GRAYED].orEmpty(), PersistedPendingJourney.serializer(), ::keyOf)
        return base.copy(version = PersistedFavoriteJourneys.CURRENT_VERSION, journeys = rides, alerts = alerts, pending = grayed)
    }

    /** Whether this build can read [kind]'s [value] at all ([app.stopdash.domain.Sync.record]'s `understood`). */
    @WorkerThread
    fun understood(kind: String, value: String): Boolean = decode(serializerOf(kind), value) != null

    /**
     * Whether two of [kind]'s values are the same entry as this build reads them: a newer build's extra
     * fields don't make them differ ([app.stopdash.domain.Sync.record]'s and `changes`' `same`).
     */
    @WorkerThread
    fun same(kind: String, a: String, b: String): Boolean {
        if (a == b) return true
        val serializer = serializerOf(kind)
        val left = decode(serializer, a) ?: return false
        return left == decode(serializer, b)
    }

    // [stored]'s items held in [merged], in their order and as [merged] has them, then [merged]'s others
    // in id order. One whose merged value can't be read keeps what's stored, or isn't added.
    private fun <T : Any> mergedList(stored: List<T>, merged: Map<String, String>, serializer: KSerializer<T>, key: (T) -> String): List<T> {
        val result = ArrayList<T>(merged.size)
        val placed = HashSet<String>()
        for (item in stored) {
            val id = key(item)
            if (!placed.add(id)) continue
            val value = merged[id] ?: continue
            result += decode(serializer, value) ?: item
        }
        for ((id, value) in merged.toSortedMap()) {
            if (id in placed) continue
            decode(serializer, value)?.let { result += it }
        }
        return result
    }

    private fun serializerOf(kind: String): KSerializer<out Any> = when (kind) {
        RIDES -> PersistedFavoriteJourney.serializer()
        ALERTS -> PersistedAlertSchedule.serializer()
        GRAYED -> PersistedPendingJourney.serializer()
        else -> throw IllegalArgumentException("not a ride kind: $kind")
    }

    // A ride's segment, either way round, as FavoriteJourney.key gives it.
    private fun keyOf(ride: PersistedFavoriteJourney): String = listOf(ride.from.stopId, ride.to.stopId).sorted().joinToString("|")

    // A grayed ride's two ends, either way round, as PendingJourney.key gives them.
    private fun keyOf(ride: PersistedPendingJourney): String {
        val from = "${ride.from.kind}:${ride.from.id}"
        val to = "${ride.to.kind}:${ride.to.id}"
        return if (from <= to) "$from|$to" else "$to|$from"
    }

    // The ride an alert time's direction belongs to (JourneyAlerts.journeyKey).
    private fun rideKeyOf(directionKey: String): String = directionKey.substringBefore('>')

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    // Null for a value this build can't read (a newer build's shape), which the caller leaves alone, as
    // it's meant to: noted by the exception's type and first frames, never the value, which holds the
    // rider's stops.
    private fun <T> decode(serializer: KSerializer<T>, value: String): T? = try {
        json.decodeFromString(serializer, value)
    } catch (e: SerializationException) {
        unread(e)
    } catch (e: IllegalArgumentException) {
        unread(e)
    }

    private fun unread(e: Exception): Nothing? {
        val at = e.stackTrace.take(STACK_FRAMES).joinToString("") { " at $it" }
        StopdashDebugLog.info("sync: a ride entry this build can't read (%s%s)", e.javaClass.simpleName, at)
        return null
    }

    private const val STACK_FRAMES = 3
}
