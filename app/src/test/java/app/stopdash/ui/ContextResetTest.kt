package app.stopdash.ui

import app.stopdash.domain.CollapsedPlaces
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** What a list keeps when the set it's for changes, and what it starts afresh. */
class ContextResetTest {
    @Test
    fun a_lists_work_is_kept_while_its_set_is_not_ready_and_replaced_for_another_set() {
        val holder = ListWorkHolder()
        val first = holder.workFor(null)
        // The first set adopts what was held before it was ready.
        assertSame(first, holder.workFor("A"))
        // A re-locate passes through not ready and finds the same set: its rows are kept.
        assertSame(first, holder.workFor(null))
        assertSame(first, holder.workFor("A"))
        // Another set starts afresh.
        assertNotSame(first, holder.workFor("B"))
    }

    @Test
    fun farther_cards_picked_for_one_set_are_not_another_sets() {
        val places = emptyList<CollapsedPlaces.Place>()
        val picked = FartherFor("A", places)
        assertSame(places, picked.forSet("A"))
        assertNull(picked.forSet("B"))
    }
}
