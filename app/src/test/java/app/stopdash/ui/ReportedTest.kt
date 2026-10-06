package app.stopdash.ui

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** A report the worker finds unchanged is the same [Reported], so where it's reported, identity tells. */
class ReportedTest {
    @Test
    fun the_same_value_is_the_last_report() {
        val last = Reported(listOf("940GZZLUVIC"))
        assertSame(last, Reported.of(listOf("940GZZLUVIC"), last))
    }

    @Test
    fun a_changed_value_is_a_new_report() {
        val last = Reported(listOf("940GZZLUVIC"))
        val next = Reported.of(listOf("940GZZLUVIC", "940GZZLUWRR"), last)
        assertNotSame(last, next)
        assertSame(next, Reported.of(listOf("940GZZLUVIC", "940GZZLUWRR"), next))
    }

    @Test
    fun the_first_value_is_a_new_report() {
        assertNotSame(null, Reported.of(emptyList<String>(), null))
    }
}
