package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stations a line's alert names (SPEC *Disruptions*). Stock station names, no user data. */
class AlertStopsTest {
    private fun stops(vararg names: String) = names.mapIndexed { i, name -> RouteStop("S$i", name) }

    private fun mentioned(text: String?, vararg names: String) =
        AlertStops.mentioned(text, stops(*names)).map { id -> names[id.removePrefix("S").toInt()] }.toSet()

    @Test
    fun `a station kept with its line qualifier is still found by its bare name`() {
        // The route lists "Hammersmith (H&C)" ([cleanStopName]); an alert writes "Hammersmith".
        val hammersmith = cleanStopName("Hammersmith (H&C Line) Underground Station")
        val paddington = cleanStopName("Paddington (H&C Line)-Underground")
        assertEquals(
            setOf(hammersmith, paddington),
            mentioned("No service between Hammersmith and Paddington.", hammersmith, "Edgware Road (Circle)", paddington),
        )
        // And by the shortened qualifier, too.
        assertEquals(setOf(hammersmith), mentioned("Hammersmith (H&C) is closed.", hammersmith, "Edgware Road (Circle)"))
    }

    @Test
    fun `marks every station the alert names`() {
        assertEquals(
            setOf("Moorgate", "Monument"),
            mentioned("Diverted between Moorgate and Monument due to roadworks.", "Old Street", "Moorgate", "Bank", "Monument"),
        )
    }

    @Test
    fun `a stop quoted as its sign reads is the route's stop listed without "Station"`() {
        // A route lists its stops cleaned ("Bank / King William Street"), where the alert quotes the
        // sign: "Station" on the stop's part, no spaces round the slash (maintainer, 2026-10-02).
        val route = stops("Bank / King William Street", "Bank / Second Street", "Middle Road", "Moorgate", "Old Street")
        val alert = "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'."
        assertEquals(setOf("S0", "S1", "S2", "S3"), AlertStops.stretched(alert, route))
        // The other stop at Bank is only between the ends, never named by the quoted one's "Bank".
        assertEquals(setOf("S0", "S3"), AlertStops.mentioned(alert, route))
        // Its cross street named "Station" instead, or the slash spaced, reads the same.
        assertEquals(setOf("S0", "S3"), AlertStops.mentioned(alert.replace("Bank Station/King William Street", "Bank / King William Street Station"), route))
    }

    @Test
    fun `a stop the alert quotes is named, an apostrophe inside a word isn't a break`() {
        // Bus alerts quote their stops ('Moorgate Station'), slash and all (maintainer, 2026-10-01).
        assertEquals(
            setOf("Bank Station / King William Street", "Moorgate Station"),
            mentioned(
                "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'.",
                "Bank Station / King William Street", "Moorgate Station", "Old Street Station",
            ),
        )
        // The stops between quoted ends are in the stretch, a cross street written without spaces too.
        assertEquals(
            listOf("S0", "S1", "S2"),
            AlertStops.affected(
                "Not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'.",
                stops("Bank Station / King William Street", "Middle Road", "Moorgate Station", "Old Street Station"),
            ).toList(),
        )
        // A stretch placed only in the words TfL's bus alerts use for stops not served (maintainer,
        // 2026-10-01); anything else places nothing (Codex, PR #455).
        val line = stops("Bank Station", "Middle Road", "Moorgate Station", "Old Street Station")
        assertEquals(setOf("S0", "S1", "S2"), AlertStops.stretched("Buses are not serving stops between 'Bank Station' and 'Moorgate Station'.", line))
        assertEquals(setOf("S0", "S1", "S2"), AlertStops.stretched("Buses will not serve stops between Bank Station and Moorgate Station.", line))
        // The other form, with a direction and a stop letter (made-up stops, TfL's wording).
        val road = stops("Alpha Road", "Beta Road", "Gamma Road / Delta Road", "North End")
        assertEquals(
            setOf("S0", "S1", "S2"),
            AlertStops.stretched("Towards North End, the stops from 'Alpha Road' (E) to 'Gamma Road / Delta Road' will not be served. Please allow extra time for your journey.", road),
        )
        assertEquals(setOf("S0", "S1", "S2"), AlertStops.affected("Towards North End, the stops from 'Alpha Road' (E) to 'Gamma Road / Delta Road' will not be served.", road))
        assertEquals(
            setOf("S0", "S1", "S2"),
            AlertStops.stretched(
                "Buses towards North End are diverted via Example Street, Other Lane, and Last Terrace. Stops between " +
                    "'Alpha Road' (H) and 'Gamma Road / Delta Road' (CL) will not be served. Please allow extra time for your journeys.",
                road,
            ),
        )
        // A single stop missed.
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "Buses will divert via Example Street and Other Lane, missing the stop 'St Paul's Station' (SP). Please allow extra time for your journey",
                stops("Bank Station", "St Paul's Station", "Moorgate Station"),
            ),
        )
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "Road will be closed southbound for water works from 22:00 6 October until 05:00 7 October. Buses towards North End and South End will miss stop Millbank.",
                stops("Westminster Station", "Millbank", "Vauxhall Station"),
            ),
        )
        // Stops named one by one, a stop pair with its cross street among them (made-up stops).
        val listed = stops("Alpha Road", "Beta Lane Station", "Gamma Road / Delta Road", "North End")
        assertEquals(setOf("S1", "S2"), AlertStops.stretched("Buses will not serve stops 'Beta Lane Station' (F) and 'Gamma Road/Delta Road' (W).", listed))
        // "Are not being served", and a name with "&" in it, written in another case.
        val school = stops("Alpha Road", "Example Academy-Primary", "Middle Road", "Example East & Example Academy Secondary", "North End")
        assertEquals(
            setOf("S1", "S2", "S3"),
            AlertStops.stretched("Stops from 'Example Academy-Primary' to 'Example east & Example Academy Secondary' are not being served. Please allow extra time for your journey.", school),
        )
        assertEquals(
            setOf("S0", "S1", "S2"),
            AlertStops.stretched(
                "EXAMPLE ROAD, E1: ROUTES 98 99 are on diversion towards North End. Buses are diverting via Example Street and Other Lane. " +
                    "Bus stops from 'Alpha Road' to 'Gamma Road / Delta Road' (PR) will be missed. Please allow extra time for\nyour journey.",
                road,
            ),
        )
        assertEquals(
            setOf("S0", "S1", "S2"),
            AlertStops.stretched(
                "EXAMPLE R0AD, E1: ROUTES 98 99 southbound are on diversion via Example Street. Buses are missing Alpha Road 'E' to " +
                    "Gamma Road/Delta Road (T). Routes 97 and 96 also follows the same diversion but does not miss any stops.",
                road,
            ),
        )
        // Unquoted names, each with its letter quoted.
        assertEquals(setOf("S0", "S1", "S2"), AlertStops.stretched("Buses are missing stops from Alpha Road 'F' to Gamma Road / Delta Road 'T'.", road))
        // A curtailment leaves out the stops after where buses terminate and before where they start
        // (made-up stops, TfL's wording).
        val cut = stops("South End", "Alpha Road", "Beta Road", "Gamma Road", "North End")
        assertEquals(
            setOf("S3", "S4"),
            AlertStops.stretched(
                "EXAMPLE ROAD, E1: Route 99 is curtailed to Beta Road 'J' due to water works until 22:00 on Monday 30 November. " +
                    "Buses towards North End are terminating at the stop Beta Road 'V' after stop Beta Road 'J'.",
                cut,
            ),
        )
        assertEquals(
            setOf("S0", "S4"),
            AlertStops.stretched(
                "Buses towards North End will terminate at 'Gamma Road' (D) and buses towards South End will start from stop at 'Alpha Road' (C). Please allow more time for your journey.",
                cut,
            ),
        )
        assertEquals(setOf("S0"), AlertStops.stretched("Starting services towards North End from stop Alpha Road 'G'.", cut))
        // One it can't place leaves where the alert applies unknown, beside a stretch it can (Codex, PR #455).
        assertEquals(emptySet<String>(), AlertStops.stretched("Route 99 is cutting short of its normal route. Buses are not serving stops between 'Alpha Road' and 'Beta Road'.", cut))
        assertEquals(emptySet<String>(), AlertStops.stretched("Buses will start from stop at Example Lane. Buses are not serving stops between 'Alpha Road' and 'Beta Road'.", cut))
        // Nor beside another curtailment it can place, unless it's said of the whole route (Codex, PR #455).
        assertEquals(
            emptySet<String>(),
            AlertStops.stretched("Buses will terminate at Beta Road. Some journeys are cutting short of their normal route.", cut),
        )
        // Placed alone, the same terminus does place it: the guard above is what keeps it on.
        assertEquals(setOf("S3", "S4"), AlertStops.stretched("Buses will terminate at Beta Road.", cut))
        assertEquals(
            emptySet<String>(),
            AlertStops.stretched("Buses towards North End will terminate at Beta Road. Buses towards South End are cutting short of their normal route.", cut),
        )
        // Placed by where buses terminate and start, the same words are read.
        assertEquals(
            setOf("S0", "S4"),
            AlertStops.stretched(
                "EXAMPLE BUS STATION, E1: Route 99 is cutting short of it's normal route due to ongoing Bus Station works. Buses towards " +
                    "North End will terminate at 'Gamma Road' (D) and buses towards South End will start from stop at 'Alpha Road' (C).",
                cut,
            ),
        )
        // Negated, it says where buses don't stop short (Codex, PR #455).
        assertEquals(emptySet<String>(), AlertStops.stretched("Route 99 is curtailed due to roadworks; buses will not terminate at Beta Road.", cut))
        assertEquals(emptySet<String>(), AlertStops.stretched("Buses won't start from stop Beta Road.", cut))
        assertEquals(emptySet<String>(), AlertStops.stretched("Route 99 is curtailed; buses are not expected to terminate at Beta Road.", cut))
        // So for every wording: a negation anywhere earlier in its clause.
        assertEquals(emptySet<String>(), AlertStops.stretched("It is not yet known whether buses are missing the stop 'Beta Road' (SP).", cut))
        assertEquals(emptySet<String>(), AlertStops.stretched("Buses are no longer expected to be missing stops from Alpha Road to Gamma Road.", cut))
        assertEquals(emptySet<String>(), AlertStops.stretched("Route 99 is diverted via Example Street. No stops between Alpha Road and Gamma Road will be missed.", cut))
        // Stations and well-known places, as TfL wrote them (maintainer, 2026-10-01).
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "NEWGATE STREET, EC4: From 14:00 Sunday 06 September until 18:00 Sunday 29 November, ROUTE 76 will be on diversion towards " +
                    "Tottenham Hale due to urban realm works. Buses will divert via St Martin's Le Grand and Angel Street, missing the stop " +
                    "'St Paul's Station' (SP). Please allow extra time for your journey.",
                stops("Bank Station", "St Paul's Station", "Holborn Station"),
            ),
        )
        assertEquals(
            setOf("S1", "S2"),
            AlertStops.stretched(
                "Road will be closed to facilitate UKPN works from 13 Oct 07:00 until 31 Oct 18:00. Buses will be diverted in both " +
                    "directions and will miss stops Monument Station and Fenchurch Street.",
                stops("London Bridge Station", "Monument Station", "Fenchurch Street", "Aldgate Station"),
            ),
        )
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "BELMONT ROAD, UB8: Until approximately 23:00 on Tuesday 06 October, ROUTES 427 U7 and N207 towards Southall, Hayes and " +
                    "Holborn are not serving stop 'Uxbridge Station' (O) due to Cadent Gas works.",
                stops("Hillingdon Station", "Uxbridge Station", "North End"),
            ),
        )
        assertEquals(
            setOf("S0", "S1", "S2"),
            AlertStops.stretched(
                "EXAMPLE AVENUE, E1: ROUTE 99 is on diversion towards North End only until 18:00 Friday 16 October. " +
                    "The 'Hail & Ride' section from Alpha Road to Gamma Road / Delta Road are not being served. Please allow extra time for your journey.",
                road,
            ),
        )
        assertEquals(
            setOf("S1", "S2"),
            AlertStops.stretched(
                "WATERLOO ROAD, Southwark: ROUTES 1 68 172 176 188 SL6 BL1 southbound are on diversion in via York Way and Westminster " +
                    "Bridge Road. Buses are missing Waterloo Station /Waterloo Road 'E' to St George's Circus (T). Routes BL1 and SL6 also " +
                    "follows the same diversion but does not miss any stops.",
                stops("County Hall", "Waterloo Station / Waterloo Road", "St George's Circus", "Elephant & Castle"),
            ),
        )
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "EXAMPLE ROAD, E1: ROUTES 98 99 towards North End are on diversion due to water works. Bus stop 'Example Town / Alpha " +
                    "Road' (R) will not be served. Please allow\n\nextra time for your journey.",
                stops("Middle Road", "Example Town / Alpha Road", "North End"),
            ),
        )
        assertEquals(
            setOf("S0", "S1", "S2", "S3"),
            AlertStops.stretched(
                "EXAMPLE ROAD, E1: Route 99 is on diversion in both directions due to gas works. Buses are missing stops from Alpha " +
                    "Road to Gamma Road towards North End and stops from Gamma Road to Delta Road / Example Road towards South End.",
                stops("Alpha Road", "Beta Road", "Gamma Road", "Delta Road / Example Road", "North End"),
            ),
        )
        // An alert naming no stops places none, so it stays on.
        assertEquals(
            emptySet<String>(),
            AlertStops.stretched("EXAMPLE ROAD, E1 ROUTES 98,99,R1 are on diversion due to an emergency servces incident. Please allow extra time for your journey.", road),
        )
        // A stretch one way, and stops one by one the other (made-up stops).
        assertEquals(
            setOf("S0", "S1", "S2", "S3"),
            AlertStops.stretched(
                "EXAMPLE ROAD, E1: Route 99 will be on diversion in both directions due to borough roadworks. Buses will divert via " +
                    "Example Street. Towards North End, the stops from 'Alpha Road' (L) to 'Gamma Road' will not be served. Towards " +
                    "South End, the stops 'Gamma Road' (U) and 'Delta Road' (H) will not be served. Please allow\n\nextra time for your journey",
                stops("Alpha Road", "Beta Road", "Gamma Road", "Delta Road", "North End"),
            ),
        )
        assertEquals(
            setOf("S0", "S1", "S2", "S3"),
            AlertStops.stretched(
                "EXAMPLE PARK ROAD, E1: Routes 98 and 99 are on diversion in both directions. Towards North End, the stops from 'Alpha " +
                    "Road' (J) to 'Gamma Road' (A) will not be served. Towards South End, the stops from 'Gamma Road' (B) to 'Delta " +
                    "Road' (M) will not be served. Please allow extra time for your journey.",
                stops("Alpha Road", "Beta Road", "Gamma Road", "Delta Road", "North End"),
            ),
        )
        // Both poles' letters (made-up stops).
        assertEquals(
            setOf("S0", "S1", "S2"),
            AlertStops.stretched(
                "EXAMPLE HILL, E1: Route 99 is on diversion in both directions until 23:59 on Thursday 01 October due to emergency gas " +
                    "works. Buses are missing stops between Alpha Road (BN and BP) and Gamma Road / Delta Road (M and P).",
                road,
            ),
        )
        // One stretch told once for each direction, in one run-on list (made-up stops).
        assertEquals(
            setOf("S1", "S2", "S4", "S5"),
            AlertStops.stretched(
                "EXAMPLE LANE, E1: Routes 98 99 are on diversion in both directions. Buses are diverted via Example Street, missing the " +
                    "stops from Beta Road 'CA' to Gamma Road 'T' northbound, and from Delta Road 'U' to Epsilon Road / Other Road 'CB' southbound.",
                stops("Alpha Road", "Beta Road", "Gamma Road", "Middle Road", "Delta Road", "Epsilon Road / Other Road", "North End"),
            ),
        )
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "EXAMPLE ROAD, E1: Due to water works Example Road will be closed until 22:00 Monday 30 November. ROUTES 98 W1 and 675 " +
                    "will be diverted and will miss stop 'Example Walk'.",
                stops("Alpha Road", "Example Walk", "North End"),
            ),
        )
        assertEquals(
            setOf("S1"),
            AlertStops.stretched(
                "NEW BRIDGE STREET, EC4: ROUTES 40, 63, N63 and N89 towards Clerkenwell / Kings Cross / Charing Cross are on diversion via " +
                    "Queen Victoria Street and Cannon Street / Ludgate Hill, due to emergency gas works until Wednesday 07 October 2026 at " +
                    "20:00. Buses are missing the stop 'Blackfriars Station / North Entrance' (J).",
                stops("Ludgate Circus", "Blackfriars Station / North Entrance", "Blackfriars Bridge"),
            ),
        )
        // One stretch for each direction, and a stop served on the way (made-up stops).
        assertEquals(
            setOf("S1", "S2", "S4", "S5"),
            AlertStops.stretched(
                "Towards North End, buses are diverted via Example Street. Stops between 'Beta Road' (CN) and 'Gamma Road' (EL) will " +
                    "not be served. Buses towards South End are diverted via Other Lane (serving Bus Stop J - Example Market). Stops " +
                    "between 'Delta Road' (H) and 'Epsilon Road' (CL) will not be served. Please allow extra time for your journeys.",
                stops("Alpha Road", "Beta Road", "Gamma Road", "Middle Road", "Delta Road", "Epsilon Road", "North End"),
            ),
        )
        assertEquals(
            setOf("S1", "S2"),
            AlertStops.stretched(
                "Route 99 is on diversion via Example Street (serving Bus Stop J - Example Market) due to maintenance works. " +
                    "Buses will not serve stops 'Beta Lane Station' (F) and 'Gamma Road/Delta Road' (W).",
                listed,
            ),
        )
        // Other wording, affected or not, places nothing.
        for (text in listOf(
            "Good service between Bank Station and Moorgate Station. Route diverted via Example Road.",
            "Buses run between Bank Station and Moorgate Station.",
            "Buses diverted between Bank Station and Moorgate Station.",
            "Buses are diverted from Bank Station to Moorgate Station.",
            "Route diverted via Example Road; buses will not be diverted between Bank Station and Moorgate Station.",
            "No buses are diverted between Bank Station and Moorgate Station.",
            "Buses are diverted except between Bank Station and Moorgate Station.",
            "Buses are not serving stops except between Bank Station and Moorgate Station.",
            "The stops from Bank Station to Moorgate Station will be served.",
        )) {
            assertEquals(text, emptySet<String>(), AlertStops.stretched(text, line))
        }
        // Stops missed in other words too may be the ride's (Codex, PR #455).
        val four = stops("Bank Station", "Moorgate Station", "Victoria Station", "Waterloo Station")
        assertEquals(emptySet<String>(), AlertStops.stretched("Buses are not serving stops between 'Bank Station' and 'Moorgate Station' and are not serving Victoria Station.", four))
        assertEquals(emptySet<String>(), AlertStops.stretched("Buses are not serving stops between 'Bank Station' and 'Moorgate Station'. Buses will also miss Victoria Station.", four))
        for (more in listOf("Buses are not expected to serve Victoria Station.", "Buses cannot serve Victoria Station.", "Buses are unable to call at Victoria Station.", "Buses will skip Victoria Station.", "Buses never stop at Victoria Station.")) {
            assertEquals(more, emptySet<String>(), AlertStops.stretched("Buses are not serving stops between 'Bank Station' and 'Moorgate Station'. $more", four))
        }
        // A stretch it can't confirm leaves where the alert applies unknown (Codex, PR #455).
        assertEquals(emptySet<String>(), AlertStops.stretched("Buses are not serving stops between Bank Station and Example Street and between Moorgate Station and Old Street Station.", stops("Bank Station", "Example Street", "Moorgate Station", "Old Street Station")))
        // Only a stretch places it: a stop merely named is none.
        assertEquals(emptySet<String>(), AlertStops.stretched("Roadworks near Moorgate Station.", stops("Moorgate Station", "Old Street Station")))
        // "Earl's Court" doesn't mark a stop called "S Court".
        assertEquals(emptySet<String>(), mentioned("Trains stop at Earl's Court.", "S Court"))
    }

    @Test
    fun `matches a station listed under its full TfL name`() {
        assertEquals(
            setOf("Charing Cross Underground Station"),
            mentioned("No service between Charing Cross and Kennington.", "Charing Cross Underground Station", "Embankment Underground Station"),
        )
    }

    @Test
    fun `ignores case in the name but not a lowercase word`() {
        assertEquals(setOf("Bank"), mentioned("Trains are not stopping at BANK.", "Bank"))
        assertEquals(emptySet<String>(), mentioned("Flooding by the river bank.", "Bank"))
    }

    @Test
    fun `a line, branch or holiday is not the station of the same name`() {
        assertEquals(
            emptySet<String>(),
            mentioned(
                "Minor delays on the Victoria line and the Bank branch. Hammersmith & City: no service on Bank Holiday.",
                "Victoria", "Bank", "Hammersmith",
            ),
        )
    }

    @Test
    fun `a name leading a list of lines is a line`() {
        assertEquals(
            emptySet<String>(),
            mentioned(
                "Victoria and Piccadilly lines: minor delays. Bank, Charing Cross and Kennington branches: good service.",
                "Victoria", "Bank", "Charing Cross",
            ),
        )
    }

    @Test
    fun `a station followed by other stations is still a station`() {
        assertEquals(
            setOf("Victoria", "Green Park"),
            mentioned("No service between Victoria and Green Park stations.", "Victoria", "Green Park"),
        )
    }

    @Test
    fun `the station is still marked where the alert also names its line`() {
        assertEquals(setOf("Victoria"), mentioned("Victoria line: no service, trains terminate at Victoria.", "Victoria"))
    }

    @Test
    fun `a name inside a longer word is not a match`() {
        assertEquals(emptySet<String>(), mentioned("Bankside works continue.", "Bank"))
    }

    @Test
    fun `spelling variants within the text still match`() {
        assertEquals(
            setOf("Elephant & Castle", "St. Paul's", "King's Cross St. Pancras"),
            mentioned(
                "Closed between Elephant and Castle and St Paul’s.\\nKing's Cross St Pancras is open.",
                "Elephant & Castle", "St. Paul's", "King's Cross St. Pancras",
            ),
        )
    }

    @Test
    fun `a bus stop listed with its cross street matches on its own name`() {
        assertEquals(
            setOf("Camomile Street / Bishopsgate", "Fenchurch Street"),
            mentioned(
                "Buses will be diverted and will miss stops Camomile Street and Fenchurch Street.",
                "Liverpool Street / Bishopsgate", "Camomile Street / Bishopsgate", "Fenchurch Street",
            ),
        )
    }

    @Test
    fun `the cross street alone is not a match`() {
        assertEquals(emptySet<String>(), mentioned("Bishopsgate is closed to traffic.", "Camomile Street / Bishopsgate"))
    }

    @Test
    fun `destinations in a towards list are not where the disruption is`() {
        assertEquals(
            setOf("Camomile Street"),
            mentioned(
                "Buses towards Lewisham and London Bridge will miss stops Camomile Street.",
                "Camomile Street", "London Bridge",
            ),
        )
    }

    @Test
    fun `a name after a towards list's sentence ends still counts`() {
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses run towards Lewisham. London Bridge is closed.", "London Bridge"),
        )
    }

    @Test
    fun `a line break ends a towards list as a full stop does`() {
        // Real and escaped line breaks alike: TfL's prose arrives with either.
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses towards London Bridge\nLondon Bridge Station is closed", "London Bridge"),
        )
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses towards London Bridge\\nLondon Bridge Station is closed", "London Bridge"),
        )
    }

    @Test
    fun `a line break ends a list of lines too`() {
        assertEquals(
            setOf("Victoria"),
            mentioned("Trains terminate at Victoria\nCircle and District lines: good service", "Victoria"),
        )
    }

    @Test
    fun `an all-caps alert still matches, full stops and all`() {
        assertEquals(
            setOf("St. Paul's"),
            mentioned("ST. PAUL'S STATION CLOSED", "St. Paul's", "Bank"),
        )
    }

    @Test
    fun `an all-caps alert marks every station it names, destinations and lines' namesakes too`() {
        // Capitals are the signal that ends a "towards" list or a list of lines; all-caps text has
        // none, so the matcher marks generously rather than miss the stop the buses skip.
        assertEquals(
            setOf("Lewisham", "Camomile Street / Bishopsgate"),
            mentioned("BUSES TOWARDS LEWISHAM WILL MISS STOPS CAMOMILE STREET", "Lewisham", "Camomile Street / Bishopsgate", "Aldgate"),
        )
        assertEquals(setOf("Victoria"), mentioned("VICTORIA AND PICCADILLY LINES: MINOR DELAYS", "Victoria", "Green Park"))
        // A single line or branch is still named by the word after it, whatever the case.
        assertEquals(emptySet<String>(), mentioned("VICTORIA LINE: MINOR DELAYS", "Victoria"))
    }

    @Test
    fun `matches a station without the place TfL qualifies it with, but not a longer name`() {
        assertEquals(
            setOf("Stratford (London)"),
            mentioned("No service at Stratford.", "Stratford (London)", "Stratford International"),
        )
        assertEquals(
            setOf("Stratford International"),
            mentioned("No service at Stratford International.", "Stratford (London)", "Stratford International"),
        )
    }

    @Test
    fun `matches a station whether or not the alert writes its apostrophe`() {
        assertEquals(setOf("Earl's Court"), mentioned("Minor delays between Earls Court and Wimbledon.", "Earl's Court"))
        assertEquals(setOf("Earls Court"), mentioned("No service at Earl’s Court.", "Earls Court"))
        // A possessive after the name still ends it.
        assertEquals(setOf("Victoria"), mentioned("Victoria's platforms are closed.", "Victoria"))
        assertEquals(setOf("Earl's Court"), mentioned("Earl's Court's lifts are unavailable.", "Earl's Court"))
    }

    @Test
    fun `destinations joined by or are still destinations`() {
        assertEquals(
            setOf("Camomile Street"),
            mentioned("Buses towards Lewisham or London Bridge will miss stops Camomile Street", "Lewisham", "London Bridge", "Camomile Street"),
        )
        // A destination's own lowercase word doesn't end the list.
        assertEquals(
            setOf("Camomile Street"),
            mentioned(
                "Buses towards Prince of Wales Road or South End Green will miss stops Camomile Street",
                "Prince of Wales Road", "South End Green", "Camomile Street",
            ),
        )
        // A question or exclamation mark ends the sentence, and the list with it.
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses towards Lewisham! London Bridge station is closed", "Lewisham", "London Bridge"),
        )
    }

    @Test
    fun `an all-caps alert with an ampersand is still all caps`() {
        assertEquals(
            setOf("Elephant & Castle", "Camomile Street"),
            mentioned("BUSES TOWARDS ELEPHANT & CASTLE WILL MISS STOPS CAMOMILE STREET", "Elephant & Castle", "Camomile Street"),
        )
        // All caps is judged per clause, so lowercase prose after it doesn't switch it off.
        assertEquals(
            setOf("Lewisham", "Camomile Street"),
            mentioned("BUSES TOWARDS LEWISHAM WILL MISS STOPS CAMOMILE STREET. For more information, check the website.", "Lewisham", "Camomile Street"),
        )
        // An all-caps template quoting stops in their own case is still all caps.
        assertEquals(
            setOf("Lewisham", "Camomile Street"),
            mentioned("BUSES TOWARDS LEWISHAM WILL MISS STOPS Camomile Street", "Lewisham", "Camomile Street"),
        )
        assertEquals(
            setOf("Lewisham", "Camomile Street", "Fenchurch Street"),
            mentioned(
                "BUSES TOWARDS LEWISHAM WILL MISS STOPS Camomile Street AND Fenchurch Street",
                "Lewisham", "Camomile Street", "Fenchurch Street",
            ),
        )
    }

    @Test
    fun `Waterloo and City Thameslink are two stations, but the Waterloo and City line is not one`() {
        assertEquals(
            setOf("Waterloo", "City Thameslink"),
            mentioned("No service between Waterloo and City Thameslink.", "Waterloo", "City Thameslink"),
        )
        assertEquals(emptySet<String>(), mentioned("WATERLOO & CITY LINE: GOOD SERVICE", "Waterloo"))
        assertEquals(emptySet<String>(), mentioned("Waterloo & City: part suspended.", "Waterloo"))
        assertEquals(emptySet<String>(), mentioned("Victoria and Hammersmith & City lines: minor delays", "Victoria", "Hammersmith"))
        assertEquals(emptySet<String>(), mentioned("Use the Victoria or Piccadilly lines instead.", "Victoria"))
    }

    @Test
    fun `a station before another starting City is still a station`() {
        assertEquals(
            setOf("Bank", "City Thameslink"),
            mentioned("No service between Bank and City Thameslink.", "Bank", "City Thameslink", "Hammersmith"),
        )
    }

    @Test
    fun `the Waterloo and City line doesn't mark Waterloo`() {
        assertEquals(emptySet<String>(), mentioned("Waterloo & City line: part suspended.", "Waterloo"))
    }

    @Test
    fun `no text or no stops marks nothing`() {
        assertEquals(emptySet<String>(), mentioned(null, "Bank"))
        assertEquals(emptySet<String>(), mentioned("  ", "Bank"))
        assertEquals(emptySet<String>(), AlertStops.mentioned("Bank", emptyList()))
        assertEquals(emptySet<String>(), AlertStops.affected(null, stops("Bank", "Monument")))
    }

    // The stations [affected] marks on a stop list in route order, by name, in that order.
    private fun affected(text: String?, vararg names: String) =
        AlertStops.affected(text, stops(*names)).map { id -> names[id.removePrefix("S").toInt()] }

    private val northbound = arrayOf("Borough", "London Bridge", "Bank", "Moorgate", "Old Street", "Angel")

    @Test
    fun `a stretch between two named stations marks the stations between them too`() {
        val text = "Diverted between London Bridge and Old Street due to roadworks."
        assertEquals(listOf("London Bridge", "Bank", "Moorgate", "Old Street"), affected(text, *northbound))
        // The names alone are still what the alert names, for beside its chip.
        assertEquals(setOf("London Bridge", "Old Street"), mentioned(text, *northbound))
        // Either way round, and written "X to Y".
        assertEquals(
            listOf("London Bridge", "Bank", "Moorgate", "Old Street"),
            affected("No service between Old Street and London Bridge.", *northbound),
        )
        assertEquals(listOf("Bank", "Moorgate", "Old Street"), affected("Diversion Old Street to Bank", *northbound))
    }

    @Test
    fun `every stretch the alert gives is marked, whatever the sentence says of it`() {
        val stretch = listOf("London Bridge", "Bank", "Moorgate", "Old Street")
        // Not told apart from prose (maintainer, 2026-10-01): a reason, a negation, a status after a
        // colon or in the same sentence, all mark the stretch alike (Codex on #437).
        assertEquals(stretch, affected("No service between London Bridge and Old Street due to an operational issue.", *northbound))
        assertEquals(stretch, affected("Trains are not running between London Bridge and Old Street.", *northbound))
        assertEquals(stretch, affected("Trains are not expected to run between London Bridge and Old Street.", *northbound))
        assertEquals(stretch, affected("London Bridge to Old Street: good service", *northbound))
        assertEquals(
            stretch,
            affected("No service between London Bridge and Old Street, good service on the rest of the line.", *northbound),
        )
        // Both stretches of a sentence, the one running too.
        assertEquals(
            northbound.toList(),
            affected("Good service between Borough and Bank but no service between Bank and Angel.", *northbound),
        )
        assertEquals(
            listOf("Borough", "London Bridge", "Bank", "Moorgate", "Old Street", "Angel"),
            affected("No service between Borough and Moorgate. Trains are running between Moorgate and Angel only.", *northbound),
        )
    }

    @Test
    fun `named stations with no stretch between them, or an end off the page, stay as named`() {
        assertEquals(
            listOf("London Bridge", "Old Street"),
            affected("Lifts out of order at London Bridge and Old Street.", *northbound),
        )
        // Euston isn't on this train's list, so what lies between it and Bank isn't known.
        assertEquals(listOf("Bank"), affected("No service between Euston and Bank.", *northbound))
    }

    @Test
    fun `a stretch names its ends by the station after a stop's slash and with its own cross street`() {
        // Route 43 lists "Finsbury Square / Moorgate" and "Monument"; TfL's alert calls them "Moorgate
        // Station (L)" and "King William Street / Monument Station (G)" (maintainer, 2026-10-01).
        val alert = "KING WILLIAM STREET, City of London: Routes 21 43 and 141 are on diversion southbound only until " +
            "19:00 on 1 February 2027 due to major roadworks. Buses are diverted via South Place, Eldon Street, Blomfield " +
            "Street, London Wall, Bishopsgate and Gracechurch Street, missing stops from Moorgate Station (L) to King " +
            "William Street / Monument Station (G)."
        val listed = listOf("Epworth Street", "Finsbury Square / Moorgate", "All Hallows Church", "Camomile Street", "Fenchurch Street", "Monument", "London Bridge")
        for (route in listOf(listed, listed.map { it.replace("Moorgate", "Moorgate Station").replace("Monument", "Monument Station") })) {
            assertEquals(setOf("S1", "S2", "S3", "S4", "S5"), AlertStops.stretched(alert, stops(*route.toTypedArray())))
        }
        // A cross street's name with no letter after it is the road, not the stop.
        assertEquals(
            emptySet<String>(),
            AlertStops.mentioned("Buses are diverted via Moorgate.", stops("Finsbury Square / Moorgate", "Monument")),
        )
    }

    @Test
    fun `a clause naming more stops than it reads places nothing`() {
        // C and D are listed with A and B as not served, though no verb repeats for them (Codex, PR #455).
        val route = stops("Alpha Road", "Beta Road", "Gamma Road", "Delta Road", "Echo Road")
        assertEquals(
            emptySet<String>(),
            AlertStops.stretched("Buses are not serving stops between Alpha Road and Beta Road and stops Gamma Road and Delta Road.", route),
        )
        // A stop named in another sentence is an aside, and the stretch still stands.
        assertEquals(
            setOf("S0", "S1"),
            AlertStops.stretched("Buses are not serving stops between Alpha Road and Beta Road. Allow extra time at Delta Road.", route),
        )
    }

    @Test
    fun `the page marks every stop a trip reads as not served`() {
        // A curtailment's left-out stops, which the alert doesn't name (maintainer, 2026-10-01).
        val route = stops("South End", "Alpha Road", "Beta Road", "Gamma Road", "North End")
        assertEquals(setOf("S1", "S2", "S3", "S4"), AlertStops.affected("Route 99 is curtailed to Alpha Road 'J'.", route))
        // TfL's bus stretch, its ends named as the alert writes them.
        val line = stops("Old Street", "Finsbury Square / Moorgate", "All Hallows Church", "Monument", "London Bridge")
        assertEquals(
            setOf("S1", "S2", "S3"),
            AlertStops.affected("Buses are missing stops from Moorgate Station (L) to King William Street / Monument Station (G).", line),
        )
    }

    @Test
    fun `marked stops are named as runs by their ends`() {
        val route = stops("Alpha Road", "Beta Road", "Gamma Road", "Delta Road", "Echo Road", "Beta Road")
        val name: (RouteStop) -> String = { it.name }
        assertEquals(listOf("Alpha Road to Gamma Road", "Echo Road"), AlertStops.runs(setOf("S0", "S1", "S2", "S4"), route, name))
        // A stop alone, or a run whose ends share a name, is that name once; a label isn't repeated.
        assertEquals(listOf("Beta Road"), AlertStops.runs(setOf("S1", "S5"), route, name))
        assertEquals(emptyList<String>(), AlertStops.runs(emptySet(), route, name))
    }
}
