package app.stopdash

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [KeepOpenStationModels]: each open station's models are kept while it's open and go as it closes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class OpenStationModelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_change_of_start_and_back_from_its_search_keep_the_station_under() {
        // Codex on #1147: Back from the search a station's From row opened dropped every station's models, the one
        // still open under it included, so Back to that one looked it up again.
        val stores = NearbyDeparturesStores()
        val stations = mutableStateMapOf<String, Unit>("a" to Unit, "b" to Unit)
        val modelA = ViewModelProvider(stores.ownerAmong("a", open = stations.keys), ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]
        val ownerB = stores.ownerAmong("b", open = stations.keys)
        val modelB = ViewModelProvider(ownerB, ViewModelProvider.NewInstanceFactory())[SentinelViewModel::class.java]
        composeRule.setContent { KeepOpenStationModels(stores, stations) }
        composeRule.waitForIdle()
        assertFalse(modelA.cleared)
        assertFalse(modelB.cleared)
        // B's From row: B closes for the search, its models with it.
        stations.remove("b")
        composeRule.waitForIdle()
        assertTrue("the station left for the search goes", modelB.cleared)
        assertFalse("the station under it stays", modelA.cleared)
        // Back from the search reopens B, afresh; A stays as it was.
        stations["b"] = Unit
        composeRule.waitForIdle()
        assertFalse(modelA.cleared)
        assertNotSame(ownerB.viewModelStore, stores.ownerAmong("b", open = stations.keys).viewModelStore)
        // The flow ends: every station closes, their models with them.
        stations.clear()
        composeRule.waitForIdle()
        assertTrue("a station closed with the flow goes", modelA.cleared)
    }
}
