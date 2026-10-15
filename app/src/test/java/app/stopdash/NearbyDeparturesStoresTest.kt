package app.stopdash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NearbyDeparturesStores] retains a nearby set's store across recomposition (so a rotation
 * doesn't rebuild the departures ViewModel and re-fetch) and clears a set the user moved away
 * from (so an old location's models and its in-flight fetch don't pile up).
 */
class SentinelViewModel : ViewModel() {
    var cleared = false
    public override fun onCleared() {
        cleared = true
    }
}

class NearbyDeparturesStoresTest {

    @Test
    fun `the same key reuses the same store`() {
        val holder = NearbyDeparturesStores()
        assertSame(holder.ownerFor("a").viewModelStore, holder.ownerFor("a").viewModelStore)
    }

    @Test
    fun `switching keys clears the previous set's models`() {
        val holder = NearbyDeparturesStores()
        val ownerA = holder.ownerFor("a")
        val vm = ViewModelProvider(ownerA, ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]
        assertFalse(vm.cleared)

        // Moving to a new set clears the old one's store, running onCleared (which cancels
        // that MainViewModel's viewModelScope and any in-flight fetch in production).
        holder.ownerFor("b")
        assertTrue(vm.cleared)
    }

    @Test
    fun `a kept key's store survives another being looked up, and goes once it isn't kept`() {
        // Codex on #887: a stop picked to go via, looked up beside the via in place, cleared the via's lookup.
        val holder = NearbyDeparturesStores()
        val ownerA = holder.ownerFor("a")
        val vmA = ViewModelProvider(ownerA, ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]

        val ownerB = holder.ownerFor("b", keep = "a")
        val vmB = ViewModelProvider(ownerB, ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]
        assertFalse("the kept set's model survives", vmA.cleared)
        assertSame("and its store is the one retained", ownerA.viewModelStore, holder.ownerFor("a", keep = "b").viewModelStore)
        assertFalse("looking the kept one up again keeps the other", vmB.cleared)

        // A third, keeping only the second: the first is no longer wanted and is cleared.
        holder.ownerFor("c", keep = "b")
        assertTrue(vmA.cleared)
        assertFalse(vmB.cleared)
    }

    @Test
    fun `clearAll drops every store so a recovered set rebuilds its model`() {
        val holder = NearbyDeparturesStores()
        val ownerA = holder.ownerFor("a")
        val storeA = ownerA.viewModelStore
        val vm = ViewModelProvider(ownerA, ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]

        // The gate shows (a failed/empty relocate): clearAll drops the retained store and
        // cancels its in-flight fetch, so recovering to the same key can't reuse a stale model.
        holder.clearAll()
        assertTrue("the retained model is cleared", vm.cleared)

        // Recovering to the same key ("Try again" → same stops) rebuilds a fresh store, so the
        // MainViewModel's init re-runs and re-fetches rather than serving the pre-gate rows.
        assertFalse("the recovered set gets a new store", holder.ownerFor("a").viewModelStore === storeA)
    }
}
