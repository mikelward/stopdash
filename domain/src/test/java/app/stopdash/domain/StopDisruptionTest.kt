package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [cleanDisruptionBody]: newline conversion and leading-place-name stripping.
 *
 * [RawSamples] records disruption **description strings captured verbatim** from TfL's
 * `/StopPoint/{id}/Disruption` feed (2026-09-22) — kept as-is so we can keep iterating the
 * cleaning against real input. Only public, non-personal places are recorded (SPEC *Privacy*);
 * the bus case uses a generic notice with a stand-in stop, not a captured watched-stop string.
 * King's Cross St. Pancras carried a step-free notice of this exact shape the day before but not
 * on capture day, so the interchange spelling-variance case below is representative, not verbatim.
 */
class StopDisruptionTest {

    /** Real TfL `description` strings (captured 2026-09-22) — the input the cleaning must handle. */
    private object RawSamples {
        // Bank DLR/tube — a step-free notice led by the bare station name and a colon.
        const val BANK_STEP_FREE =
            "Bank: No Step Free Access - Step free access is not available to/from the King William " +
                "Street entrance due to faulty lifts. For step free access to the DLR and the Northern " +
                "line, use the entrance on Cannon Street."

        // Barons Court — a planned part-closure led by the UPPERCASE name + "STATION" + a colon.
        const val BARONS_COURT_CLOSURE =
            "BARONS COURT STATION: From Monday 6 July until November, westbound trains will not call " +
                "at Barons Court. If travelling westbound from the station, please use eastbound District " +
                "or Piccadilly line trains and change to westbound services at West Kensington (on the " +
                "District line) or Earls Court (on the Piccadilly line) and change to westbound services."
    }

    @Test
    fun `turns literal backslash-n into real newlines and trims each indent line`() {
        // TfL escapes a bus notice's line breaks as literal backslash-n with runs of indent
        // spaces; they must render as real line breaks, not visible "\n" noise, and the body is
        // kept as-is since it names no stop. Generic notice + stand-in stop (SPEC *Privacy*).
        val raw = "Bus Stop Closed\\n    Please use the next stop\\n    or the previous stop \\n    to catch your bus"
        assertEquals(
            "Bus Stop Closed\nPlease use the next stop\nor the previous stop\nto catch your bus",
            cleanDisruptionBody(raw, stopName = "Example Road"),
        )
    }

    @Test
    fun `also converts real CR and CRLF line breaks`() {
        assertEquals(
            "Line one\nLine two\nLine three",
            cleanDisruptionBody("Line one\r\nLine two\rLine three", stopName = "Somewhere"),
        )
    }

    @Test
    fun `a bus notice that never names the stop is returned unchanged`() {
        // The bus case: the body carries no stop name, so nothing is stripped — the card's
        // heading supplies the name instead.
        assertEquals("Bus Stop Closed", cleanDisruptionBody("Bus Stop Closed", stopName = "Fairlawn Avenue"))
    }

    @Test
    fun `strips a real name-led step-free notice (Bank, name plus colon)`() {
        assertEquals(
            "No Step Free Access - Step free access is not available to/from the King William " +
                "Street entrance due to faulty lifts. For step free access to the DLR and the Northern " +
                "line, use the entrance on Cannon Street.",
            cleanDisruptionBody(RawSamples.BANK_STEP_FREE, stopName = "Bank"),
        )
    }

    @Test
    fun `strips a real uppercase name plus STATION plus colon (Barons Court)`() {
        assertEquals(
            "From Monday 6 July until November, westbound trains will not call at Barons Court. If " +
                "travelling westbound from the station, please use eastbound District or Piccadilly line " +
                "trains and change to westbound services at West Kensington (on the District line) or " +
                "Earls Court (on the Piccadilly line) and change to westbound services.",
            cleanDisruptionBody(RawSamples.BARONS_COURT_CLOSURE, stopName = "Barons Court"),
        )
    }

    @Test
    fun `strips a name plus type that sits on its own line above the body`() {
        // The name + station-type suffix ends the first line; the line break is the boundary.
        val raw = "East Finchley Underground Station\nStep-free access is not available."
        assertEquals(
            "Step-free access is not available.",
            cleanDisruptionBody(raw, stopName = "East Finchley"),
        )
    }

    @Test
    fun `does not treat the next line's first word as a type suffix`() {
        // A bare name on its own line, then a body that happens to start with "Station", must NOT
        // pull "Station" up as a type suffix and mangle the warning to "closed" — the intra-name
        // whitespace is same-line only, so this matches no leading-name run and is left intact (Codex).
        val raw = "Victoria\nStation closed"
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "Victoria"))
    }

    @Test
    fun `leaves a bare name on its own line with no type or separator`() {
        // No station-type word and no punctuation separator after the name — just a line break — is
        // not a boundary, so the name is kept rather than stripped on a guess.
        val raw = "Victoria\nClosed until Friday"
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "Victoria"))
    }

    @Test
    fun `strips a leading name spelled differently from the stop name`() {
        // TfL spells King's Cross St. Pancras many ways; the notice may lead with a different
        // spelling (no apostrophe or period) than the stop name. Token matching still strips it.
        // Representative wording — KGX had no live notice on capture day.
        val raw = "Kings Cross St Pancras: No step-free access to the Northern line platforms."
        assertEquals(
            "No step-free access to the Northern line platforms.",
            cleanDisruptionBody(raw, stopName = "King's Cross St. Pancras"),
        )
    }

    @Test
    fun `does not strip a different station that merely shares a prefix`() {
        // "Kilburn" must not tear the front off a "Kilburn Park …" notice: the word after the
        // name is neither a station-type word nor a separator, so there is no leading-name run.
        val raw = "Kilburn Park Underground Station is closed"
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "Kilburn"))
    }

    @Test
    fun `does not strip a nearby station whose name resembles the watched stop`() {
        // The disruption is about Edgware Road, a different station from the watched Edgware; the
        // leading name is not "Edgware" + a boundary, so the body is left intact and still reads
        // as being about Edgware Road (SPEC principle 1 — never mangle a warning).
        val raw = "Edgware Road Underground Station: Reduced service due to a signal failure."
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "Edgware"))
    }

    @Test
    fun `keeps the notice when it is only the station name`() {
        // Stripping the whole text would leave an empty body under the heading, so a notice that
        // is nothing but the name is kept as-is.
        val raw = "East Finchley Underground Station"
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "East Finchley"))
    }

    @Test
    fun `matches the interchange name when the body leads with it`() {
        val raw = "King's Cross St. Pancras Underground Station: lifts out of service"
        assertEquals(
            "lifts out of service",
            cleanDisruptionBody(
                raw,
                stopName = "King's Cross St. Pancras",
                hubName = "King's Cross & St Pancras International",
            ),
        )
    }

    @Test
    fun `leaves a body whose text merely mentions the name mid-sentence`() {
        val raw = "Diversion in place near East Finchley until Friday"
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "East Finchley"))
    }

    // --- King's Cross St. Pancras: the fleet's most-variably-spelled interchange ---
    // The real StopPoint names under HUBKGX (captured from TfL 2026-09-22) span, among others:
    //   "King's Cross St. Pancras Underground Station"   "King's Cross Rail Station"
    //   "London King's Cross Rail Station"               "London Kings Cross Rail Station"
    //   "London St Pancras International Rail Station"    "London St Pancras International LL Rail Station"
    //   "St Pancras International Station"                "St Pancras Intern'l & King's X Stns"
    //   "King's Cross Stn / Pentonville Rd"              "King's Cross & St Pancras International"
    // No name-match folds all of these, and it doesn't have to: on the near-me list these all share
    // hubNaptanCode HUBKGX, so the disruption fold collapses them by hub regardless of spelling
    // (see DepartureRowsTest). The strip below is the display nicety — best-effort — and catches the
    // common case where the notice leads with the watched stop's own name, spelled loosely.

    @Test
    fun `strips a King's Cross notice led by a punctuation-looser spelling than the stop name`() {
        // Watched stop's heading is cleanStopName("King's Cross St. Pancras Underground Station");
        // TfL's step-free notice leads with the station (cf. the real Bank/Farringdon notices),
        // here without the apostrophe or period. letterKey folds that away, so it strips.
        assertEquals(
            "No Step Free Access - Step free access is not available to the Victoria line " +
                "due to a faulty lift. Call us on 0343 222 1234 if you need help planning your journey.",
            cleanDisruptionBody(
                "Kings Cross St Pancras: No Step Free Access - Step free access is not available to " +
                    "the Victoria line due to a faulty lift. Call us on 0343 222 1234 if you need help " +
                    "planning your journey.",
                stopName = "King's Cross St. Pancras",
            ),
        )
    }

    @Test
    fun `keeps the LL suffix as part of the name rather than interpreting it`() {
        // "LL" (Low Level? London Underground Limited? unclear) is not a station-type word, so it
        // stays in the name key — the strip neither drops it nor guesses its meaning. A body led by
        // the same LL spelling still strips.
        assertEquals(
            "Reduced service on the Thameslink platforms.",
            cleanDisruptionBody(
                "London St Pancras International LL: Reduced service on the Thameslink platforms.",
                stopName = "London St Pancras International LL",
            ),
        )
    }

    @Test
    fun `leaves a wildly different King's Cross spelling in the body rather than mis-stripping`() {
        // "St Pancras Intern'l & King's X Stns" reduces to different letters than the watched stop's
        // "King's Cross St. Pancras", so the strip declines rather than guess — best-effort, never
        // mangles (the heading still names the stop, and the hub fold already collapsed the cards).
        val body = "St Pancras Intern'l & King's X Stns: platform alteration in progress"
        assertEquals(body, cleanDisruptionBody(body, stopName = "King's Cross St. Pancras"))
    }

    @Test
    fun `strips a leading member alias even when it differs from the watched stop name`() {
        // You watch King's Cross St. Pancras, but the interchange's notice leads with a *different*
        // member's name. Passing the hub's member aliases lets the strip drop it — no one name
        // catches the interchange's spellings, the union does.
        val raw = "St Pancras International: No step-free access to the Thameslink platforms."
        assertEquals(
            "No step-free access to the Thameslink platforms.",
            cleanDisruptionBody(
                raw,
                stopName = "King's Cross St. Pancras",
                hubName = "King's Cross & St Pancras International",
                aliases = listOf("King's Cross", "London St Pancras International LL", "St Pancras International"),
            ),
        )
    }

    @Test
    fun `without the alias set a different member spelling is left in the body`() {
        // The same notice with no aliases (a stop in no hub, or a failed hub lookup): the leading
        // name doesn't match the watched stop, so it stays — best-effort, never mangled.
        val raw = "St Pancras International: No step-free access to the Thameslink platforms."
        assertEquals(raw, cleanDisruptionBody(raw, stopName = "King's Cross St. Pancras"))
    }

    @Test
    fun `TfL's advice to use the next or previous stop is dropped, never the closure it came with`() {
        // As TfL sends it: literal backslash-n and indent spaces between the lines.
        val advice = StopDisruption("Bus Stop Closed\\n    Please use the next stop\\n    or the previous stop\\n    to catch your bus")
        val diversion = StopDisruption("Bus Stop Closed\\n    Buses on diversion due to a burst water main.")
        // Beside a notice saying the same closure, nothing of it is left to say.
        assertEquals(listOf(diversion), withoutNeighborStopAdvice(listOf(diversion, advice)))
        // Alone, it still says the stop is closed.
        assertEquals(listOf(StopDisruption("Bus Stop Closed")), withoutNeighborStopAdvice(listOf(advice)))
        // Its heading stays where the other notice doesn't hold all its window: the closure may outlast the diversion.
        val from = Instant.parse("2026-11-10T08:00:00Z")
        val shorter = diversion.copy(validFrom = from, validTo = from.plusSeconds(3600))
        val longer = advice.copy(validFrom = from, validTo = from.plusSeconds(7200))
        assertEquals(listOf(shorter, StopDisruption("Bus Stop Closed", from, from.plusSeconds(7200))), withoutNeighborStopAdvice(listOf(shorter, longer)))
        // Anything else it says is kept.
        assertEquals(
            listOf(StopDisruption("Bus Stop Closed\nRoadworks until Friday")),
            withoutNeighborStopAdvice(listOf(StopDisruption("Bus Stop Closed\nPlease use the previous stop to catch your bus.\nRoadworks until Friday"))),
        )
        // A stop with no such advice keeps its notices as they came.
        assertEquals(listOf(diversion), withoutNeighborStopAdvice(listOf(diversion)))
    }
}
