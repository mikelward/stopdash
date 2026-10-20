package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldTrainsTest {
    private val t0 = Instant.parse("2026-01-01T08:00:00Z")
    private val stop = "940GZZLUKSX"

    private fun train(dueAt: Long, mode: String = "tube", vehicle: String = "125") = Departure(
        lineId = "northern", lineName = "Northern", direction = "inbound", destination = "Morden",
        platform = "Southbound - Platform 4", expectedArrival = t0.plusSeconds(dueAt), mode = mode, vehicleId = vehicle,
    )

    // One answer made [madeAt] seconds in, the train due at [dueAt] and TfL placing it [at].
    private fun HeldTrains.answer(madeAt: Long, dueAt: Long, at: String, mode: String = "tube", vehicle: String = "125") =
        mark(stop, t0.plusSeconds(madeAt), listOf(train(dueAt, mode, vehicle)), listOf(at)).single()

    @Test
    fun `a train standing still while its time slides is held once two minutes have passed`() {
        val held = HeldTrains()
        // At Camden Town from 0 s, due at 300 s, its time sliding at the clock's pace.
        assertFalse(held.answer(0, 300, "At Camden Town").held)
        assertFalse(held.answer(60, 360, "At Camden Town").held)
        assertFalse(held.answer(110, 410, "At Camden Town").held)
        assertTrue(held.answer(120, 420, "At Camden Town").held)
        assertTrue(held.answer(180, 480, "At Camden Town").held)
    }

    @Test
    fun `a train standing still whose time counts down isn't held`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        assertFalse(held.answer(150, 300, "At Camden Town").held)
        // Sliding at under half the clock's pace isn't enough either.
        assertFalse(held.answer(200, 390, "At Camden Town").held)
    }

    @Test
    fun `a train that moves on starts over, and isn't held while it does`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        assertTrue(held.answer(150, 450, "At Camden Town").held)
        assertFalse(held.answer(170, 470, "Between Camden Town and Euston").held)
        assertFalse(held.answer(260, 560, "Between Camden Town and Euston").held)
        assertTrue(held.answer(300, 600, "Between Camden Town and Euston").held)
    }

    @Test
    fun `an older cache copy changes nothing and keeps what the newest answer worked out`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        assertTrue(held.answer(150, 450, "At Camden Town").held)
        // A copy made before that answer, its train elsewhere: still held, and its place unchanged.
        assertTrue(held.answer(100, 400, "Between Kentish Town and Camden Town").held)
        assertTrue(held.answer(200, 500, "At Camden Town").held)
    }

    @Test
    fun `a train due within a minute isn't held, as it's all but here`() {
        val held = HeldTrains()
        held.answer(0, 100, "Approaching King's Cross")
        assertFalse(held.answer(150, 200, "Approaching King's Cross").held)
    }

    @Test
    fun `only tube trains with an id and a location are followed`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Stop A", mode = "bus")
        assertFalse(held.answer(150, 450, "At Stop A", mode = "bus").held)
        held.answer(0, 300, "", vehicle = "200")
        assertFalse(held.answer(150, 450, "", vehicle = "200").held)
        held.answer(0, 300, "At Camden Town", vehicle = "")
        assertFalse(held.answer(150, 450, "At Camden Town", vehicle = "").held)
    }

    @Test
    fun `a train not answered for ten minutes starts over`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        assertFalse(held.answer(700, 1200, "At Camden Town").held)
    }

    @Test
    fun `an undated answer is taken as it comes, nothing marked, and the stop's trains start over`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        val board = listOf(train(450))
        assertEquals(board, held.mark(stop, null, board, listOf("Between Camden Town and Euston")))
        // Placed back where it was: no history carried over the undated answer.
        assertFalse(held.answer(150, 450, "At Camden Town").held)
    }

    @Test
    fun `each stop and train is followed apart`() {
        val held = HeldTrains()
        val at = listOf("At Camden Town", "At Camden Town")
        held.mark(stop, t0, listOf(train(300), train(300, vehicle = "126")), at)
        // 125 slides; 126, counting down at the same place, doesn't.
        val later = held.mark(stop, t0.plusSeconds(150), listOf(train(450), train(300, vehicle = "126")), at)
        assertEquals(listOf(true, false), later.map { it.held })
        // The same train at another stop has no history there yet.
        val there = held.mark("940GZZLUEUS", t0.plusSeconds(150), listOf(train(400)), listOf("At Camden Town")).single()
        assertFalse(there.held)
    }

    @Test
    fun `a train's mode left blank takes its line's from the line's other trains`() {
        val held = HeldTrains()
        val other = train(600, vehicle = "126")
        fun answer(madeAt: Long, dueAt: Long) =
            held.mark(stop, t0.plusSeconds(madeAt), listOf(train(dueAt, mode = ""), other), listOf("At Camden Town", "At Archway"))
        answer(0, 300)
        assertTrue(answer(150, 450).first().held)
    }

    @Test
    fun `a train whose location goes blank starts over`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        held.answer(60, 360, "")
        assertFalse(held.answer(130, 430, "At Camden Town").held)
        // An older copy with no location doesn't wipe a newer answer's history.
        held.answer(10, 310, "")
        assertTrue(held.answer(260, 560, "At Camden Town").held)
    }

    @Test
    fun `one platform's two labels are one platform`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        val relabeled = train(450).copy(platform = "Platform 4")
        assertTrue(held.mark(stop, t0.plusSeconds(150), listOf(relabeled), listOf("At Camden Town")).single().held)
    }

    @Test
    fun `a platform named by direction alone is followed by that direction`() {
        val held = HeldTrains()
        val northbound = train(300).copy(platform = "Northbound")
        held.mark(stop, t0, listOf(northbound), listOf("At Camden Town"))
        val later = northbound.copy(expectedArrival = t0.plusSeconds(450))
        assertTrue(held.mark(stop, t0.plusSeconds(150), listOf(later), listOf("At Camden Town")).single().held)
        // The other direction is another visit.
        val southbound = later.copy(platform = "Southbound - Platform Unknown")
        assertFalse(held.mark(stop, t0.plusSeconds(160), listOf(southbound), listOf("At Camden Town")).single().held)
    }

    @Test
    fun `a train left out of the stop's newest answer starts over`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        held.mark(stop, t0.plusSeconds(60), listOf(train(400, vehicle = "126")), listOf("At Archway"))
        assertFalse(held.answer(150, 450, "At Camden Town").held)
        // An older copy leaving it out changes nothing.
        held.mark(stop, t0.plusSeconds(100), emptyList(), emptyList())
        assertTrue(held.answer(280, 580, "At Camden Town").held)
        // Nor does another stop's answer.
        held.mark("940GZZLUEUS", t0.plusSeconds(290), emptyList(), emptyList())
        assertTrue(held.answer(300, 600, "At Camden Town").held)
    }

    @Test
    fun `an older copy listing a train the newest answer doesn't starts no history for it`() {
        val held = HeldTrains()
        held.mark(stop, t0.plusSeconds(60), listOf(train(400, vehicle = "126")), listOf("At Archway"))
        // A copy made before that answer lists 125, at Camden Town.
        assertFalse(held.answer(0, 300, "At Camden Town").held)
        // 125 listed for real much later is new: no history from that copy.
        assertFalse(held.answer(130, 430, "At Camden Town").held)
    }

    @Test
    fun `an answer far older than any cache copy is taken, so a clock moved back doesn't freeze the stop`() {
        val held = HeldTrains()
        held.answer(600, 900, "At Camden Town")
        held.answer(0, 300, "At Camden Town")
        assertTrue(held.answer(150, 450, "At Camden Town").held)
    }

    @Test
    fun `a held train is grayed but still counts as running`() {
        val held = train(300).copy(held = true)
        assertTrue(held.grayed)
        assertFalse(held.mayNotRun)
        assertEquals(listOf(Countdown.Part(5, doubtful = false, held = true)), Countdown.parts(listOf(held), t0))
        assertTrue(Countdown.parts(listOf(held), t0).single().grayed)
    }

    @Test
    fun `memory stays bounded, the least recently seen train dropped first`() {
        val held = HeldTrains()
        held.answer(0, 300, "At Camden Town")
        (1..HeldTrains.MAX).forEach { n -> held.answer(10, 300, "At Euston", vehicle = "v$n") }
        // 125 was dropped, so it starts over and isn't held yet.
        assertFalse(held.answer(150, 450, "At Camden Town").held)
    }
}
