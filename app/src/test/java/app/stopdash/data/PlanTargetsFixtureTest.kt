package app.stopdash.data

import app.stopdash.domain.PlanTargets
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The whole target-selection path against TfL's recorded answer for King's Cross St. Pancras. */
class PlanTargetsFixtureTest {
    private val fixture = checkNotNull(javaClass.getResource("/fixtures/stoppoint_hubkgx.json")).readText()

    private val client = KtorTflClient(
        httpClient = HttpClient(MockEngine { respond(ByteReadChannel(fixture), headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        baseUrl = "https://tfl.example", decodeDispatcher = serialDecode,
    )

    @Test
    fun `king's cross is planned to at each station code and one bus stop`() = runTest {
        val members = client.stationStops("HUBKGX")
        val targets = PlanTargets.of(members.map { PlanTargets.Member(it.id, it.lines) })
        // Every station code (Underground, King's Cross, and St Pancras's three), then the first of
        // its four bus stops for them all: six Planner requests.
        assertEquals(
            listOf("910GKNGX", "910GSTPADOM", "910GSTPX", "910GSTPXBOX", "940GZZLUKSX", "490001172X"),
            targets,
        )
    }
}
