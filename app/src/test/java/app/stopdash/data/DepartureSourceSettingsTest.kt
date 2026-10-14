package app.stopdash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a departures model refetches on ([DepartureSourceSettings]): the current settings first, which
 * the model ignores, then once for each change to the National Rail key or to train spacing.
 */
class DepartureSourceSettingsTest {
    @Test
    fun `turning spacing on or off is a change, as adding or clearing the key is`() = runTest {
        val key = MutableStateFlow<String?>(null)
        val spacing = MutableStateFlow(true)
        val seen = mutableListOf<Any?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            departureSourceChanges(key, spacing).collect { seen += it }
        }
        assertEquals(1, seen.size)
        spacing.value = false
        assertEquals(2, seen.size)
        key.value = "KEY"
        assertEquals(3, seen.size)
        // The same choice again changes nothing, so nothing is fetched for it.
        spacing.value = false
        assertEquals(3, seen.size)
        spacing.value = true
        assertEquals(4, seen.size)
    }
}
