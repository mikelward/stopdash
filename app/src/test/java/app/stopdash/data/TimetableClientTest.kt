package app.stopdash.data

import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line's timetable at a stop, against TfL's recorded (trimmed) `/Line/{id}/Timetable/{stop}` at
 * King's Cross: public network data only.
 */
class TimetableClientTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private val bus = fixture("timetable_73_kings_cross.json")
    private val tubePlain = fixture("timetable_victoria_kings_cross_plain.json")
    private val tubeOutbound = fixture("timetable_victoria_kings_cross_outbound.json")

    private fun client(answer: (HttpRequestData) -> Pair<String, HttpStatusCode>): KtorTflClient {
        val engine = MockEngine { request ->
            val (body, status) = answer(request)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, appKey = { "EXAMPLE" })
    }

    @Test
    fun `reads a bus route's day types and departures, asked plainly`() = runTest {
        val asked = mutableListOf<HttpRequestData>()
        val timetable = client { asked += it; bus to HttpStatusCode.OK }.timetable("73", "490000129E")
        assertEquals(1, asked.size)
        assertEquals("/Line/73/Timetable/490000129E", asked.single().url.encodedPath)
        assertEquals(null, asked.single().url.parameters["direction"])
        assertEquals(4, timetable.schedules.size)
        val weekday = timetable.schedules.single { DayOfWeek.MONDAY in it.days }
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), weekday.days)
        assertTrue(weekday.minutes.isNotEmpty())
        // Its last buses run past midnight, on the same service day.
        assertTrue(weekday.minutes.max() >= 24 * 60)
    }

    @Test
    fun `asks a tube line by direction when the plain answer has no routes`() = runTest {
        val asked = mutableListOf<String?>()
        val timetable = client { request ->
            val direction = request.url.parameters["direction"]
            asked += direction
            (if (direction == null) tubePlain else tubeOutbound) to HttpStatusCode.OK
        }.timetable("victoria", "940GZZLUKSX")
        assertEquals(listOf(null, "inbound", "outbound"), asked)
        // Both directions' schedules are kept (the stand-in answers outbound for each).
        assertEquals(8, timetable.schedules.size)
        assertTrue(timetable.schedules.any { DayOfWeek.SATURDAY in it.days && it.minutes.isNotEmpty() })
    }

    @Test
    fun `a direction with a schedule it couldn't read leaves the combined timetable unreadable`() = runTest {
        val schoolDays = """{"timetable":{"routes":[{"schedules":[{"name":"School days","knownJourneys":[{"hour":"8","minute":"10"}]}]}]}}"""
        val timetable = client { request ->
            when (request.url.parameters["direction"]) {
                null -> tubePlain
                "inbound" -> schoolDays
                else -> tubeOutbound
            } to HttpStatusCode.OK
        }.timetable("victoria", "940GZZLUKSX")
        assertEquals(false, timetable.readable)
        // The other direction's schedules are still there to find a train due.
        assertTrue(timetable.schedules.isNotEmpty())
    }

    @Test
    fun `a Saturday evening train is due, and a Saturday 4am one isn't on the Victoria line`() = runTest {
        val timetable = client { request ->
            (if (request.url.parameters["direction"] == null) tubePlain else tubeOutbound) to HttpStatusCode.OK
        }.timetable("victoria", "940GZZLUKSX")
        // 2026-10-03 is a Saturday; 19:00 London (BST) is 18:00Z.
        assertEquals(true, timetable.departsWithin(Instant.parse("2026-10-03T18:00:00Z"), EmptyTimes.WINDOW))
        // Saturday 04:00 London: Friday's Night Tube still runs on the Victoria line, so trains are due.
        assertEquals(true, timetable.departsWithin(Instant.parse("2026-10-03T03:00:00Z"), EmptyTimes.WINDOW))
        // Tuesday 03:00 London: no Night Tube on a weeknight, and the first train is after 05:00.
        assertEquals(false, timetable.departsWithin(Instant.parse("2026-10-06T02:00:00Z"), EmptyTimes.WINDOW))
        // Good Friday 04:00 (2027-03-26, GMT): TfL runs its Saturday timetable then, so the Friday
        // one's "none before 05:00" is no answer.
        assertNotEquals(false, timetable.departsWithin(Instant.parse("2027-03-26T04:00:00Z"), EmptyTimes.WINDOW))
    }

    @Test
    fun `a failed request is a TflException`() = runTest {
        val failure = runCatching { client { "" to HttpStatusCode.InternalServerError }.timetable("73", "490000129E") }
        assertTrue(failure.exceptionOrNull() is TflException)
    }

    @Test
    fun `a schedule with a journey whose time can't be read is kept, marked incomplete`() {
        val schedule = TflScheduleDto(
            "Monday - Friday",
            listOf(TflKnownJourneyDto("12", "00"), TflKnownJourneyDto("", "30")),
        )
        val timetable = TflTimetableResponseDto(TflTimetableDto(listOf(TflTimetableRouteDto(listOf(schedule))))).toStopTimetable()
        assertEquals(listOf(12 * 60), timetable.schedules.single().minutes)
        assertEquals(false, timetable.schedules.single().complete)
    }

    @Test
    fun `a schedule sent without its journey list is incomplete, an empty one isn't`() {
        fun read(schedule: TflScheduleDto) =
            TflTimetableResponseDto(TflTimetableDto(listOf(TflTimetableRouteDto(listOf(schedule))))).toStopTimetable().schedules.single()
        assertEquals(false, read(TflScheduleDto("Sunday")).complete)
        // No journeys at all is a day it doesn't run.
        assertEquals(true, read(TflScheduleDto("Sunday", emptyList())).complete)
    }

    @Test
    fun `a schedule whose days can't be read leaves the timetable unreadable`() {
        fun read(vararg schedules: TflScheduleDto) =
            TflTimetableResponseDto(TflTimetableDto(listOf(TflTimetableRouteDto(schedules.toList())))).toStopTimetable()
        val weekdays = TflScheduleDto("Monday - Friday", listOf(TflKnownJourneyDto("12", "00")))
        assertEquals(true, read(weekdays).readable)
        val withSchoolDays = read(weekdays, TflScheduleDto("School days", listOf(TflKnownJourneyDto("08", "10"))))
        assertEquals(false, withSchoolDays.readable)
        assertEquals(1, withSchoolDays.schedules.size)
    }
}
