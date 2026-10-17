package app.stopdash.data

import app.stopdash.BuildConfig

/**
 * stopdash's National Rail relay (`dev-docs/national-rail-proxy.md`): National Rail's incidents and live
 * departure boards with stopdash's own keys, so a rider needs none (SPEC *National Rail*). A rider's own
 * key still goes straight to National Rail, as before the relay.
 */
object RailRelay {
    /** The relay's address, from `stopdash.railRelayUrl`; null in a build with none. */
    val url: String? = BuildConfig.RAIL_RELAY_URL.trim().trimEnd('/').ifEmpty { null }

    /** Whether National Rail times can be had with [key] (the rider's own, or none): with it, or through the relay. */
    fun timesWith(key: String?, relay: String? = url): Boolean = !key.isNullOrBlank() || relay != null
}
