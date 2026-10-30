package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * The faraway journeys opened, by key, and the nearby stop set they were opened for. [run] is the same object for
 * as long as rides are only added, so a set from the same run with no fewer keys holds every key of an earlier
 * one: a check that costs nothing on the main thread.
 */
internal class FarOpened(val forKey: String?, val keys: PersistentSet<String>, val run: Any = Any()) {
    companion object {
        val None = FarOpened(null, persistentSetOf())

        // Saved as the set they were opened for, then their keys. Restoring rebuilds the set in composition: it is
        // as long as the rides the rider opened, and restoring it later would draw them folded for a frame.
        val Saver = Saver<FarOpened, ArrayList<String>>(
            save = { opened -> opened.forKey?.let { arrayListOf(it).apply { addAll(opened.keys) } } ?: arrayListOf() },
            restore = { saved -> if (saved.isEmpty()) None else FarOpened(saved[0], saved.drop(1).toPersistentSet()) },
        )
    }
}

/**
 * The faraway favorites the rider has opened, each by a tap on its collapsed card, for the nearby stop set
 * [key] (SPEC *Journeys*). Saved with the set they were opened for, not as bare keys, so a restore after
 * process death somewhere else doesn't bring them back unchecked.
 */
@Stable
class FarRevealState internal constructor(private val opened: MutableState<FarOpened>, private val key: String) {
    /**
     * The journeys opened for this set, by key: the saved set itself, so reading it does no work and work keyed on
     * it by identity ([Inputs]) runs once per change.
     */
    val revealed: Set<String> get() = opened.value.let { if (it.forKey == key) it.keys else persistentSetOf() }

    /**
     * The run [revealed] belongs to: while it stays the same, rides were only added, so a later [revealed] of no
     * smaller size holds every ride of an earlier one.
     */
    val run: Any get() = opened.value.let { if (it.forKey == key) it.run else FarOpened.None.run }

    /** Opens the faraway journey [journeyKey]: its card shows in full and its trains are fetched. */
    fun reveal(journeyKey: String) {
        val now = opened.value
        // A persistent set adds without copying the others.
        if (now.forKey != key) opened.value = FarOpened(key, persistentSetOf(journeyKey))
        else if (journeyKey !in now.keys) opened.value = FarOpened(key, now.keys.add(journeyKey), now.run)
    }
}

/**
 * The faraway favorites opened for [nearbyKey], kept while that set stays (held above the overlays and
 * route page, like the list's scroll position, so opening one doesn't forget them) and forgotten on
 * leaving the set, so coming back later holds far journeys back again. A null key (the set not known
 * yet, e.g. while location resolves) changes nothing, as in [rememberListStateFor].
 */
@Composable
fun rememberFarReveal(nearbyKey: String?): FarRevealState {
    val opened = rememberSaveable(stateSaver = FarOpened.Saver) { mutableStateOf(FarOpened.None) }
    LaunchedEffect(nearbyKey) {
        if (nearbyKey != null && opened.value.forKey != null && opened.value.forKey != nearbyKey) opened.value = FarOpened.None
    }
    val key = nearbyKey ?: ""
    return remember(opened, key) { FarRevealState(opened, key) }
}
