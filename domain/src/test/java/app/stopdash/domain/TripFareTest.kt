package app.stopdash.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TripFareTest {
    @Test
    fun `a fare reads in pounds and pence`() {
        assertEquals("£3.10", TripFare(310).label)
        assertEquals("£1.75", TripFare(175).label)
        assertEquals("£13.50", TripFare(1350).label)
        assertEquals("£0.05", TripFare(5).label)
    }

    @Test
    fun `whatever the device's locale`() {
        val saved = Locale.getDefault()
        try {
            // A locale with a decimal comma: the price is still "£3.10".
            Locale.setDefault(Locale.GERMANY)
            assertEquals("£3.10", TripFare(310).label)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
