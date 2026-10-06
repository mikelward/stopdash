package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * [this] flow's latest value as state while the screen is started, as `collectAsStateWithLifecycle`
 * gives it, but taken in by identity (Codex, #627). That one compares each new value with the last by
 * its `equals`, on the main thread, which for a data class walks every collection it holds; a
 * [StateFlow] has already made that comparison where it was written, so a value that reaches here is
 * a new one, and a reference check is all the main thread does with it however much it holds.
 */
@Composable
internal fun <T> StateFlow<T>.collectByIdentityWithLifecycle(): State<T> {
    val owner = LocalLifecycleOwner.current
    val state = remember(this) { heldByIdentity(this) }
    LaunchedEffect(this, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { collect { state.value = it } }
    }
    return state
}

private fun <T> heldByIdentity(flow: StateFlow<T>): MutableState<T> = mutableStateOf(flow.value, referentialEqualityPolicy())
