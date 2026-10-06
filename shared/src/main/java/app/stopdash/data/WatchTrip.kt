package app.stopdash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A trip on the way, as the watch shows it (dev-docs/wear-os.md *Trip on the way*): every step as
 * the phone's trip screen lists them ([steps]), the one the rider is at ([current]), what to do
 * there now ([title], [detail]), and the trains for the next ride ([departures], at step
 * [departuresAt], with what the trip's screen says of them, [departuresNote]). Its own `DataItem` ([WatchSyncContract.TRIP_PATH]), apart from the snapshot
 * envelope: a trip refreshes on its own cadence, and is taken off the watch when it ends.
 *
 * The phone writes every word in its own language, so the watch renders text and adds none of
 * its own beyond its controls. Times are epoch milliseconds on the phone's clock, as the
 * envelope's are. [sentAt] says when the phone wrote it; the watch also times it by its own clock
 * from when it arrived, so a trip the phone stopped updating reads as out of date, then goes.
 */
@Serializable
data class WatchTrip(
    val version: Int = CURRENT_VERSION,
    val title: String,
    val detail: String = "",
    val steps: List<Step>,
    val current: Int,
    // The trains that take the rider on the next ride, soonest first; empty when none are known.
    val departures: List<Train> = emptyList(),
    // The step those trains are boarded at: the next ride's boarding step, or -1 with none.
    val departuresAt: Int = -1,
    // What the trip's screen says of those trains, in the phone's words: their update failed, they're
    // being updated, loading, or none go there. Empty when the trains speak for themselves.
    val departuresNote: String = "",
    // The trains from a board too old to stand behind (SPEC D4), in place of [departures], drawn as
    // marked guesses ("21:14?") as the trip's screen draws them. A field of their own, not a flag on
    // [departures]: a watch that doesn't know it ignores it and shows only [departuresNote], never an
    // old board's times as live.
    val oldDepartures: List<Train> = emptyList(),
    val sentAt: Long,
    // When the trip was started on the phone, epoch milliseconds: which trip this is, so the same
    // route followed again reads as another trip. 0 from a phone that didn't say.
    val startedAt: Long = 0,
) {
    /** One step: a walk, boarding a ride, or getting off it. [text] is the phone's words for it. */
    @Serializable
    data class Step(
        val text: String,
        val lineId: String = "",
        val lineName: String = "",
        val mode: String = "",
        val walk: Boolean = false,
    )

    /**
     * A train for the next ride: its line, where it's going, and when it's due. [stop] is the pole it
     * boards at ("Stop N", or the pole's name), when the stop pair's other poles have trains too, as
     * the phone heads each pole's; empty otherwise. [missed]: it leaves before the rider can be there,
     * grayed as the phone grays it.
     */
    @Serializable
    data class Train(
        val lineId: String,
        val lineName: String,
        val mode: String,
        val destination: String,
        val dueAt: Long,
        val stop: String = "",
        val missed: Boolean = false,
    )

    companion object {
        const val CURRENT_VERSION = 1

        /** Trains sent for the next ride: what a round screen shows under a step. */
        const val TRAINS_CAP = 3

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** [trip] as bytes, serialized on [dispatcher], so a caller on the main thread never does. */
        suspend fun encode(trip: WatchTrip, dispatcher: CoroutineDispatcher = Dispatchers.Default): ByteArray =
            withContext(dispatcher) { json.encodeToString(serializer(), trip).encodeToByteArray() }

        /**
         * [bytes] as a trip, or null for a version this build doesn't know or bytes it can't read,
         * with [log] told which (a failure type, never the trip, which is where the rider is going).
         * Parses on [dispatcher], so a caller on the main thread never does.
         */
        suspend fun decode(
            bytes: ByteArray,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            log: (String) -> Unit,
        ): WatchTrip? = withContext(dispatcher) { parse(bytes, log) }

        private fun parse(bytes: ByteArray, log: (String) -> Unit): WatchTrip? {
            val text = bytes.decodeToString()
            return try {
                val version = json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.int
                if (version != CURRENT_VERSION) {
                    log("trip version unsupported: $version")
                    return null
                }
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                log("trip unreadable: ${e::class.simpleName}")
                null
            } catch (e: IllegalArgumentException) {
                log("trip unreadable: ${e::class.simpleName}")
                null
            }
        }
    }
}
