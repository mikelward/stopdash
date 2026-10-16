package app.stopdash

import androidx.annotation.WorkerThread
import app.stopdash.data.MapsLinkMemo
import app.stopdash.domain.PlaceIndex
import app.stopdash.domain.SharedPlace
import app.stopdash.domain.Workers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A place shared in ([text], with any [subject]) or opened with StopDash ([geo], a `geo:` link), as it
 * came, for [SharedPlace] to read off the main thread (SPEC *Sharing a place in*).
 */
internal data class SharedIn(val text: String? = null, val subject: String? = null, val geo: String? = null)

/**
 * What [shared] asks for, worked out on [compute] (AGENTS.md *Main thread: read and dispatch only*): shared
 * text can be any length up to [SharedPlace.MAX_SHARED]. A Google Maps link in it is read for the exact spot,
 * a short one first [follow]ed to the link it stands for (one request to Google). Failing that, an address
 * with a postcode the bundled [places] hold plans to the postcode, on the device, titled by its street (a
 * shared Calendar event's place, maintainer 2026-10-13); one they don't hold searches the postcode, as
 * typing it does. A Maps link that names its place only by text, as the Maps app's own short links lead to,
 * is read for its address's postcode the same way, titled by the shared name (maintainer, 2026-10-16).
 * Otherwise the share's name is searched. Null when there is nothing to go on. [log] hears
 * what kind of share it was and how it was read, never what it said.
 */
internal suspend fun readSharedIn(
    shared: SharedIn,
    compute: CoroutineDispatcher = Workers.compute,
    places: suspend () -> PlaceIndex = { PlaceIndex.EMPTY },
    log: (String) -> Unit = {},
    follow: suspend (String) -> String? = { MapsLinkMemo.shared.follow(it) },
): SharedPlace.Ask? = withContext(compute) {
    val kind = if (shared.geo != null) "geo link" else "text"
    val (ask, how) = readAsk(shared, follow, places)
    log("share: $kind, read as $how")
    ask
}

@WorkerThread
private suspend fun readAsk(
    shared: SharedIn,
    follow: suspend (String) -> String?,
    places: suspend () -> PlaceIndex,
): Pair<SharedPlace.Ask?, String> {
    if (shared.geo != null) {
        addressAsk(SharedPlace.addressesOf(SharedPlace.geoText(shared.geo)), places)?.let { return it }
        return when (val ask = SharedPlace.fromGeo(shared.geo)) {
            is SharedPlace.Ask.Point -> ask to "a point"
            is SharedPlace.Ask.Search -> ask to "a search"
            null -> null to "nothing"
        }
    }
    val name = SharedPlace.queryOf(shared.text, shared.subject)
    val link = mapsLink(shared, follow)
    val fromLink = link?.let { SharedPlace.fromMapsUrl(it, name) }
    if (fromLink is SharedPlace.Ask.Point) return fromLink to "a Maps link's spot"
    addressAsk(SharedPlace.addressesOf(SharedPlace.lineOf(shared.text, shared.subject)), places)?.let { return it }
    // The Maps app's own short links lead to the place's name and address, never its spot.
    link?.let(SharedPlace::mapsTextOf)?.let { text ->
        addressAsk(SharedPlace.addressesOf(text), places, label = name)?.let { (ask, how) -> return ask to "$how in a Maps link" }
    }
    // A link naming its place only by text searches that, but a name shared beside it reads better in To….
    return when (fromLink) {
        is SharedPlace.Ask.Search -> SharedPlace.Ask.Search(name ?: fromLink.query) to "a Maps link's search"
        else -> (name?.let(SharedPlace.Ask::Search) ?: return null to "nothing") to "a search"
    }
}

// The first of [addresses] whose postcode is bundled: its spot, titled by [label] or its street. Failing that, the
// last written in capitals is searched, as an address ends with its postcode; one in lowercase may not be
// a postcode at all. Null where neither, and the share reads as before. The index loads only for a candidate.
// All of them are looked up in one pass over the index, which a share given up on stops.
@WorkerThread
private suspend fun addressAsk(
    addresses: List<SharedPlace.Address>,
    places: suspend () -> PlaceIndex,
    label: String? = null,
): Pair<SharedPlace.Ask, String>? {
    if (addresses.isEmpty()) return null
    val context = currentCoroutineContext()
    val bundled = places().postcodesAmong(addresses.map { it.postcode }) { context.ensureActive() }
    for (address in addresses) {
        val at = bundled[address.postcode] ?: continue
        return SharedPlace.Ask.Point(at, label ?: address.label) to "a bundled postcode"
    }
    return addresses.lastOrNull { it.capitals }?.let { SharedPlace.Ask.Search(it.postcode) to "a postcode search" }
}

// The share's Google Maps link as a full one, a short one [follow]ed; null where it has none or it can't be followed.
@WorkerThread
private suspend fun mapsLink(shared: SharedIn, follow: suspend (String) -> String?): String? {
    val link = SharedPlace.mapsLinkOf(shared.text, shared.subject) ?: return null
    return if (SharedPlace.isShortMapsLink(link)) follow(link) else link
}
