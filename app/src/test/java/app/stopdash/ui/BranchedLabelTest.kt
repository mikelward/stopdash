package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [branchedLabel]'s rule, tested apart from the measuring composable. Widths are the abstract px the
 * caller measures; only their relative sizes matter, so the numbers here are chosen to land each
 * case rather than to match any real font. The composable lays the branch out whole at its natural
 * width and gives the terminus the leftover (ellipsized), so [branchedLabel] only picks which
 * strings show.
 */
class BranchedLabelTest {

    @Test
    fun `joins the full terminus and full branch with a slash when both fit`() {
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            floorLabel = "Battersea P.",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 1000,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 200,
            abbrevBranchWidth = 140,
            minStubWidth = 20,
        )
        assertEquals("Battersea Power", r.terminus)
        assertEquals("/Charing Cross", r.branch)
        assertEquals(null, r.contentDescription)
        assertEquals(null, r.branchDescription)
    }

    @Test
    fun `abbreviates the terminus while the full branch still fits beside it`() {
        // The terminus yields first: the full one doesn't fit beside the full branch, but its
        // word-abbreviated form does, so the branch stays whole.
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            floorLabel = "High B.",
            branch = "Bank",
            abbreviatedBranch = "Bank",
            maxWidth = 200,
            labelWidth = 180,
            abbrevLabelWidth = 120,
            fullBranchWidth = 60,
            abbrevBranchWidth = 60,
            minStubWidth = 20,
        )
        assertEquals("H. Barnet", r.terminus)
        assertEquals("/Bank", r.branch)
        assertEquals("High Barnet", r.contentDescription)
    }

    @Test
    fun `abbreviates both sides before dropping the terminus to its floor`() {
        // The floor would fit beside the full branch, but both sides are abbreviated before either
        // is cut: the short branch leaves the word-abbreviated terminus room, so that shows rather
        // than the floor.
        val r = branchedLabel(
            label = "South Harrow Road",
            abbreviatedLabel = "S. Harrow Rd",
            floorLabel = "South H. R.",
            branch = "Newbury Park",
            abbreviatedBranch = "Newbury Pk",
            maxWidth = 200,
            labelWidth = 150,
            abbrevLabelWidth = 130,
            fullBranchWidth = 100,
            abbrevBranchWidth = 60,
            minStubWidth = 20,
        )
        assertEquals("S. Harrow Rd", r.terminus)
        assertEquals("/Newbury Pk", r.branch)
        assertEquals("South Harrow Road", r.contentDescription)
        // The shortened branch keeps its full name for a screen reader.
        assertEquals(BranchSpoken("Newbury Park"), r.branchDescription)
    }

    @Test
    fun `drops the terminus to its floor beside the kept full branch`() {
        // Neither the full nor the word-abbreviated terminus fits beside the full branch, and this
        // branch has no shorter form, so the floor shows beside it and the branch is never clipped.
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            floorLabel = "Battersea P.",
            branch = "Bank",
            abbreviatedBranch = "Bank",
            maxWidth = 400,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 150,
            abbrevBranchWidth = 150,
            minStubWidth = 30,
        )
        assertEquals("Battersea P.", r.terminus)
        assertEquals("/Bank", r.branch)
        assertEquals("Battersea Power", r.contentDescription)
    }

    @Test
    fun `shortens the branch to its board form to keep the full terminus`() {
        // Not even the abbreviated terminus fits beside the full branch, but beside the short branch
        // the FULL terminus fits, so the terminus stays whole.
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            floorLabel = "High B.",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 150,
            labelWidth = 100,
            abbrevLabelWidth = 80,
            fullBranchWidth = 100,
            abbrevBranchWidth = 40,
            minStubWidth = 20,
        )
        assertEquals("High Barnet", r.terminus)
        assertEquals("/Charing X", r.branch)
        assertEquals(null, r.contentDescription)
    }

    @Test
    fun `drops the terminus to its floor beside the short branch under tighter pressure`() {
        // Neither terminus form fits beside the full branch, and only the floor fits the leftover
        // beside the short branch.
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            floorLabel = "Battersea P.",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 400,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 250,
            abbrevBranchWidth = 150,
            minStubWidth = 30,
        )
        assertEquals("Battersea P.", r.terminus)
        assertEquals("/Charing X", r.branch)
        assertEquals("Battersea Power", r.contentDescription)
    }

    @Test
    fun `shows the branch alone and bare when not even a stub fits beside it`() {
        // At an extreme font scale on a very narrow row the whole branch fills the column, leaving
        // less than a stub (a glyph plus its ellipsis) — so the branch takes the row bare (board short
        // form, no leading slash, no orphaned "/"), the full name kept for a screen reader.
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            floorLabel = "High B.",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 220,
            labelWidth = 300,
            abbrevLabelWidth = 200,
            fullBranchWidth = 260,
            abbrevBranchWidth = 200,
            minStubWidth = 30,
        )
        assertEquals("", r.terminus)
        assertEquals("Charing X", r.branch)
        assertFalse("no leading slash when the branch stands alone", r.branch.startsWith("/"))
        assertEquals("High Barnet", r.contentDescription)
        assertEquals(BranchSpoken("Charing Cross", terminus = "High Barnet"), r.branchDescription)
    }

    @Test
    fun `keeps a terminus stub beside the short branch when at least a stub fits`() {
        // One step wider than the bare case: the leftover clears the stub width, so the floor terminus
        // rides beside the whole short branch (the render ellipsizes it to a stub like "High…").
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            floorLabel = "High B.",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 240,
            labelWidth = 300,
            abbrevLabelWidth = 200,
            fullBranchWidth = 260,
            abbrevBranchWidth = 200,
            minStubWidth = 30,
        )
        assertEquals("High B.", r.terminus)
        assertEquals("/Charing X", r.branch)
        assertTrue("the branch keeps its slash beside the terminus", r.branch.startsWith("/"))
        assertEquals("High Barnet", r.contentDescription)
    }
}
