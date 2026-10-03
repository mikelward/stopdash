package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings wrapper's read/write mapping over a fake in-memory [DataStore], so no Android
 * file or Context is needed (the JSON serialization is a plain default-bearing shape). Mirrors
 * [DataStoreStarredRowsStoreTest]. A missing file reads the documented default; a write persists
 * and re-emits.
 */
class DataStoreAppSettingsTest {
    private class FakeDataStore(initial: PersistedSettings?) : DataStore<PersistedSettings?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedSettings?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedSettings?) -> PersistedSettings?,
        ): PersistedSettings? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `hidden modes read none by default and persist a change`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertTrue(store.hiddenModes().first().isEmpty())
        store.setHiddenModes(setOf("bus", "national-rail"))
        assertEquals(setOf("bus", "national-rail"), store.hiddenModes().first())
    }

    @Test
    fun `distance units follow the locale by default and persist a change`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertEquals(DistanceUnits.AUTOMATIC, store.distanceUnits().first())
        store.setDistanceUnits(DistanceUnits.YARDS)
        assertEquals(DistanceUnits.YARDS, store.distanceUnits().first())
    }

    @Test
    fun `a distance unit this build doesn't know reads as automatic`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(distanceUnits = "NAUTICAL")))
        assertEquals(DistanceUnits.AUTOMATIC, store.distanceUnits().first())
    }

    @Test
    fun `max walk is 20 minutes by default and persists a change`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertEquals(MaxWalk.TWENTY, store.maxWalk().first())
        store.setMaxWalk(MaxWalk.SIXTY)
        assertEquals(MaxWalk.SIXTY, store.maxWalk().first())
    }

    @Test
    fun `a max walk this build doesn't know reads as the default`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(maxWalk = "NINETY")))
        assertEquals(MaxWalk.DEFAULT, store.maxWalk().first())
    }

    @Test
    fun `step-free asks nothing by default and persists a change`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertEquals(StepFree.ANY, store.stepFree().first())
        store.setStepFree(StepFree.FULLY)
        assertEquals(StepFree.FULLY, store.stepFree().first())
    }

    @Test
    fun `a step-free level this build doesn't know reads as the default`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(stepFree = "LEVEL")))
        assertEquals(StepFree.DEFAULT, store.stepFree().first())
    }

    @Test
    fun `a trip rides every mode by default and persists a change`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertEquals(TripModes.DEFAULT, store.tripModes().first())
        store.setTripModes(TripModes(setOf("bus", "tram")))
        assertEquals(TripModes(setOf("bus", "tram")), store.tripModes().first())
    }

    @Test
    fun `a trip mode group this build doesn't know is dropped`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(tripModesOff = setOf("bus", "hovercraft"))))
        assertEquals(TripModes(setOf("bus")), store.tripModes().first())
    }

    @Test
    fun `a trip avoids no line by default and persists the lines avoided`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertTrue(store.avoidedLines().first().isEmpty())
        val northern = AvoidedLines.key("northern", "Northern line")
        store.setAvoidedLines(setOf(northern))
        assertEquals(setOf(northern), store.avoidedLines().first())
    }

    @Test
    fun `an avoided entry that isn't a line's is dropped`() = runTest {
        val northern = AvoidedLines.key("northern", "Northern line")
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(avoidedLines = setOf(northern, "bus"))))
        assertEquals(setOf(northern), store.avoidedLines().first())
    }

    @Test
    fun `live widget refresh reads the default when nothing is stored`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertFalse(store.liveWidgetRefresh().first())
    }

    @Test
    fun `setting live widget refresh on persists and re-emits`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        store.setLiveWidgetRefresh(true)
        assertTrue(store.liveWidgetRefresh().first())
    }

    @Test
    fun `setting live widget refresh off persists`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(liveWidgetRefresh = true)))
        assertTrue(store.liveWidgetRefresh().first())
        store.setLiveWidgetRefresh(false)
        assertFalse(store.liveWidgetRefresh().first())
    }

    @Test
    fun `the documented default is off`() {
        assertEquals(false, DataStoreAppSettings.DEFAULT_LIVE_WIDGET_REFRESH)
    }

    @Test
    fun `skip bug-report consent reads the default (ask every time) when nothing is stored`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertFalse(store.skipBugReportConsent().first())
    }

    @Test
    fun `opting out of the bug-report consent screen persists and re-emits`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        store.setSkipBugReportConsent(true)
        assertTrue(store.skipBugReportConsent().first())
    }

    @Test
    fun `an opt-out saved before the report grew asks for consent again`() = runTest {
        // A file from before consent versions: opted out, no version.
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(skipBugReportConsent = true)))
        assertFalse(store.skipBugReportConsent().first())
        store.setSkipBugReportConsent(true)
        assertTrue(store.skipBugReportConsent().first())
    }

    @Test
    fun `the journey tip shows until dismissed, and stays dismissed`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertFalse(store.journeyTipDismissed().first())
        store.setJourneyTipDismissed(true)
        assertTrue(store.journeyTipDismissed().first())
    }

    @Test
    fun `the watch install card shows until dismissed, apart from the journey tip`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertFalse(store.watchInstallCardDismissed().first())
        store.setWatchInstallCardDismissed(true)
        assertTrue(store.watchInstallCardDismissed().first())
        assertFalse(store.journeyTipDismissed().first())
    }

    @Test
    fun `the documented consent default is to ask every time`() {
        assertEquals(false, DataStoreAppSettings.DEFAULT_SKIP_BUG_REPORT_CONSENT)
    }

    @Test
    fun `user api key reads null (keyless) when nothing is stored`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        assertNull(store.userApiKey().first())
    }

    @Test
    fun `pasting a user api key persists and re-emits`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        store.setUserApiKey("EXAMPLE")
        assertEquals("EXAMPLE", store.userApiKey().first())
    }

    @Test
    fun `a pasted key is trimmed`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(null))
        store.setUserApiKey("  EXAMPLE  ")
        assertEquals("EXAMPLE", store.userApiKey().first())
    }

    @Test
    fun `clearing the key with blank returns to keyless`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(userApiKey = "EXAMPLE")))
        assertEquals("EXAMPLE", store.userApiKey().first())
        store.setUserApiKey("   ")
        assertNull(store.userApiKey().first())
    }

    @Test
    fun `clearing the key with null returns to keyless`() = runTest {
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(userApiKey = "EXAMPLE")))
        store.setUserApiKey(null)
        assertNull(store.userApiKey().first())
    }

    @Test
    fun `a stored blank key reads as keyless`() = runTest {
        // An empty string from an older build or a hand-edited file must not be sent as an empty
        // app_key (which TfL rejects) — it reads back as null.
        val store = DataStoreAppSettings(FakeDataStore(PersistedSettings(userApiKey = "")))
        assertNull(store.userApiKey().first())
    }
}
