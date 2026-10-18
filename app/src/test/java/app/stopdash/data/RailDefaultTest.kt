package app.stopdash.data

import app.stopdash.domain.HiddenModesChoice
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.RailKeyDefault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailDefaultTest {
    @Test
    fun `National Rail starts hidden without the rider's own key, proxy or not`() {
        // railHiddenByDefault reads no proxy at all: only the rider's key and their own choice.
        assertTrue(railHiddenByDefault(HiddenModesChoice(), key = null))
        assertTrue(railHiddenByDefault(HiddenModesChoice(), key = " "))
        assertFalse(railHiddenByDefault(HiddenModesChoice(), key = "EXAMPLE"))
    }

    @Test
    fun `a rider who chose National Rail keeps their choice`() {
        assertFalse(railHiddenByDefault(HiddenModesChoice(railChosen = true), key = null))
        val hidden = ModeGroups.withGroup(emptySet(), RailKeyDefault.GROUP, hide = true)
        assertFalse(railHiddenByDefault(HiddenModesChoice(modes = hidden, railChosen = true), key = null))
    }

    @Test
    fun `showing National Rail asks for a key only when the proxy can't give its times`() {
        assertTrue(railKeyNeeded(key = null, loaded = true, proxy = null))
        assertFalse(railKeyNeeded(key = null, loaded = true, proxy = "https://proxy.example.com"))
        assertFalse(railKeyNeeded(key = "EXAMPLE", loaded = true, proxy = null))
        // A key still being read isn't taken for none.
        assertFalse(railKeyNeeded(key = null, loaded = false, proxy = null))
    }
}
