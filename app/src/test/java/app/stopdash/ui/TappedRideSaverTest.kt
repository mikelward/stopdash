package app.stopdash.ui

import android.os.Parcel
import androidx.compose.runtime.saveable.SaverScope
import app.stopdash.domain.Coordinates
import app.stopdash.domain.TripLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * The ride a board's train was tapped for, saved whole with its page ([TappedRideSaver]) and read back
 * through a real parcel, as the activity's state is when it's recreated (Codex, #627).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TappedRideSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(leg: TripLeg?): TripLeg? {
        val saved = with(TappedRideSaver) { scope.save(leg) } ?: return null
        val parcel = Parcel.obtain()
        try {
            parcel.writeValue(saved)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            val back = parcel.readValue(javaClass.classLoader) as ArrayList<Any?>
            return TappedRideSaver.restore(back)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `a tapped ride comes back whole`() {
        // The Jubilee line from Stratford to Canary Wharf, every field set; its path a view onto a longer
        // list, as a ride's can be, which a parcel writes as any other list.
        val calls = listOf("940GZZLUWHM", "940GZZLUCGT", "940GZZLUCYF", "940GZZLULNB")
        val ride = TripLeg(
            "tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf",
            Instant.parse("2026-10-06T08:00:00Z"), Instant.parse("2026-10-06T08:09:00Z"),
            path = calls.subList(0, 3), pathNames = listOf("West Ham", "Canning Town", "Canary Wharf"),
            changeAfter = Duration.ofMinutes(4), headings = listOf("Stanmore"),
            fromArea = "area-a", toArea = "area-b",
            fromAt = Coordinates(51.5, 0.0), toAt = Coordinates(51.5, -0.02),
            plannedFromId = "940GZZLUSTD-planned", plannedToId = "940GZZLUCYF-planned",
        )
        assertEquals(ride, roundTrip(ride))
    }

    @Test
    fun `a ride with no stop positions comes back without them`() {
        val ride = TripLeg(
            "tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf",
            Instant.parse("2026-10-06T08:00:00Z"), Instant.parse("2026-10-06T08:09:00Z"),
        )
        val back = roundTrip(ride)
        assertEquals(ride, back)
        assertNull(back?.fromAt)
        assertNull(back?.toAt)
    }

    @Test
    fun `saving a ride never copies its lists`() {
        // The save runs on the main thread as the activity stops: it hands the ride's lists over as they
        // are, never walking a route there (Codex, #627).
        val path = List(200) { "stop$it" }
        val names = List(200) { "Stop $it" }
        val headings = listOf("Stanmore")
        val ride = TripLeg(
            "tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf",
            Instant.parse("2026-10-06T08:00:00Z"), Instant.parse("2026-10-06T08:09:00Z"),
            path = path, pathNames = names, headings = headings,
        )
        val saved = with(TappedRideSaver) { scope.save(ride) }!!
        assertSame(path, saved.single { it === path })
        assertSame(names, saved.single { it === names })
        assertSame(headings, saved.single { it === headings })
    }

    @Test
    fun `no ride saves as none`() {
        assertNull(with(TappedRideSaver) { scope.save(null) })
    }
}
