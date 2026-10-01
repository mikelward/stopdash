package app.stopdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import org.junit.Test

class LineAlertDirectionsTest {
    private val now = Instant.parse("2026-09-29T08:00:00Z")

    private fun line(id: String, vararg alerts: Pair<String, List<String>>) = TflLineDto(
        id = id,
        lineStatuses = alerts.map { (reason, directions) ->
            TflLineStatusEntryDto(
                statusSeverity = 0,
                statusSeverityDescription = "Special Service",
                reason = reason,
                disruption = TflLineDisruptionDto(directions.map { TflAffectedRouteDto(it) }),
            )
        },
    )

    // The directions of [reason] as [line] lists it: TfL's catch-all severity, no kind of alert.
    private fun LineAlertDirections.directionsOf(lineId: String, reason: String) =
        directionsOf(lineId, TflLineStatusEntryDto(statusSeverity = 0, statusSeverityDescription = "Special Service", reason = reason))

    // The stops of each section [entry] on [lineId] shuts, whichever way.
    private fun LineAlertDirections.stopsOf(lineId: String, entry: TflLineStatusEntryDto) = sectionsOf(lineId, entry).map { it.stops }

    @Test
    fun `an unseen alert's line is claimed once, then answered from what was recorded`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))

        val claimed = cache.claimUnknown(plain, now)
        assertEquals(listOf("bus1"), claimed.map { it.id })
        // A refresh landing mid-lookup doesn't ask again, but knows the answer is still coming.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
        assertTrue(cache.anyUnknown(plain.single()))
        assertNull(cache.directionsOf("bus1", "Diverted"))

        cache.record(claimed, listOf(line("bus1", "Diverted" to listOf("inbound"))))
        assertEquals(setOf("inbound"), cache.directionsOf("bus1", "Diverted"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
        assertFalse(cache.anyUnknown(plain.single()))
    }

    @Test
    fun `a new alert on a known line is claimed again`() {
        val cache = LineAlertDirections()
        val claimed = cache.claimUnknown(listOf(line("bus1", "Old" to emptyList())), now)
        cache.record(claimed, listOf(line("bus1", "Old" to listOf("inbound"))))

        assertEquals(listOf("bus1"), cache.claimUnknown(listOf(line("bus1", "Old" to emptyList(), "New" to emptyList())), now).map { it.id })
    }

    @Test
    fun `an alert TfL scopes to no direction is remembered, and counts for both`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.record(cache.claimUnknown(plain, now), listOf(line("bus1", "Diverted" to emptyList())))

        assertNull(cache.directionsOf("bus1", "Diverted"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
    }

    @Test
    fun `an alert the detail no longer carries isn't looked up again`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Withdrawn" to emptyList()))
        cache.record(cache.claimUnknown(plain, now), emptyList())

        assertNull(cache.directionsOf("bus1", "Withdrawn"))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now))
    }

    @Test
    fun `the sections the detail names an alert as shutting are kept with it, each apart`() {
        val cache = LineAlertDirections()
        // An entry shutting [stops], on a route running them in that order, scoped to [direction].
        fun suspended(stops: List<String>, direction: String = "") = TflLineStatusEntryDto(
            statusSeverity = 3,
            statusSeverityDescription = "Part Suspended",
            reason = "No service between A and C",
            disruption = TflLineDisruptionDto(
                affectedRoutes = listOf(TflAffectedRouteDto(direction, stops.map { TflRouteSectionEntryDto(TflAffectedStopDto(naptanId = it)) })),
                affectedStops = stops.map { TflAffectedStopDto(naptanId = it) },
            ),
        )
        val plain = listOf(TflLineDto("red", lineStatuses = listOf(suspended(emptyList()))))
        // Not looked up yet: no sections known.
        assertEquals(emptyList<List<String>>(), cache.stopsOf("red", suspended(emptyList())))
        // Entries sharing the text name sections apart, and one of them both ways: each kept, in its own
        // order and with its own direction, neither joined to another, which would put the stretch
        // between them (A–B to X–Y) under the alert too, or hand one direction's section to the other.
        cache.record(
            cache.claimUnknown(plain, now),
            listOf(
                TflLineDto(
                    "red",
                    lineStatuses = listOf(
                        suspended(listOf("A", "B"), "outbound"),
                        suspended(listOf("X", "Y"), "outbound"),
                        suspended(listOf("B", "A"), "inbound"),
                        suspended(listOf("A", "B"), "outbound"),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                AffectedSection("outbound", listOf("A", "B")),
                AffectedSection("outbound", listOf("X", "Y")),
                AffectedSection("inbound", listOf("B", "A")),
            ),
            cache.sectionsOf("red", suspended(emptyList())),
        )
        assertEquals(setOf("inbound", "outbound"), cache.directionsOf("red", suspended(emptyList())))
        // An alert the detail didn't carry has none.
        val withdrawn = listOf(line("bus1", "Withdrawn" to emptyList()))
        cache.record(cache.claimUnknown(withdrawn, now), emptyList())
        assertEquals(emptyList<List<String>>(), cache.stopsOf("bus1", withdrawn.single().lineStatuses.single()))
    }

    @Test
    fun `entries sharing an alert's text keep their stops apart by severity and kind`() {
        val cache = LineAlertDirections()
        val reason = "No service between A and C"
        // As TfL may list a closure under way beside the same text planned for later, or ranked
        // otherwise: each entry's stops are its own.
        fun entry(severity: Int, category: String, stops: List<String>) = TflLineStatusEntryDto(
            statusSeverity = severity,
            statusSeverityDescription = "Part Closure",
            reason = reason,
            disruption = TflLineDisruptionDto(
                affectedRoutes = listOf(TflAffectedRouteDto("", stops.map { TflRouteSectionEntryDto(TflAffectedStopDto(naptanId = it)) })),
                affectedStops = stops.map { TflAffectedStopDto(naptanId = it) },
                category = category,
            ),
        )
        val now = entry(5, "RealTime", listOf("X", "Y"))
        val later = entry(5, "PlannedWork", listOf("A", "B", "C"))
        val worse = entry(3, "RealTime", listOf("P", "Q"))
        val plain = listOf(TflLineDto("red", lineStatuses = listOf(now, later, worse).map { it.copy(disruption = TflLineDisruptionDto(category = it.disruption!!.category)) }))
        cache.record(cache.claimUnknown(plain, this.now), listOf(TflLineDto("red", lineStatuses = listOf(now, later, worse))))
        assertEquals(listOf(listOf("X", "Y")), cache.stopsOf("red", plain.single().lineStatuses[0]))
        assertEquals(listOf(listOf("A", "B", "C")), cache.stopsOf("red", plain.single().lineStatuses[1]))
        assertEquals(listOf(listOf("P", "Q")), cache.stopsOf("red", plain.single().lineStatuses[2]))
        // The category is matched as TfL spells it, any case.
        assertEquals(listOf(listOf("X", "Y")), cache.stopsOf("red", now.copy(disruption = TflLineDisruptionDto(category = "realtime"))))
    }

    @Test
    fun `an alert's text seen again as a new kind is looked up again, once`() {
        val cache = LineAlertDirections()
        val reason = "No service between A and C"
        fun entry(severity: Int, stops: List<String>) = TflLineStatusEntryDto(
            statusSeverity = severity,
            statusSeverityDescription = "Part Closure",
            reason = reason,
            disruption = TflLineDisruptionDto(
                affectedRoutes = listOf(TflAffectedRouteDto("", stops.map { TflRouteSectionEntryDto(TflAffectedStopDto(naptanId = it)) })),
                affectedStops = stops.map { TflAffectedStopDto(naptanId = it) },
                category = "RealTime",
            ),
        )
        fun plain(entry: TflLineStatusEntryDto) = TflLineDto("red", lineStatuses = listOf(entry.copy(disruption = TflLineDisruptionDto(category = "RealTime"))))
        // First seen as planned work's severity, the detail giving no stops for it.
        val first = plain(entry(4, emptyList()))
        cache.record(cache.claimUnknown(listOf(first), now), listOf(TflLineDto("red", lineStatuses = listOf(entry(4, emptyList())))))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(first), now))
        // The same text now a part closure under way: a kind not looked up, so it's asked about again
        // rather than left unplaced for good (Codex, PR #446).
        val closure = plain(entry(5, listOf("A", "B", "C")))
        assertTrue(cache.anyUnknown(closure))
        val claimed = cache.claimUnknown(listOf(closure), now)
        assertEquals(listOf("red"), claimed.map { it.id })
        cache.record(claimed, listOf(TflLineDto("red", lineStatuses = listOf(entry(5, listOf("A", "B", "C"))))))
        assertEquals(listOf(listOf("A", "B", "C")), cache.stopsOf("red", closure.lineStatuses.single()))
        assertFalse(cache.anyUnknown(closure))
        // A kind the detail doesn't carry (the responses racing across a change) is looked up once, then
        // known to have no stops, not asked about on every refresh.
        val raced = plain(entry(3, listOf("A", "B")))
        cache.record(cache.claimUnknown(listOf(raced), now), listOf(TflLineDto("red", lineStatuses = listOf(entry(5, listOf("A", "B", "C"))))))
        assertEquals(emptyList<List<String>>(), cache.stopsOf("red", raced.lineStatuses.single()))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(raced), now))
    }

    @Test
    fun `a kind looked up before keeps what it had when a later answer leaves it out`() {
        val cache = LineAlertDirections()
        val reason = "No service between A and C"
        fun entry(severity: Int, direction: String, stops: List<String>) = TflLineStatusEntryDto(
            statusSeverity = severity,
            statusSeverityDescription = "Part Closure",
            reason = reason,
            disruption = TflLineDisruptionDto(
                affectedRoutes = listOf(TflAffectedRouteDto(direction, stops.map { TflRouteSectionEntryDto(TflAffectedStopDto(naptanId = it)) })),
                affectedStops = stops.map { TflAffectedStopDto(naptanId = it) },
            ),
        )
        val closure = entry(5, "inbound", listOf("A", "B", "C"))
        val plainClosure = closure.copy(disruption = null)
        cache.record(cache.claimUnknown(listOf(TflLineDto("red", lineStatuses = listOf(plainClosure))), now), listOf(TflLineDto("red", lineStatuses = listOf(closure))))
        // The same text joins it at a new severity; the detail, racing, carries only the new one. The
        // closure keeps its stops and direction, rather than losing them for good (Codex, PR #446).
        val worse = entry(3, "outbound", listOf("X", "Y"))
        val plain = TflLineDto("red", lineStatuses = listOf(plainClosure, worse.copy(disruption = null)))
        cache.record(cache.claimUnknown(listOf(plain), now), listOf(TflLineDto("red", lineStatuses = listOf(worse))))
        assertEquals(listOf(listOf("A", "B", "C")), cache.stopsOf("red", plainClosure))
        assertEquals(setOf("inbound"), cache.directionsOf("red", plainClosure))
        assertEquals(listOf(listOf("X", "Y")), cache.stopsOf("red", plain.lineStatuses[1]))
        assertEquals(setOf("outbound"), cache.directionsOf("red", plain.lineStatuses[1]))
        assertFalse(cache.anyUnknown(plain))
    }

    @Test
    fun `an alert gone from its line's status, then back, is looked up afresh`() {
        val cache = LineAlertDirections()
        val reason = "No service between A and C"
        fun entry(severity: Int, stops: List<String>) = TflLineStatusEntryDto(
            statusSeverity = severity,
            statusSeverityDescription = "Part Closure",
            reason = reason,
            disruption = TflLineDisruptionDto(
                affectedRoutes = listOf(TflAffectedRouteDto("outbound", stops.map { TflRouteSectionEntryDto(TflAffectedStopDto(naptanId = it)) })),
                affectedStops = stops.map { TflAffectedStopDto(naptanId = it) },
            ),
        )
        fun plain(vararg entries: TflLineStatusEntryDto) = TflLineDto("red", lineStatuses = entries.map { it.copy(disruption = null) })
        val closure = entry(5, listOf("A", "B"))
        cache.record(cache.claimUnknown(listOf(plain(closure)), now), listOf(TflLineDto("red", lineStatuses = listOf(closure))))
        assertEquals(listOf(listOf("A", "B")), cache.stopsOf("red", closure))
        // Another line's refresh says nothing about red's alerts: kept.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(line("bus1")), now))
        assertEquals(listOf(listOf("A", "B")), cache.stopsOf("red", closure))
        // Red good again: that closure is over.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(TflLineDto("red")), now))
        assertEquals(emptyList<List<String>>(), cache.stopsOf("red", closure))
        assertNull(cache.directionsOf("red", closure))
        // The same text later, shutting another stretch: looked up, not placed on the last one's stops
        // (Codex, PR #446).
        val again = entry(5, listOf("B", "C"))
        assertTrue(cache.anyUnknown(plain(again)))
        cache.record(cache.claimUnknown(listOf(plain(again)), now).also { assertEquals(listOf("red"), it.map { l -> l.id }) }, listOf(TflLineDto("red", lineStatuses = listOf(again))))
        assertEquals(listOf(listOf("B", "C")), cache.stopsOf("red", again))
        // One kind of it gone while the text stays at another: that kind is looked up again when back.
        val worse = entry(3, listOf("X", "Y"))
        cache.record(cache.claimUnknown(listOf(plain(again, worse)), now), listOf(TflLineDto("red", lineStatuses = listOf(again, worse))))
        assertEquals(listOf(listOf("X", "Y")), cache.stopsOf("red", worse))
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(plain(worse)), now))
        assertEquals(emptyList<List<String>>(), cache.stopsOf("red", again))
        assertEquals(listOf(listOf("X", "Y")), cache.stopsOf("red", worse))
        assertTrue(cache.anyUnknown(plain(again, worse)))
        // A lookup that failed waits before it's tried again, but not past its alert's end: the same
        // text back after is looked up at once (Codex, PR #446).
        cache.fail(cache.claimUnknown(listOf(plain(again, worse)), now), now)
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(plain(again, worse)), now.plusSeconds(10)))
        cache.claimUnknown(listOf(plain(worse)), now.plusSeconds(20))
        assertEquals(listOf("red"), cache.claimUnknown(listOf(plain(again, worse)), now.plusSeconds(30)).map { it.id })
    }

    @Test
    fun `a lookup under way when its alert ends answers for nothing`() {
        val cache = LineAlertDirections()
        fun entry(stops: List<String>) = TflLineStatusEntryDto(
            statusSeverity = 5,
            statusSeverityDescription = "Part Closure",
            reason = "No service between A and C",
            disruption = TflLineDisruptionDto(
                affectedRoutes = listOf(TflAffectedRouteDto("outbound", stops.map { TflRouteSectionEntryDto(TflAffectedStopDto(naptanId = it)) })),
                affectedStops = stops.map { TflAffectedStopDto(naptanId = it) },
            ),
        )
        val closure = entry(listOf("A", "B"))
        val plain = TflLineDto("red", lineStatuses = listOf(closure.copy(disruption = null)))
        // Claimed; the closure ends before its detail lands, which then isn't written back to place
        // the same text, when it's next seen, on this one's stops (Codex, PR #446).
        val claimed = cache.claimUnknown(listOf(plain), now)
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(listOf(TflLineDto("red")), now))
        cache.record(claimed, listOf(TflLineDto("red", lineStatuses = listOf(closure))))
        assertEquals(emptyList<List<String>>(), cache.stopsOf("red", closure))
        val again = entry(listOf("B", "C"))
        val next = cache.claimUnknown(listOf(plain), now)
        assertEquals(listOf("red"), next.map { it.id })
        cache.record(next, listOf(TflLineDto("red", lineStatuses = listOf(again))))
        assertEquals(listOf(listOf("B", "C")), cache.stopsOf("red", again))
        // Nor does a failure for an ended occurrence hold the next one back.
        val third = TflLineDto("red", lineStatuses = listOf(closure.copy(statusSeverity = 3, disruption = null)))
        val failing = cache.claimUnknown(listOf(third), now)
        cache.claimUnknown(listOf(TflLineDto("red")), now)
        cache.fail(failing, now)
        assertEquals(listOf("red"), cache.claimUnknown(listOf(third), now.plusSeconds(10)).map { it.id })
        // A lookup whose alerts all still show is written as before.
        val still = cache.claimUnknown(listOf(TflLineDto("bus1", lineStatuses = listOf(closure.copy(disruption = null)))), now)
        cache.claimUnknown(listOf(TflLineDto("bus1", lineStatuses = listOf(closure.copy(disruption = null)))), now)
        cache.record(still, listOf(TflLineDto("bus1", lineStatuses = listOf(closure))))
        assertEquals(listOf(listOf("A", "B")), cache.stopsOf("bus1", closure))
    }

    @Test
    fun `entries sharing an alert's text keep every direction between them`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList(), "Diverted" to emptyList()))
        cache.record(
            cache.claimUnknown(plain, now),
            listOf(line("bus1", "Diverted" to listOf("inbound"), "Diverted" to listOf("outbound"))),
        )
        assertEquals(setOf("inbound", "outbound"), cache.directionsOf("bus1", "Diverted"))
    }

    @Test
    fun `entries sharing an alert's text keep their directions apart by severity and kind`() {
        val cache = LineAlertDirections()
        val reason = "Engineering work"
        // A closure one way and minor delays the other, worded the same: neither lends the other its
        // direction, so the delays' way doesn't carry the closure too (Codex, PR #446).
        fun entry(severity: Int, description: String, direction: String) = TflLineStatusEntryDto(
            statusSeverity = severity,
            statusSeverityDescription = description,
            reason = reason,
            disruption = TflLineDisruptionDto(affectedRoutes = listOf(TflAffectedRouteDto(direction))),
        )
        val closed = entry(11, "Part Closed", "inbound")
        val delays = entry(9, "Minor Delays", "outbound")
        val plain = listOf(TflLineDto("red", lineStatuses = listOf(closed, delays).map { it.copy(disruption = null) }))
        cache.record(cache.claimUnknown(plain, now), listOf(TflLineDto("red", lineStatuses = listOf(closed, delays))))
        assertEquals(setOf("inbound"), cache.directionsOf("red", plain.single().lineStatuses[0]))
        assertEquals(setOf("outbound"), cache.directionsOf("red", plain.single().lineStatuses[1]))
    }

    @Test
    fun `a failed lookup waits before it's asked again, longer after each failure`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.fail(cache.claimUnknown(plain, now), now)
        // Not on the next refresh: a minute on.
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, now.plusSeconds(30)))
        assertTrue(cache.anyUnknown(plain.single()))
        val second = now.plus(LineAlertDirections.RETRY_AFTER)
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, second).map { it.id })
        // A second failure waits twice as long.
        cache.fail(plain, second)
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(plain, second.plus(LineAlertDirections.RETRY_AFTER)))
        val third = second.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(2))
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, third).map { it.id })
        // Never longer than the cap, however many failures.
        var at = third
        repeat(20) {
            cache.fail(plain, at)
            at = at.plus(LineAlertDirections.RETRY_AFTER_MAX)
            assertEquals(listOf("bus1"), cache.claimUnknown(plain, at).map { it.id })
        }
        // An answer clears the wait: a new alert on the line is asked about at once.
        cache.record(plain, listOf(line("bus1", "Diverted" to listOf("inbound"))))
        val reworded = listOf(line("bus1", "Diverted again" to emptyList()))
        assertEquals(listOf("bus1"), cache.claimUnknown(reworded, at).map { it.id })
    }

    @Test
    fun `a new alert on a line waiting after failures is looked up at once, and starts its wait over`() {
        val cache = LineAlertDirections()
        val old = listOf(line("bus1", "Diverted" to emptyList()))
        var at = now
        // Failed three times: the next wait is four minutes.
        repeat(3) {
            cache.fail(cache.claimUnknown(old, at), at)
            at = at.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(1L shl it))
        }
        assertEquals(emptyList<TflLineDto>(), cache.claimUnknown(old, now.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(4))))
        // TfL rewords it: a new alert, looked up at once, whose first failure waits a minute.
        val reworded = listOf(line("bus1", "Diverted via Street A" to emptyList()))
        val newAt = now.plus(LineAlertDirections.RETRY_AFTER.multipliedBy(4))
        cache.fail(cache.claimUnknown(reworded, newAt).also { assertEquals(listOf("bus1"), it.map { l -> l.id }) }, newAt)
        assertEquals(listOf("bus1"), cache.claimUnknown(reworded, newAt.plus(LineAlertDirections.RETRY_AFTER)).map { it.id })
        // An alert joining the one that failed is new too.
        cache.fail(reworded, newAt)
        val joined = listOf(line("bus1", "Diverted via Street A" to emptyList(), "Stop closed" to emptyList()))
        assertEquals(listOf("bus1"), cache.claimUnknown(joined, newAt).map { it.id })
    }

    @Test
    fun `a lookup given up on, or failed before a clock set back, is asked again at once`() {
        val cache = LineAlertDirections()
        val plain = listOf(line("bus1", "Diverted" to emptyList()))
        cache.release(cache.claimUnknown(plain, now))
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, now).map { it.id })
        cache.fail(plain, now)
        assertEquals(listOf("bus1"), cache.claimUnknown(plain, now.minusSeconds(60)).map { it.id })
    }

    @Test
    fun `the least recently read alert is dropped past capacity`() {
        val cache = LineAlertDirections(capacity = 1)
        val a = listOf(line("a", "A" to listOf("inbound")))
        val b = listOf(line("b", "B" to listOf("outbound")))
        cache.record(a, a)
        cache.record(b, b)

        assertNull(cache.directionsOf("a", "A"))
        assertEquals(setOf("outbound"), cache.directionsOf("b", "B"))
    }
}
