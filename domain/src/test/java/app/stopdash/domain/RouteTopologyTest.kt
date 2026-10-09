package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTopologyTest {
    // A minimal Northern-line topology with a stop on each shared leg beyond the junctions:
    // Highgate (HGT) north of Camden Town, Oval (OVL) south of Kennington. The two central
    // trunks differ only between Camden Town and Kennington — Mornington Crescent (MTC) and the
    // Charing Cross stations (CHX) on Charing X, Bank (BNK) on Bank.
    private val topology = RouteTopology(
        mapOf(
            "northern" to listOf(
                RoutePattern("Bank", listOf(HBT, HGT, CTN, EUS, BNK, KNG, OVL, MDN), "High Barnet", "Morden"),
                RoutePattern("Charing X", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, OVL, MDN), "High Barnet", "Morden"),
                // Battersea is reached only over the Charing Cross trunk, but TfL's route name
                // carries no "via", so this pattern has no branch — the unlabeled-pattern case.
                RoutePattern(null, listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, BPS), "High Barnet", "Battersea Power"),
            ),
        ),
    )

    private fun label(stopId: String, destination: String, branch: String?) =
        topology.grouping("northern", stopId, destination, branch).label

    private fun mergeKey(stopId: String, destination: String, branch: String?) =
        topology.grouping("northern", stopId, destination, branch).mergeKey

    @Test
    fun `branches merge and lose the label past the junction, on the shared track`() {
        // Highgate → High Barnet (north of Camden Town): the trunks have physically joined, so
        // the two are the same service from here — one merged line, no branch label.
        assertNull(label(HGT, "High Barnet", "Bank"))
        assertNull(label(HGT, "High Barnet", "Charing X"))
        assertEquals(mergeKey(HGT, "High Barnet", "Bank"), mergeKey(HGT, "High Barnet", "Charing X"))
        // Oval → Morden (south of Kennington): likewise past the southern junction.
        assertNull(label(OVL, "Morden", "Bank"))
        assertEquals(mergeKey(OVL, "Morden", "Bank"), mergeKey(OVL, "Morden", "Charing X"))
    }

    @Test
    fun `branches stay labeled at the junction, reached by different approaches`() {
        // Camden Town → High Barnet: both go to High Barnet the same way onward, but a Bank train
        // and a Charing Cross train reach Camden by different approaches (Euston vs Mornington
        // Crescent), so the branch stays — the rider still picks a trunk/platform here.
        assertEquals("Bank", label(CTN, "High Barnet", "Bank"))
        assertEquals("Charing X", label(CTN, "High Barnet", "Charing X"))
        assertNotEquals(mergeKey(CTN, "High Barnet", "Bank"), mergeKey(CTN, "High Barnet", "Charing X"))
        // Kennington → Morden: the southern junction, kept the same way.
        assertEquals("Bank", label(KNG, "Morden", "Bank"))
        assertEquals("Charing X", label(KNG, "Morden", "Charing X"))
    }

    private fun rideGrouping(stopId: String, alightingId: String, destination: String, branch: String?) =
        topology.rideGrouping("northern", stopId, alightingId, destination, branch)

    @Test
    fun `a trip's board drops the branch where it doesn't change the ride`() {
        // Boarding at Camden Town for Highgate: a train up via Bank and one via Charing X ride the same
        // stops to Highgate, so which trunk they came up isn't the rider's concern (maintainer, 2026-10-09).
        assertNull(rideGrouping(CTN, HGT, "High Barnet", "Bank").label)
        assertNull(rideGrouping(CTN, HGT, "High Barnet", "Charing X").label)
        assertEquals(rideGrouping(CTN, HGT, "High Barnet", "Bank").mergeKey, rideGrouping(CTN, HGT, "High Barnet", "Charing X").mergeKey)
        // The same board as a stop's board still tells them apart at the junction.
        assertEquals("Bank", label(CTN, "High Barnet", "Bank"))
    }

    @Test
    fun `a trip's board keeps the branch where it changes the ride`() {
        // Boarding at Kennington for Camden Town: the two trunks call at different stops on the way.
        assertEquals("Bank", rideGrouping(KNG, CTN, "High Barnet", "Bank").label)
        assertEquals("Charing X", rideGrouping(KNG, CTN, "High Barnet", "Charing X").label)
        assertNotEquals(rideGrouping(KNG, CTN, "High Barnet", "Bank").mergeKey, rideGrouping(KNG, CTN, "High Barnet", "Charing X").mergeKey)
        // Getting off on the shared stretch past the junction: no difference to the ride, so no label,
        // whichever trunk ahead the train runs on to (Highgate to Camden Town, southbound).
        assertNull(rideGrouping(HGT, CTN, "Morden", "Bank").label)
        assertNull(rideGrouping(HGT, CTN, "Morden", "Charing X").label)
    }

    @Test
    fun `a trip's board falls back to the stop's grouping for a branch it can't place`() {
        // No pattern carries "Bank" from Euston to Mornington Crescent (only Charing X calls there).
        assertEquals(topology.grouping("northern", EUS, "High Barnet", "Bank"), rideGrouping(EUS, MTC, "High Barnet", "Bank"))
        // An unmodeled line keeps TfL's label.
        assertEquals("Bank", topology.rideGrouping("other", CTN, HGT, "High Barnet", "Bank").label)
    }

    @Test
    fun `branches stay labeled where the trunk is a choice ahead`() {
        // Camden southbound to Morden: the trunks diverge ahead (different central stations).
        assertEquals("Bank", label(CTN, "Morden", "Bank"))
        assertEquals("Charing X", label(CTN, "Morden", "Charing X"))
        assertNotEquals(mergeKey(CTN, "Morden", "Bank"), mergeKey(CTN, "Morden", "Charing X"))
        // Euston → High Barnet keeps it too: only Charing X calls at Mornington Crescent.
        assertEquals("Bank", label(EUS, "High Barnet", "Bank"))
        assertEquals("Charing X", label(EUS, "High Barnet", "Charing X"))
    }

    @Test
    fun `a single-trunk stop drops the label — no alternative to choose`() {
        // Bank station is on the Bank trunk only, so every Morden train there is via Bank: no
        // choice, so the redundant "Bank" label is dropped.
        assertNull(label(BNK, "Morden", "Bank"))
    }

    @Test
    fun `a single-trunk stop drops the label for an unmodeled short-working too`() {
        // The King's Cross case. Bank station is Bank-only, so a train tagged "Bank" bound for a
        // stop the asset models as no pattern's terminus (a short working, like Golders Green or
        // Finchley Central from King's Cross) has no alternative trunk to choose. Resolution falls
        // back to raw, but the redundant "Bank" is still dropped.
        assertNull(label(BNK, "Highgate", "Bank"))
        // A two-branch stop keeps the raw label for the same unmodeled destination — there a "Bank"
        // train really is one of two trunks, so we don't guess it away.
        assertEquals("Bank", label(CTN, "Highgate", "Bank"))
    }

    @Test
    fun `an arrival whose branch matches no serving pattern keeps it raw`() {
        // TfL tags a Battersea train "via Charing Cross" (branch "Charing X"), but the Battersea
        // pattern carries no via — the branch matches no serving pattern, so it stays raw. The
        // maintainer prefers Battersea keeping "/Charing X" (the trunk it runs) over dropping it.
        assertEquals("Charing X", label(KNG, "Battersea Power", "Charing X"))
        // A branch the line never runs (a rename/extension the asset predates) likewise stays raw.
        assertEquals("Riverside", label(BNK, "Morden", "Riverside"))
        // And an unmatched branch where two paths serve the leg stays raw — we don't guess.
        assertEquals("Victoria", label(CTN, "Morden", "Victoria"))
    }

    @Test
    fun `an unknown line keeps the raw branch and merges nothing`() {
        val a = topology.grouping("victoria", "940GZZLUVIC", "Brixton", "Bank")
        assertEquals("Bank", a.label)
        val b = topology.grouping("victoria", "940GZZLUVIC", "Brixton", "Charing X")
        assertNotEquals(a.mergeKey, b.mergeKey)
    }

    @Test
    fun `an unresolvable destination or stop keeps the raw branch`() {
        // Destination is no pattern's terminus from this stop → raw.
        assertEquals("Bank", label(CTN, "Nowhere", "Bank"))
        // Stop not on any pattern → raw.
        assertEquals("Bank", label("940GZZLUXXX", "Morden", "Bank"))
    }

    @Test
    fun `the empty topology resolves every branch as raw`() {
        val g = RouteTopology.EMPTY.grouping("northern", CTN, "High Barnet", "Charing X")
        assertEquals("Charing X", g.label)
        assertEquals("raw:Charing X", g.mergeKey)
    }

    private companion object {
        const val HBT = "940GZZLUHBT"
        const val HGT = "940GZZLUHGT"
        const val CTN = "940GZZLUCTN"
        const val MTC = "940GZZLUMTC"
        const val EUS = "940GZZLUEUS"
        const val BNK = "940GZZLUBNK"
        const val CHX = "940GZZLUCHX"
        const val KNG = "940GZZLUKNG"
        const val OVL = "940GZZLUOVL"
        const val MDN = "940GZZLUMDN"
        const val BPS = "940GZZBPSUST"
    }
}
