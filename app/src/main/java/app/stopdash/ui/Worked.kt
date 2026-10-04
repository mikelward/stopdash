package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.stopdash.domain.AlertPlacement
import kotlinx.coroutines.withContext

/**
 * Inputs compared part by part: a plain value (a string, a number, a time, a day, a duration) by
 * value, and anything else (a collection, a map, a screen state) by identity, so a snapshot counts as
 * changed when it's a new object, never by comparing its contents, which grows with it (Codex, #505).
 * A part rebuilt with equal contents only costs one more run on the worker.
 */
internal class Inputs(vararg val parts: Any?) {
    override fun equals(other: Any?): Boolean =
        other is Inputs && other.parts.size == parts.size && parts.indices.all { same(parts[it], other.parts[it]) }

    override fun hashCode(): Int = parts.fold(1) { hash, part -> hash * 31 + if (byIdentity(part)) System.identityHashCode(part) else part.hashCode() }

    private companion object {
        fun byIdentity(part: Any?): Boolean = part != null && !plain(part)
        fun plain(part: Any): Boolean =
            part is String || part is Number || part is Boolean || part is Char || part is Enum<*> ||
                part is java.time.temporal.Temporal || part is java.time.temporal.TemporalAmount
        fun same(a: Any?, b: Any?): Boolean = if (byIdentity(a) || byIdentity(b)) a === b else a == b
    }
}

/** An answer worked out for [key]. */
internal class Worked<K, T>(val key: K, val value: T)

/**
 * The near-me list's answers worked out on [LocalWorker], one slot per stage. Hoisted above the
 * overlays and the route page with the list's scroll position, so a return to the list draws its last
 * rows at once, not a spinner while they are worked out again.
 */
@Stable
class ListWork {
    internal val alertLines: MutableState<Worked<Inputs, Set<String>>?> = mutableStateOf(null)
    internal val alertVerdicts: MutableState<Worked<Inputs, AlertPlacement?>?> = mutableStateOf(null)
    internal val rows: MutableState<Worked<ListInputs, ListRows?>?> = mutableStateOf(null)
    internal val shown: MutableState<Worked<ListInputs, ShownRows?>?> = mutableStateOf(null)
    internal val platform: MutableState<Worked<PlatformInputs, PlatformView?>?> = mutableStateOf(null)
}

/**
 * [compute] for [key], run on [LocalWorker] (AGENTS.md *Main thread: read and dispatch only*), so
 * composition only reads the answer. The answer for [key] once it's in. Until then the last answer
 * held in [slot], if [keep] says it may stand in for this key, else null. For example, rows whose
 * times moved on stand in for the moment the new ones take, but rows for other places don't.
 *
 * [compute] reads only what [key] is made of: it is the lambda of the composition that changed the
 * key, run once for it.
 */
@Composable
internal fun <K : Any, T> rememberWorked(
    slot: MutableState<Worked<K, T>?>,
    key: K,
    keep: (held: K, wanted: K) -> Boolean = { _, _ -> false },
    @WorkerThread compute: () -> T,
): T? {
    val worker = LocalWorker.current
    LaunchedEffect(slot, key, worker) {
        if (slot.value?.key == key) return@LaunchedEffect
        slot.value = Worked(key, withContext(worker) { compute() })
    }
    val held = slot.value ?: return null
    return held.value.takeIf { held.key == key || keep(held.key, key) }
}
