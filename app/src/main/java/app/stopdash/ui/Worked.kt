package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.stopdash.domain.AlertPlacement
import app.stopdash.domain.JourneySegment
import app.stopdash.domain.SiblingPoles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    companion object {
        private fun byIdentity(part: Any?): Boolean = part != null && !plain(part)
        private fun plain(part: Any): Boolean =
            part is String || part is Number || part is Boolean || part is Char || part is Enum<*> ||
                part is java.time.temporal.Temporal || part is java.time.temporal.TemporalAmount

        /** Whether two parts are the same as [Inputs] compares them: a plain value by value, else by identity. */
        fun same(a: Any?, b: Any?): Boolean = if (byIdentity(a) || byIdentity(b)) a === b else a == b
    }
}

/** An answer worked out for [key]. */
internal class Worked<K, T>(val key: K, val value: T)

/**
 * Each journey's [segments] by key (null where its route can't place it), and the [unplaced] lines:
 * those of a journey whose loaded route couldn't place it.
 */
internal class JourneySegments(val segments: Map<String, JourneySegment?>, val unplaced: Set<String>)

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

    // The journey cards' segments and neighboring poles, by journey key.
    internal val segments: MutableState<Worked<Inputs, JourneySegments>?> = mutableStateOf(null)
    internal val siblings: MutableState<Worked<Inputs, Map<String, SiblingPoles>>?> = mutableStateOf(null)
}

/**
 * The [ListWork] for the list [listKey] names, kept while it's null (the set not ready yet) and
 * replaced only for another list: a re-locate that finds the same set again draws its rows at once,
 * not behind a spinner, while another set starts afresh.
 */
@Composable
fun rememberListWork(listKey: String?): ListWork = remember { ListWorkHolder() }.workFor(listKey)

/** The work [rememberListWork] keeps, and the list it belongs to. */
internal class ListWorkHolder {
    private var owner: String? = null
    private var work = ListWork()

    fun workFor(listKey: String?): ListWork {
        if (listKey != null && listKey != owner) {
            if (owner != null) work = ListWork()
            owner = listKey
        }
        return work
    }
}

// A run skipped because a newer key was wanted before it started ([rememberWorked]).
private object Skipped

// The key each [rememberWorked] slot is wanted for now, by slot, as its latest keyed effect set it.
private val wantedKeys: MutableMap<MutableState<*>, Any> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

/**
 * [compute] for [key], run on [LocalWorker] (AGENTS.md *Main thread: read and dispatch only*), so
 * composition only reads the answer. The answer for [key] once it's in. Until then the last answer
 * held in [slot], if [keep] says it may stand in for this key, else null. For example, rows whose
 * times moved on stand in for the moment the new ones take, but rows for other places don't.
 *
 * [compute] reads only what [key] is made of: it is the lambda of the composition that changed the
 * key, run once for it. An answer under way is never canceled by a newer key (a clock tick): it's
 * stored for the key it was worked out for, and a key no longer wanted when its run would start is
 * skipped, so a worker slower than the keys change still lands answers (Codex, #529). A slot runs one
 * at a time: the keys that change meanwhile wait, and only the one wanted when the run ends is worked
 * out next, so a slow slot never takes up more than one of the worker's threads (Codex, #529).
 */
@Composable
internal fun <K : Any, T> rememberWorked(
    slot: MutableState<Worked<K, T>?>,
    key: K,
    keep: (held: K, wanted: K) -> Boolean = { _, _ -> false },
    @WorkerThread compute: () -> T,
): T? {
    val worker = LocalWorker.current
    // The work runs in a scope of the slot's own, not the effect's: a newer key (a clock tick) never
    // cancels an answer under way, while leaving composition, or the slot being replaced (another
    // list's work), still cancels it (Codex, #529).
    val parent = rememberCoroutineScope()
    val scope = remember(slot, parent) { CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job])) }
    // One run at a time, for this scope's slot: a scope gone (the composition left and came back) keeps
    // no hold on it, so the new one's work never waits behind work no longer wanted, which can't be
    // stopped mid-run (Codex, #529).
    val runs = remember(scope) { Mutex() }
    // Canceled from an effect's own end, on the dispatcher, never from disposal mid-apply: a slot in a
    // lazy list's item goes during a measure pass, where canceling its work outright could resume it there.
    LaunchedEffect(scope) {
        try {
            awaitCancellation()
        } finally {
            scope.cancel()
        }
    }
    LaunchedEffect(slot, key, worker) {
        // The key wanted now, kept with the slot, so every composition using it (one that left and came
        // back, a slot hoisted above an overlay) reads the same one (Codex, #529).
        wantedKeys[slot] = key
        if (slot.value?.key == key) return@LaunchedEffect
        scope.launch {
            runs.withLock {
                // Already in, by a run for the same key that went first.
                if (slot.value?.key == key) return@withLock
                val answer = withContext(worker) {
                    // Skipped if a newer key is wanted before it starts: that key's own run works it out.
                    if (wantedKeys[slot] != key) Skipped else compute()
                }
                // Not over an answer for the key wanted now (a change undone while this was out, or a newer
                // run's that landed first): that one is current, this one isn't (Codex, #529). Nor over one
                // that may stand in for it ([keep]) when this one may not: a choice undone while the clock
                // moved on leaves the last answer standing, not a blank until the wanted one is in.
                val held = slot.value
                val wanted = wantedKeys[slot]
                @Suppress("UNCHECKED_CAST")
                val standsIn = { k: K -> wanted != null && (k == wanted || keep(k, wanted as K)) }
                @Suppress("UNCHECKED_CAST")
                if (answer !== Skipped && held?.key != wanted && (held == null || !standsIn(held.key) || standsIn(key))) {
                    slot.value = Worked(key, answer as T)
                }
            }
        }
    }
    val held = slot.value ?: return null
    return held.value.takeIf { held.key == key || keep(held.key, key) }
}
