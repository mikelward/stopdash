package app.stopdash

import androidx.annotation.WorkerThread
import app.stopdash.data.MapsLinkMemo
import app.stopdash.domain.SharedPlace
import app.stopdash.domain.Workers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A place shared in ([text], with any [subject]) or opened with StopDash ([geo], a `geo:` link), as it
 * came, for [SharedPlace] to read off the main thread (SPEC *Sharing a place in*).
 */
internal data class SharedIn(val text: String? = null, val subject: String? = null, val geo: String? = null)

/**
 * What [shared] asks for, worked out on [compute] (AGENTS.md *Main thread: read and dispatch only*): shared
 * text can be any length up to [SharedPlace.MAX_SHARED]. A Google Maps link in it is read for the exact spot,
 * a short one first [follow]ed to the link it stands for (one request to Google); where that names no spot,
 * or can't be followed, the share's name is searched as before. Null when there is nothing to go on.
 */
internal suspend fun readSharedIn(
    shared: SharedIn,
    compute: CoroutineDispatcher = Workers.compute,
    follow: suspend (String) -> String? = { MapsLinkMemo.shared.follow(it) },
): SharedPlace.Ask? = withContext(compute) {
    if (shared.geo != null) return@withContext SharedPlace.fromGeo(shared.geo)
    val name = SharedPlace.queryOf(shared.text, shared.subject)
    // A link naming its place only by text searches that, but a name shared beside it reads better in To….
    when (val fromLink = mapsAsk(shared, name, follow)) {
        is SharedPlace.Ask.Point -> fromLink
        is SharedPlace.Ask.Search -> SharedPlace.Ask.Search(name ?: fromLink.query)
        null -> name?.let(SharedPlace.Ask::Search)
    }
}

// What the share's Google Maps link asks for, or null where it has none or names nothing.
@WorkerThread
private suspend fun mapsAsk(shared: SharedIn, name: String?, follow: suspend (String) -> String?): SharedPlace.Ask? {
    val link = SharedPlace.mapsLinkOf(shared.text, shared.subject) ?: return null
    val full = if (SharedPlace.isShortMapsLink(link)) follow(link) ?: return null else link
    return SharedPlace.fromMapsUrl(full, name)
}
