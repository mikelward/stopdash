package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class StopNameTest {
    @Test
    fun `strips the Underground Station suffix`() {
        assertEquals("Charing Cross", cleanStopName("Charing Cross Underground Station"))
        assertEquals("Oxford Circus", cleanStopName("Oxford Circus Underground Station"))
    }

    @Test
    fun `strips DLR, Rail, and Overground suffixes`() {
        assertEquals("Canary Wharf", cleanStopName("Canary Wharf DLR Station"))
        assertEquals("London Bridge", cleanStopName("London Bridge Rail Station"))
        assertEquals("Highbury & Islington", cleanStopName("Highbury & Islington Overground Station"))
    }

    @Test
    fun `strips a bare Station suffix only after the specific ones`() {
        // "Euston Station" → "Euston" via the catch-all; "X Underground Station" loses the
        // whole phrase, not just "Station", because the specific suffix is tried first.
        assertEquals("Euston", cleanStopName("Euston Station"))
        assertEquals("Baker Street", cleanStopName("Baker Street Underground Station"))
    }

    @Test
    fun `leaves a name with no type suffix unchanged`() {
        // Most bus stops carry no "Station" suffix.
        assertEquals("Trafalgar Square (Stop A)", cleanStopName("Trafalgar Square (Stop A)"))
    }

    @Test
    fun `a cross street's slash loses its spaces, however TfL spaces it`() {
        assertEquals("Aldwych/Somerset House", cleanStopName("Aldwych / Somerset House"))
        assertEquals("Aldwych/Somerset House", cleanStopName("Aldwych  /Somerset House"))
        assertEquals("Aldwych/Somerset House", cleanStopName("Aldwych/Somerset House"))
    }

    @Test
    fun `shortens a trailing line-name parenthetical to its lines`() {
        // TfL brackets a station by its line only to tell two same-named stations apart, so the
        // bracket stays wherever the station is named; only the word "Line" goes.
        assertEquals("Hammersmith (H&C)", cleanStopName("Hammersmith (H&C Line)"))
        assertEquals("Hammersmith (Dist&Picc)", cleanStopName("Hammersmith (Dist&Picc Line)"))
        assertEquals("Paddington (H&C)", cleanStopName("Paddington (H&C Line)"))
        assertEquals("Edgware Road (Circle)", cleanStopName("Edgware Road (Circle Line)"))
        // Plural "Lines" too.
        assertEquals("Hammersmith (H&C and Circle)", cleanStopName("Hammersmith (H&C and Circle Lines)"))
    }

    @Test
    fun `shortens the line parenthetical in a full commonName`() {
        // TfL's raw commonName puts the type suffix last, after the parenthetical, so the
        // suffix must be stripped first for the parenthetical to reach the end.
        assertEquals("Hammersmith (H&C)", cleanStopName("Hammersmith (H&C Line) Underground Station"))
        assertEquals("Hammersmith (Dist&Picc)", cleanStopName("Hammersmith (Dist&Picc Line) Underground Station"))
        assertEquals("Edgware Road (Circle)", cleanStopName("Edgware Road (Circle Line) Underground Station"))
        // TfL's hyphenated spelling of one Paddington.
        assertEquals("Paddington (H&C)", cleanStopName("Paddington (H&C Line)-Underground"))
    }

    @Test
    fun `display cleaning is idempotent on a shortened qualifier`() {
        assertEquals("Hammersmith (H&C)", cleanStopName("Hammersmith (H&C)"))
        assertEquals("Edgware Road (Circle)", cleanStopName(cleanStopName("Edgware Road (Circle Line) Underground Station")))
    }

    @Test
    fun `the matching form drops a line qualifier, raw or already shortened`() {
        // Sources disagree on whether to bracket a station by its line, so names pair by this.
        assertEquals("Hammersmith", matchStopName("Hammersmith (H&C Line) Underground Station"))
        assertEquals("Hammersmith", matchStopName("Hammersmith (Dist&Picc Line)"))
        assertEquals("Hammersmith", matchStopName("Hammersmith (H&C)"))
        assertEquals("Hammersmith", matchStopName("Hammersmith (Dist&Picc)"))
        assertEquals("Hammersmith", matchStopName("Hammersmith (H&C and Circle Lines)"))
        assertEquals("Hammersmith", matchStopName(cleanStopName("Hammersmith (H&C and Circle Lines)")))
        assertEquals("Hammersmith", matchStopName("Hammersmith"))
        assertEquals("Paddington", matchStopName("Paddington (H&C Line)-Underground"))
        assertEquals("Edgware Road", matchStopName("Edgware Road (Circle)"))
        assertEquals(matchStopName("Hammersmith (H&C Line)"), matchStopName("Hammersmith"))
    }

    @Test
    fun `the matching form keeps a bracket that names no qualifying line`() {
        // TfL's own "(Bakerloo)" has no "Line", and a place or a bus stop letter is not a line.
        assertEquals("Edgware Road (Bakerloo)", matchStopName("Edgware Road (Bakerloo) Underground Station"))
        assertEquals("Edgware Road (Bakerloo)", cleanStopName("Edgware Road (Bakerloo) Underground Station"))
        assertEquals("Stratford (London)", matchStopName("Stratford (London)"))
        assertEquals("Kensington (Olympia)", matchStopName("Kensington (Olympia)"))
        assertEquals("Trafalgar Square (Stop A)", matchStopName("Trafalgar Square (Stop A)"))
        assertEquals("Example Road (C)", matchStopName("Example Road (C)"))
        assertEquals("Bank/King William Street", matchStopName("Bank Station / King William Street"))
    }

    @Test
    fun `strips National Rail's Rail Station Only qualifier`() {
        // National Rail's board names a station sharing its name with a tube station this way; it
        // must clean to the name TfL's route gives the stop, or a train bound there matches none.
        assertEquals("Heathrow Terminal 5", cleanStopName("Heathrow Terminal 5 (Rail Station Only)"))
        assertEquals("Heathrow Terminals 2 & 3", cleanStopName("Heathrow Terminals 2 & 3 (Rail Station Only)"))
        assertEquals(cleanStopName("Heathrow Terminal 5 Rail Station"), cleanStopName("Heathrow Terminal 5 (Rail Station Only)"))
    }

    @Test
    fun `keeps a geographic parenthetical that names no line`() {
        // "(London)" disambiguates the place, not a line, so it stays.
        assertEquals("Stratford (London)", cleanStopName("Stratford (London)"))
    }

    @Test
    fun `does not empty a name that is only the suffix`() {
        // Guarded so a stop literally named "Station" survives rather than becoming blank.
        assertEquals("Station", cleanStopName("Station"))
    }

    @Test
    fun `strips the type suffix from each part of a name with a cross street`() {
        // Synthetic names in TfL's shapes: the suffix before the slash, padded with extra spaces.
        assertEquals("Parkside/High Road", cleanStopName("Parkside Station   / High Road"))
        assertEquals("Hillview/Mill Lane", cleanStopName("Hillview Underground Station  / Mill Lane"))
        assertEquals("Eastgate (Green)/Bridge Street", cleanStopName("Eastgate (Green Line) Underground Station / Bridge Street"))
        assertEquals("Eastgate/Bridge Street", matchStopName("Eastgate (Green Line) Underground Station / Bridge Street"))
        // Either part: the place after the slash can be the station.
        assertEquals("Market Place/Riverside", cleanStopName("Market Place / Riverside Station"))
        // A road named for a station has no suffix to strip.
        assertEquals("Parkside/Station Road", cleanStopName("Parkside / Station Road"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("Victoria", cleanStopName("  Victoria Underground Station  "))
    }

    @Test
    fun `branchOf pulls the via trunk from towards`() {
        assertEquals("Charing X", branchOf("Battersea Power Station via Charing Cross"))
        assertEquals("Bank", branchOf("Edgware via Bank"))
    }

    @Test
    fun `branchOf folds TfL's inconsistent trunk spellings to one short label`() {
        // TfL's live feed spells the two Northern trunks three ways; all fold to two labels.
        assertEquals("Charing X", branchOf("Edgware via CX"))
        assertEquals("Bank", branchOf("Euston via Bank Branch"))
        // A trailing " Branch" is noise on either trunk.
        assertEquals("Charing X", branchOf("High Barnet via CX Branch"))
        assertEquals("Charing X", branchOf("High Barnet via Charing Cross Branch"))
    }

    @Test
    fun `normalizeBranch folds a stored branch value to the canonical label`() {
        // Used on snapshot restore so an older build's raw spelling reads back canonical.
        assertEquals("Charing X", normalizeBranch("Charing Cross"))
        assertEquals("Charing X", normalizeBranch("CX"))
        assertEquals("Bank", normalizeBranch("Bank Branch"))
        assertEquals("Bank", normalizeBranch("Bank"))
        assertNull(normalizeBranch(null))
        assertNull(normalizeBranch(""))
    }

    @Test
    fun `branchOf is null without a via`() {
        assertNull(branchOf(null))
        assertNull(branchOf("Walthamstow Central"))
        // A bus-style comma tail is not a branch.
        assertNull(branchOf("Pimlico, Grosvenor Road"))
    }

    @Test
    fun `branchOf drops a comma tail after the via`() {
        assertEquals("Bank", branchOf("Morden via Bank, Kennington"))
    }

    @Test
    fun `abbreviateBranch shortens Cross and the compass words a board shortens`() {
        assertEquals("Charing X", abbreviateBranch("Charing Cross"))
        assertEquals("Kings X", abbreviateBranch("Kings Cross"))
        assertEquals("E. Ham", abbreviateBranch("East Ham"))
        assertEquals("W. Croydon", abbreviateBranch("West Croydon"))
        assertEquals("Walthamstow C.", abbreviateBranch("Walthamstow Central"))
        assertEquals("N. Greenwich", abbreviateBranch("North Greenwich"))
    }

    @Test
    fun `abbreviateBranch uses the destination's word forms too`() {
        // The same map a terminus shortens with, so both sides of "Hainault/Newbury Park" shrink
        // alike before either is cut.
        assertEquals("Newbury Pk", abbreviateBranch("Newbury Park"))
        assertEquals("S. Harrow", abbreviateBranch("South Harrow"))
        assertEquals("Kilburn H. Rd", abbreviateBranch("Kilburn High Road"))
    }

    @Test
    fun `abbreviateBranch leaves a branch with nothing safe to shorten unchanged`() {
        assertEquals("Bank", abbreviateBranch("Bank"))
        assertEquals("Battersea", abbreviateBranch("Battersea"))
    }

    @Test
    fun `a Planner point at a station entrance goes by the station, not its street`() {
        assertEquals("Cannon Street", pointName("Cannon Street, Cannon Street Rail Station"))
        // A name whose last part is no station stays whole; one without a street is only cleaned.
        assertEquals("High Street, Kensington", pointName("High Street, Kensington"))
        assertEquals("Bank", pointName("Bank Underground Station"))
    }

    // "Bus Station" goes whole, as the other types do: the row's bus pill already says it's a bus.
    @Test
    fun `drops a bus or coach station suffix whole`() {
        assertEquals("Canada Water", cleanStopName("Canada Water Bus Station"))
        assertEquals("Victoria", cleanStopName("Victoria Coach Station"))
    }

    @Test
    fun `abbreviateStationName shortens International to Intl for display`() {
        assertEquals("King's Cross & St Pancras Intl", abbreviateStationName("King's Cross & St Pancras International"))
        // Whole word only — a name that merely starts with the letters is untouched.
        assertEquals("Internationalist Hall", abbreviateStationName("Internationalist Hall"))
        // Display only: cleanStopName keeps the full spelling, which disruption matching needs.
        assertEquals("St Pancras International", cleanStopName("St Pancras International"))
    }

    @Test
    fun `two names are one stop unless their line qualifiers name different lines`() {
        // A qualifier on one side only is a source that left it off.
        assertTrue(sameStopName("Hammersmith (H&C Line) Underground Station", "Hammersmith"))
        assertTrue(sameStopName("Hammersmith (H&C)", "Hammersmith (H&C and Circle Lines)"))
        assertTrue(sameStopName("Hammersmith (Hammersmith & City Line)", "Hammersmith (H&C)"))
        assertTrue(sameStopName("Hammersmith (Dist&Picc Line)", "Hammersmith (District Line)"))
        // Both qualified, no line in common: Hammersmith's two stations.
        assertFalse(sameStopName("Hammersmith (H&C Line) Underground Station", "Hammersmith (Dist&Picc Line) Underground Station"))
        assertFalse(sameStopName("Hammersmith (H&C)", "Hammersmith (Dist&Picc)"))
        assertTrue(conflictingQualifiers("Hammersmith (H&C)", "Hammersmith (Dist&Picc)"))
        assertFalse(conflictingQualifiers("Hammersmith (H&C)", "Hammersmith"))
        assertEquals(setOf("district", "piccadilly"), qualifierLines("Hammersmith (Dist&Picc Line) Underground Station"))
        assertEquals(setOf("hammersmith-city", "circle"), qualifierLines("Hammersmith (H&C and Circle Lines)"))
        // A place in brackets or a bus stop's letter is no qualifier.
        assertEquals(emptySet<String>(), qualifierLines("Stratford (London)"))
        assertTrue(sameStopName("Stratford (London)", "Stratford (London)"))
    }

}
