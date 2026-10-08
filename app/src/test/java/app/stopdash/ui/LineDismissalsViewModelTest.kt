package app.stopdash.ui

import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LineDismissalsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Store(private val fails: Boolean = false) : DismissedAlertsStore {
        val held = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        override fun dismissed(): Flow<Set<DismissedAlert>> = held
        override suspend fun dismiss(alert: DismissedAlert) {
            if (fails) throw IOException("disk full")
            held.value = held.value + alert
        }
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
    }

    private val delays = LineStatus(lineId = "jubilee", severity = 9, description = "Minor Delays", fullText = "Minor delays.")
    private val weekend = PlannedAlert("Part Closure", "Saturday, no service between Baker Street and Stanmore.", LocalDate.of(2026, 10, 10))

    @Test
    fun it_follows_the_store_and_dismisses_into_it() {
        val store = Store()
        val model = LineDismissalsViewModel(store, Dispatchers.Unconfined) {}
        assertEquals(emptySet<DismissedAlert>(), model.dismissed.value)
        model.dismiss(delays)
        model.dismissPlanned("jubilee", weekend)
        val both = setOf(DismissedAlert.ofLineStatus(delays), DismissedAlert.ofPlanned("jubilee", weekend))
        assertEquals(both, store.held.value)
        assertEquals(both, model.dismissed.value)
        assertFalse(model.writeFailed.value)
    }

    @Test
    fun a_dismiss_that_could_not_be_written_is_flagged_until_said() {
        val warned = mutableListOf<String>()
        val model = LineDismissalsViewModel(Store(fails = true), Dispatchers.Unconfined) { warned += it }
        model.dismissPlanned("jubilee", weekend)
        assertTrue(model.writeFailed.value)
        assertTrue(warned.isNotEmpty())
        model.writeFailureShown()
        assertFalse(model.writeFailed.value)
    }
}
