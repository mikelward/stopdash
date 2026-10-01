package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisruptionLabelTest {
    @Test
    fun `keeps TfL's own wording and severity when it names the disruption`() {
        // "Severe Delays" says what is wrong; the reason only elaborates, so it is not
        // second-guessed, and TfL's severity is kept as the comparison key.
        assertEquals(
            // The reason is retained as fullText for the route detail, though the label is TfL's.
            ResolvedDisruption("Severe Delays", 6, fullText = "Severe delays while we fix a faulty train."),
            resolveDisruption("Severe Delays", 6, "Severe delays while we fix a faulty train."),
        )
        assertEquals(ResolvedDisruption("Part Closure", 5), resolveDisruption("Part Closure", 5, ""))
    }

    @Test
    fun `names a bus diversion hidden behind Special Service`() {
        val reason = "Road will be closed for works. Buses will be diverted and will miss stops."
        assertEquals(ResolvedDisruption("Diversion", 5, fullText = reason, inferred = true), resolveDisruption("Special Service", 0, reason))
    }

    @Test
    fun `matches the diversion keyword regardless of case`() {
        assertEquals("Diversion", resolveDisruption("Special Service", 0, "Route on DIVERSION.").label)
        assertEquals("Diversion", resolveDisruption("special service", 0, "Buses Diverted.").label)
        // TfL's other words for a curtailment (maintainer, 2026-10-01).
        assertEquals("Curtailed", resolveDisruption("Special Service", 0, "Route 99 is cutting short of its normal route.").label)
        assertEquals("Curtailed", resolveDisruption("Special Service", 0, "Buses towards North End will terminate at 'Beta Road' (D).").label)
        // Denied, it names none (Codex, PR #455).
        assertEquals("Service Alert", resolveDisruption("Special Service", 0, "Buses aren't terminating at Beta Road.").label)
        assertEquals("Service Alert", resolveDisruption("Special Service", 0, "The service will not be cut short.").label)
        assertEquals("Service Alert", resolveDisruption("Special Service", 0, "The service is not expected to be cut short.").label)
        assertEquals("Service Alert", resolveDisruption("Special Service", 0, "No buses are diverted between Alpha Road and Beta Road.").label)
        // A negation of something else in the clause doesn't deny it (Codex, PR #455).
        assertEquals("Diversion", resolveDisruption("Special Service", 0, "Buses are not serving stops A and B and are diverted via Example Road.").label)
    }

    @Test
    fun `reads a diversion out of a reason that mentions other effects`() {
        // Only a diversion/curtailment is inferred from the prose; a road closure or delays
        // mentioned alongside it don't change the label.
        assertEquals(
            "Diversion",
            resolveDisruption("Special Service", 0, "Road closed; buses diverted with minor delays.").label,
        )
    }

    @Test
    fun `does not infer a suspension from the prose, since TfL words that itself`() {
        // A section outage ("suspended between A and B", "no service between A and B") is not
        // read as a whole-route suspension — real suspensions arrive as TfL's own "Suspended"
        // status on the graded path. A diversion named alongside still wins.
        assertEquals(
            "Diversion",
            resolveDisruption("Special Service", 0, "No service between A and B; buses diverted.").label,
        )
        // Prose describing only a section outage, with no diversion → the generic Service Alert.
        assertEquals("Service Alert", resolveDisruption("Special Service", 0, "Service suspended between A and B.").label)
        assertEquals("Service Alert", resolveDisruption("Special Service", 0, "No service between A and B.").label)
    }

    @Test
    fun `falls back to a low-priority Service Alert, never Special Service`() {
        // A catch-all that named nothing is a true fallback (isFallback = true) — the one
        // thing sorted below every informative status regardless of severity.
        assertEquals(
            ResolvedDisruption("Service Alert", 9, isFallback = true, fullText = "Planned engineering works this weekend.", inferred = true),
            resolveDisruption("Special Service", 0, "Planned engineering works this weekend."),
        )
        assertEquals(
            ResolvedDisruption("Service Alert", 9, isFallback = true, inferred = true),
            resolveDisruption("Special Service", 0, ""),
        )
    }

    @Test
    fun `the generic fallback sorts after every specific label, whatever its severity`() {
        val fallback = ResolvedDisruption("Service Alert", 9, isFallback = true)
        // A specific label milder than the fallback (higher TfL number) still wins over it.
        assertEquals(
            ResolvedDisruption("Diverted", 15),
            mostSevereDisruption(listOf(fallback, ResolvedDisruption("Diverted", 15))),
        )
        // And a more severe specific label wins too — severity still orders specific labels.
        assertEquals(
            ResolvedDisruption("Suspended", 2),
            mostSevereDisruption(listOf(fallback, ResolvedDisruption("Suspended", 2))),
        )
        // With nothing but fallbacks, the fallback is what's shown.
        assertEquals(fallback, mostSevereDisruption(listOf(fallback)))
        assertNull(mostSevereDisruption(emptyList()))
    }

    @Test
    fun `a severe status that only lacks wording is not demoted behind a milder named one`() {
        // A blank description at a severe TfL severity (a closure-level 1) borrows the
        // "Service Alert" label but is not a fallback, so it keeps that severity and wins over
        // a coexisting, milder, named "Minor Delays" — the severe condition is never hidden.
        assertEquals(
            ResolvedDisruption("Service Alert", 1),
            mostSevereDisruption(
                listOf(ResolvedDisruption("Minor Delays", 9), resolveDisruption("", 1, "")),
            ),
        )
    }

    @Test
    fun `a blank description keeps its real severity under a generic label`() {
        // Blank wording at a real severity is a disruption missing its text, not the vague
        // catch-all — it keeps severity 4 so it still sorts correctly.
        assertEquals(ResolvedDisruption("Service Alert", 4), resolveDisruption("", 4, ""))
    }
}
