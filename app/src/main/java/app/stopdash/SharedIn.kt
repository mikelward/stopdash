package app.stopdash

import androidx.annotation.WorkerThread
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
 * text can be any length up to [SharedPlace.MAX_SHARED]. Null when there is nothing to go on. [parse] is the
 * reading itself, swapped only by a test watching where it runs.
 */
internal suspend fun readSharedIn(
    shared: SharedIn,
    compute: CoroutineDispatcher = Workers.compute,
    parse: (SharedIn) -> SharedPlace.Ask? = ::askOf,
): SharedPlace.Ask? = withContext(compute) { parse(shared) }

@WorkerThread
private fun askOf(shared: SharedIn): SharedPlace.Ask? =
    if (shared.geo != null) {
        SharedPlace.fromGeo(shared.geo)
    } else {
        SharedPlace.queryOf(shared.text, shared.subject)?.let(SharedPlace.Ask::Search)
    }
