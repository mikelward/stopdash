package app.stopdash.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapsLinkFollowerTest {
    private val asked = mutableListOf<String>()

    private fun follower(reply: (String) -> Pair<HttpStatusCode, String?>): MapsLinkFollower {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            asked += url
            val (status, location) = reply(url)
            if (location == null) respondError(status) else respond("", status, headersOf(HttpHeaders.Location, location))
        }
        return MapsLinkFollower(
            HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout)
            },
        )
    }

    @Test
    fun `a short link is read for where it redirects, and that page isn't fetched`() = runBlocking {
        val full = "https://www.google.com/maps/place/Somewhere/data=!3d51.5!4d-0.12"
        val follower = follower { HttpStatusCode.Found to full }
        assertEquals(full, follower.follow("https://maps.app.goo.gl/X"))
        assertEquals(listOf("https://maps.app.goo.gl/X"), asked)
    }

    @Test
    fun `a short link leading to another is not followed further, so Google is asked once`() = runBlocking {
        val follower = follower { HttpStatusCode.MovedPermanently to "https://maps.app.goo.gl/Y" }
        assertNull(follower.follow("https://goo.gl/maps/X"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `a full link is returned without asking anyone`() = runBlocking {
        val follower = follower { error("asked $it") }
        assertEquals("https://maps.google.com/?q=51.5,-0.12", follower.follow("https://maps.google.com/?q=51.5,-0.12"))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a redirect off Google Maps, or no redirect, is not followed`() = runBlocking {
        assertNull(follower { HttpStatusCode.Found to "https://evil.example/maps?q=51.5,-0.12" }.follow("https://maps.app.goo.gl/X"))
        assertNull(follower { HttpStatusCode.NotFound to null }.follow("https://maps.app.goo.gl/X"))
        assertNull(follower { HttpStatusCode.OK to null }.follow("https://maps.app.goo.gl/X"))
    }

    @Test
    fun `a network failure reads as not followed`() = runBlocking {
        val follower = MapsLinkFollower(HttpClient(MockEngine { throw IOException("offline") }) { expectSuccess = false })
        assertNull(follower.follow("https://maps.app.goo.gl/X"))
    }
}
