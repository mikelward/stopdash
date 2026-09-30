package app.stopdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one record of a key TfL refused (SPEC D7): set by a refusal, ended only by that key answered. */
class RejectedApiKeyTest {

    @Test
    fun `a refusal records the key it refused`() {
        val record = RejectedApiKey()
        assertNull(record.key.value)
        record.record("EXAMPLE", rejected = true)
        assertEquals("EXAMPLE", record.key.value)
    }

    @Test
    fun `that key answered later ends it, a refusal TfL took back`() {
        val record = RejectedApiKey()
        record.record("EXAMPLE", rejected = true)
        record.record("EXAMPLE", rejected = false)
        assertNull(record.key.value)
    }

    @Test
    fun `another key answered leaves the refusal standing`() {
        // A request still sending an earlier key says nothing about the one refused.
        val record = RejectedApiKey()
        record.record("EXAMPLE", rejected = true)
        record.record("OTHER", rejected = false)
        assertEquals("EXAMPLE", record.key.value)
    }

    @Test
    fun `the bar shows only while the refused key is the one in force`() {
        assertEquals(true, RejectedApiKey.refusesInForce("EXAMPLE", "EXAMPLE"))
        // Cleared, or replaced by another key: that refusal says nothing about what's sent now.
        assertEquals(false, RejectedApiKey.refusesInForce("EXAMPLE", null))
        assertEquals(false, RejectedApiKey.refusesInForce("EXAMPLE", "OTHER"))
        assertEquals(false, RejectedApiKey.refusesInForce(null, "EXAMPLE"))
        assertEquals(false, RejectedApiKey.refusesInForce(null, null))
    }

    @Test
    fun `nothing but an answer to that key ends it`() {
        // Saving the same key again (Try again after a failed save) must not hide the refusal: the
        // bar compares the refused key with the one in force, so only TfL answering it ends it.
        val record = RejectedApiKey()
        record.record("EXAMPLE", rejected = true)
        record.record("EXAMPLE", rejected = true)
        assertEquals("EXAMPLE", record.key.value)
    }
}
