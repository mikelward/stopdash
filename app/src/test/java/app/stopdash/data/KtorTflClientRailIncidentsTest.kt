package app.stopdash.data

import app.stopdash.domain.RailIncidentCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorTflClientRailIncidentsTest {
    private val decode = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val link = "https://www.nationalrail.co.uk/service-disruptions/london-euston-20261017/"

    private fun tflStatus(reason: String) =
        """[{"id": "avanti-west-coast", "name": "Avanti West Coast", "lineStatuses": [
            {"statusSeverity": 0, "statusSeverityDescription": "Special Service", "reason": "$reason"}]}]"""

    private val incidents = """[{
        "summary": "Disruption at London Euston expected until 18:00",
        "description": "<p>Lines are blocked.</p><p>See <a href=\"https://www.nationalrail.co.uk/\">National Rail</a></p>",
        "status": "Active",
        "disruptionLinks": [{"uri": "$link", "label": "Incident detail page"}]
    }]"""

    private class Asked {
        var proxy = 0
    }

    private fun client(proxyUrl: String?, tfl: String, proxy: () -> Pair<HttpStatusCode, String>, asked: Asked, cache: RailIncidentCache, warn: (String) -> Unit = {}): KtorTflClient {
        val engine = MockEngine { request ->
            val (status, body) = if (request.url.host == "proxy.example") {
                asked.proxy++
                assertEquals("/v1/incidents", request.url.encodedPath)
                // Nothing about the rider goes to the proxy: no key, no query.
                assertTrue(request.url.parameters.isEmpty())
                proxy()
            } else {
                HttpStatusCode.OK to tfl
            }
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(
            httpClient = http,
            baseUrl = "https://tfl.example",
            appKey = { "EXAMPLE" },
            decodeDispatcher = decode,
            proxyUrl = { proxyUrl },
            railIncidents = cache,
            warn = warn,
        )
    }

    @Test
    fun `a link-only National Rail status shows the incident's words, with the link kept`() = runTest {
        val asked = Asked()
        val status = client("https://proxy.example/", tflStatus(link), { HttpStatusCode.OK to incidents }, asked, RailIncidentCache())
            .lineStatuses(listOf("avanti-west-coast")).single()
        assertEquals(link, status.fullText)
        assertEquals(
            "Disruption at London Euston expected until 18:00\n\nLines are blocked.\nSee National Rail (https://www.nationalrail.co.uk/)\n\n$link",
            status.shownText,
        )
        assertEquals(1, asked.proxy)
    }

    @Test
    fun `an incident flagged cleared, by either field, leaves TfL's link`() = runTest {
        // The Knowledgebase schema's own flag, with no status string beside it (Codex, #943).
        val flagged = incidents.replace("\"status\": \"Active\",", "\"clearedIncident\": true,")
        assertEquals(link, client("https://proxy.example", tflStatus(link), { HttpStatusCode.OK to flagged }, Asked(), RailIncidentCache()).lineStatuses(listOf("avanti-west-coast")).single().shownText)
        val cleared = incidents.replace("\"Active\"", "\"Cleared\"")
        assertEquals(link, client("https://proxy.example", tflStatus(link), { HttpStatusCode.OK to cleared }, Asked(), RailIncidentCache()).lineStatuses(listOf("avanti-west-coast")).single().shownText)
        // Not cleared by the flag: the words show.
        val open = incidents.replace("\"status\": \"Active\",", "\"clearedIncident\": false,")
        assertTrue(client("https://proxy.example", tflStatus(link), { HttpStatusCode.OK to open }, Asked(), RailIncidentCache()).lineStatuses(listOf("avanti-west-coast")).single().shownText!!.startsWith("Disruption at London Euston"))
    }

    @Test
    fun `the proxy isn't asked when no status is only a link`() = runTest {
        val asked = Asked()
        val status = client("https://proxy.example", tflStatus("Minor delays between Euston and Watford Junction."), { HttpStatusCode.OK to incidents }, asked, RailIncidentCache())
            .lineStatuses(listOf("avanti-west-coast")).single()
        assertEquals("Minor delays between Euston and Watford Junction.", status.shownText)
        assertEquals(0, asked.proxy)
    }

    @Test
    fun `with no proxy configured, TfL's link is shown as it is`() = runTest {
        val asked = Asked()
        val status = client(null, tflStatus(link), { HttpStatusCode.OK to incidents }, asked, RailIncidentCache())
            .lineStatuses(listOf("avanti-west-coast")).single()
        assertEquals(link, status.shownText)
        assertEquals(0, asked.proxy)
    }

    @Test
    fun `a proxy that fails leaves TfL's status, and is said, not thrown`() = runTest {
        val asked = Asked()
        val warnings = mutableListOf<String>()
        val engineClient = client("https://proxy.example", tflStatus(link), { HttpStatusCode.ServiceUnavailable to "{}" }, asked, RailIncidentCache(), warn = { warnings += it })
        val status = engineClient.lineStatuses(listOf("avanti-west-coast")).single()
        assertEquals(link, status.shownText)
        assertEquals(1, asked.proxy)
        assertEquals(listOf("rail incidents: 503"), warnings.filter { it.startsWith("rail incidents") })
    }

    @Test
    fun `one proxy answer serves every client sharing the cache`() = runTest {
        val asked = Asked()
        val cache = RailIncidentCache()
        client("https://proxy.example", tflStatus(link), { HttpStatusCode.OK to incidents }, asked, cache).lineStatuses(listOf("avanti-west-coast"))
        val second = client("https://proxy.example", tflStatus(link), { HttpStatusCode.OK to incidents }, asked, cache)
            .lineStatuses(listOf("avanti-west-coast")).single()
        assertTrue(second.shownText!!.startsWith("Disruption at London Euston"))
        assertEquals(1, asked.proxy)
    }
}
