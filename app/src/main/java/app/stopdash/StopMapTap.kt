package app.stopdash

import app.stopdash.domain.StopMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A tap on a stop's map button: the `geo:` link is built on [dispatcher] (encoding walks every
 * byte of the name, so it is kept off the main thread, AGENTS.md *Main thread: read and dispatch
 * only*), then [open] hands it to the maps app back on this scope's thread.
 */
internal fun CoroutineScope.openStopMapOn(
    dispatcher: CoroutineDispatcher,
    latitude: Double,
    longitude: Double,
    name: String,
    open: (String) -> Unit,
): Job = launch { open(StopMap.geoUriOn(dispatcher, latitude, longitude, name)) }
