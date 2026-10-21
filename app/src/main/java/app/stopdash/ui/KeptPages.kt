package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * Shows the page [shown], one of the pages in [open], and keeps what each other open page saved (a page
 * opened inside it, its scroll) while it's covered, so Back to it finds it as it was left (SPEC *Back*).
 * A page's saved state goes once it leaves [open]: closed, it opens afresh next time. [content] draws
 * [shown]; its state is saved under that key. Constant work per frame: a handful of pages at most.
 */
@Composable
internal fun KeptPages(shown: String, open: Set<String>, content: @Composable () -> Unit) {
    val saved = rememberSaveableStateHolder()
    // Every page shown or open since the last cleanup, whose state may be held: saved too, so a page
    // closed while the process was away is still let go of.
    val held = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableSet() })) {
        mutableSetOf<String>()
    }
    SideEffect {
        held += shown
        held += open
    }
    LaunchedEffect(open) {
        val closed = held.filterNot(open::contains)
        closed.forEach(saved::removeState)
        held -= closed.toSet()
    }
    saved.SaveableStateProvider(shown) { content() }
}
