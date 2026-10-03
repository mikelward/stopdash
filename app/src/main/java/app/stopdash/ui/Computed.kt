package app.stopdash.ui

import android.util.LruCache
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.Workers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where the UI works out anything it shows that isn't already in hand (AGENTS.md *Main thread: read
 * and dispatch only*): never the main thread. A screenshot test provides the main one, so a
 * computed value is in its first settled frame rather than racing it.
 */
internal val LocalWorker = staticCompositionLocalOf<CoroutineDispatcher> { Workers.compute }

/**
 * How long a computed value may take and still replace what's on screen unasked ([rememberComputed]).
 * Past it, a value that would change what the rider is reading waits for a tap.
 */
internal val LocalComputeBudgetMillis = staticCompositionLocalOf { COMPUTE_BUDGET_MILLIS }

internal const val COMPUTE_BUDGET_MILLIS = 300L

/**
 * A value worked out off the main thread by [rememberComputed]: [value] is what to show now, [ready]
 * whether it is a computed answer rather than the placeholder, [current] whether it was worked out
 * from the inputs as they are now (so a thing missing from it is really missing), and [hasUpdate]
 * whether a newer one came in too late to swap in unasked; [showUpdate] swaps it in (the
 * [UpdateChip]'s tap).
 */
@Stable
internal class Computed<T>(initial: T, ready: Boolean, provisional: Boolean = false) {
    var value: T by mutableStateOf(initial)
        private set
    var ready: Boolean by mutableStateOf(ready)
        private set
    private var update: Any? by mutableStateOf(NO_UPDATE)
    // A value from the memo: an earlier visit's answer, shown only until this one's first is in.
    private var provisional = provisional
    // The inputs [value] and the held update were worked out from, and those of the latest
    // composition (set by [rememberComputed] as it composes; read in the same pass).
    private var shownKeys: List<Any?>? by mutableStateOf(null)
    private var updateKeys: List<Any?>? = null
    internal var latestKeys: List<Any?> = emptyList()

    val hasUpdate: Boolean get() = update !== NO_UPDATE

    val current: Boolean get() = ready && shownKeys == latestKeys

    fun showUpdate() {
        val next = update
        if (next === NO_UPDATE) return
        @Suppress("UNCHECKED_CAST")
        value = next as T
        shownKeys = updateKeys
        update = NO_UPDATE
    }

    // Shown at once while nothing computed for these inputs is on screen (the placeholder, or an
    // earlier visit's answer from the memo), or it came in within the budget, or it shows the same;
    // otherwise held for a tap, so a late answer never moves what the rider is reading.
    internal fun offer(result: T, keys: List<Any?>, inTime: Boolean, same: Boolean) {
        if (!ready || provisional || inTime || same) {
            value = result
            shownKeys = keys
            ready = true
            provisional = false
            update = NO_UPDATE
        } else {
            update = result
            updateKeys = keys
        }
    }

    private companion object {
        val NO_UPDATE = Any()
    }
}

/**
 * [compute] run on [LocalWorker], never the caller's thread, again whenever [keys] change: the only
 * way a composable derives what it shows from data it holds (AGENTS.md *Main thread: read and
 * dispatch only*). [compute] reads only what the keys stand for; it may suspend (a repository load).
 *
 * The first frame shows [placeholder], or, where [memo] names this value and it was worked out
 * before in this process, that answer at once, so a page reopened shows its content in its first
 * frame ([memoIf] says which answers are worth that: not a failure), until this visit's first answer
 * replaces it, however late. A new answer replaces what's shown unasked while the placeholder or the
 * memo's answer is up, or within [LocalComputeBudgetMillis] of the keys changing; later, one that
 * differs waits as [Computed.hasUpdate] for a tap, so a caller that keeps old answers up
 * ([resetOnChange] false) shows an [UpdateChip].
 *
 * [resetOnChange] puts the placeholder back the moment the keys change, for a value whose old
 * answer would be wrong for the new keys (a stop list for the previous train: SPEC D4); without it
 * the old answer stays up while the new one is worked out.
 */
@Composable
internal fun <T> rememberComputed(
    vararg keys: Any?,
    placeholder: T,
    resetOnChange: Boolean = true,
    memo: Any? = null,
    memoIf: (T) -> Boolean = { true },
    compute: suspend () -> T,
): Computed<T> {
    val worker = LocalWorker.current
    val budget = LocalComputeBudgetMillis.current
    val latest by rememberUpdatedState(compute)
    val holderKeys: Array<out Any?> = if (resetOnChange) keys else emptyArray()
    val computed = remember(*holderKeys) {
        val known = memo?.let { ComputedMemo.get(it) }
        @Suppress("UNCHECKED_CAST")
        if (known != null) Computed(known.value as T, ready = true, provisional = true) else Computed(placeholder, ready = false)
    }
    // A view of the keys, not a copy.
    val keyList = keys.asList()
    computed.latestKeys = keyList
    LaunchedEffect(*keys, worker) {
        val started = System.nanoTime()
        val shown = computed.value
        val wasReady = computed.ready
        val (result, same) = withContext(worker) {
            val result = latest()
            // Compared here, not on the main thread: a stop list's equality walks every stop.
            result to (wasReady && result == shown)
        }
        if (memo != null && memoIf(result)) ComputedMemo.put(memo, result)
        computed.offer(result, keyList, inTime = (System.nanoTime() - started) / 1_000_000 <= budget, same = same)
    }
    return computed
}

/**
 * The answers [rememberComputed] has worked out under a [memo][rememberComputed] key, so a page
 * reopened in the same process shows its last answer in its first frame while it works out the
 * current one. Bounded; held values are immutable UI state.
 */
internal object ComputedMemo {
    class Held(val value: Any?)

    private val held = LruCache<Any, Held>(MAX_ENTRIES)

    fun get(key: Any): Held? = held.get(key)

    fun put(key: Any, value: Any?) {
        held.put(key, Held(value))
    }

    const val MAX_ENTRIES = 64
}

/**
 * The "Tap to see" cue for a [Computed] value with an update waiting ([Computed.hasUpdate]): shown
 * where the value is, it swaps the update in when tapped. Nothing when there's none.
 */
@Composable
internal fun UpdateChip(computed: Computed<*>, modifier: Modifier = Modifier) {
    if (!computed.hasUpdate) return
    UpdateChip(onClick = computed::showUpdate, modifier = modifier)
}

/** The "Tap to see" cue itself, for a list that places it as one of its items. */
@Composable
internal fun UpdateChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(stringResource(R.string.computed_tap_to_see)) },
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * One tap showing every update waiting among [values] ([Computed.hasUpdate]), for a list whose parts
 * are worked out apart but read as one page; null when none is waiting.
 */
internal fun listUpdate(values: List<Computed<*>>): (() -> Unit)? {
    // A fixed few values, not the list's data: no work that grows with the page.
    var waiting = false
    for (value in values) waiting = waiting || value.hasUpdate
    return if (waiting) ({ for (value in values) value.showUpdate() }) else null
}
