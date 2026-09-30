package app.stopdash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether TfL has refused the user's key (SPEC D7), as one fact for the whole process: every TfL
 * request, from any screen or the widget, reports how TfL answered the key it sent ([record]), and
 * the app shows one bar offering to clear it. One place, not each screen's own failure: a refused
 * key fails every request the same way, and many screens fold a failure into a plain "couldn't
 * update" that can't say why.
 *
 * Keyed by the key refused, so a request TfL later answers with that same key ends it (a refusal TfL
 * took back), and a refusal of a key no longer in force (cleared, replaced, or a widget request still
 * sending the old one) shows nothing. Held in memory only, like [UserApiKeySetting]'s copy: never logged, never persisted.
 */
class RejectedApiKey {
    private val refused = MutableStateFlow<String?>(null)

    /**
     * The key TfL last refused, until it answers that key; null with none. Kept across a key change
     * rather than forgotten: the bar compares it with the key in force, so a different key (or none)
     * shows nothing, and the refused one pasted again, or merely saved again, still does.
     */
    val key: StateFlow<String?> = refused.asStateFlow()

    /** TfL answered a request sent with [key]: refused it ([rejected]), or answered it. */
    fun record(key: String, rejected: Boolean) {
        if (rejected) refused.value = key else refused.compareAndSet(key, null)
    }

    companion object {
        /** The process's one record, shared by the app's and the widget's clients. */
        val SHARED = RejectedApiKey()

        /** Whether [refused], the key TfL last refused ([key]), is [inForce], the key in use now. */
        fun refusesInForce(refused: String?, inForce: String?): Boolean = inForce != null && refused == inForce
    }
}
