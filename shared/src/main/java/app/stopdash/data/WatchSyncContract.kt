package app.stopdash.data

/**
 * The names both apps agree on for the phone-to-watch sync (dev-docs/wear-os.md), kept in one
 * place so neither side can drift from the other.
 */
object WatchSyncContract {
    /** The `DataItem` path the phone writes the latest [WatchEnvelope] to. */
    const val SNAPSHOT_PATH = "/stopdash/snapshot"

    /** The envelope's key in that item: a byte array, or an `Asset` when it's too big for one. */
    const val ENVELOPE_KEY = "envelope"

    /** The phone's write generation, different on every write. It makes every write a change, so
     *  a forced republish of the same envelope still reaches the watch as a new `DATA_CHANGED`
     *  event. It carries no ordering: the phone serializes its writes, and the Data Layer delivers
     *  them to the watch in order. */
    const val GENERATION_KEY = "generation"

    /** The `DataItem` path a watch writes the rows its complications are set to, so the phone
     *  keeps them in every envelope ([WatchComplicationRows]). */
    const val COMPLICATION_ROWS_PATH = "/stopdash/complication-rows"

    /** The rows' key in that item: [WatchComplicationRows.encode]'s bytes. */
    const val COMPLICATION_ROWS_KEY = "rows"

    /** The message path a watch sends to ask the phone for one refresh of the widget's stops. */
    const val REFRESH_PATH = "/stopdash/refresh"

    /** The message path the phone acknowledges a refresh request on the moment it arrives, with
     *  the request's id, so the watch knows it's in reach even while the refresh is queued. */
    const val REFRESH_ACK_PATH = "/stopdash/refresh-ack"

    /** The message path the phone answers a refresh request on, with a [WatchRefreshOutcome]. */
    const val REFRESH_RESULT_PATH = "/stopdash/refresh-result"

    /** The `DataItem` path the phone writes a trip on the way to ([WatchTrip]), deleted when the
     *  trip ends. Apart from [SNAPSHOT_PATH]: a trip changes on its own cadence. */
    const val TRIP_PATH = "/stopdash/trip"

    /** The trip's key in that item: [WatchTrip.encode]'s bytes. */
    const val TRIP_KEY = "trip"

    /** Declared by the watch app (res/values/wear.xml), so the phone publishes only when a paired
     *  watch has it installed. */
    const val WATCH_CAPABILITY = "stopdash_watch"
}
