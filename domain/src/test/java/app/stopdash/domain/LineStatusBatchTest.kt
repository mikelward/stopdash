package app.stopdash.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineStatusBatchTest {
    // The lines a busy central hub serves: buses, night buses, tube and National Rail. Joined, they
    // run to 286 characters, which TfL refused with HTTP 400 as one segment.
    private val hubLines = listOf(
        "214", "46", "63", "205", "30", "73", "390", "91", "circle", "hammersmith-city", "metropolitan",
        "northern", "piccadilly", "victoria", "london-north-eastern-railway", "great-northern",
        "hull-trains", "grand-central", "thameslink", "southeastern", "eurostar", "east-midlands-railway",
        "avanti-west-coast", "west-midlands-trains", "n63", "n205", "n73", "n91", "lumo", "lioness",
    )

    @Test
    fun `a hub's lines are split so every segment fits TfL's limit`() {
        assertTrue(hubLines.joinToString(",").length > LineStatusBatch.MAX_SEGMENT_LENGTH)

        val chunks = LineStatusBatch.chunks(hubLines)

        assertEquals(2, chunks.size)
        chunks.forEach { assertTrue(it.joinToString(",").length <= LineStatusBatch.MAX_SEGMENT_LENGTH) }
        // Nothing lost or reordered.
        assertEquals(hubLines, chunks.flatten())
    }

    @Test
    fun `lines that fit stay in one request`() {
        assertEquals(listOf(listOf("victoria", "northern")), LineStatusBatch.chunks(listOf("victoria", "northern")))
    }

    @Test
    fun `a segment of exactly the limit is kept whole`() {
        // 23 ten-character ids and their 22 commas are 252; a comma and a seven-character id make 260.
        val ids = (1..23).map { "line%05d-".format(it) } + "abcdefg"
        assertEquals(LineStatusBatch.MAX_SEGMENT_LENGTH, ids.joinToString(",").length)
        assertEquals(listOf(ids), LineStatusBatch.chunks(ids))
        assertEquals(2, LineStatusBatch.chunks(ids + "x").size)
    }

    @Test
    fun `no lines make no chunks, and repeats are asked once`() {
        assertTrue(LineStatusBatch.chunks(emptyList()).isEmpty())
        assertEquals(listOf(listOf("victoria")), LineStatusBatch.chunks(listOf("victoria", "victoria")))
    }
}

class LineStatusBatchRequestTest {
    // Twenty-seven ten-character ids: 23 fit one request, the other 4 a second.
    private val ids = (1..27).map { "line%05d-".format(it) }

    @Test
    fun `each group is sent once and answered on its own`() = runTest {
        val sent = mutableListOf<List<String>>()
        val results = LineStatusBatch.request(ids) { chunk -> sent += chunk; chunk.size }

        assertEquals(2, results.requests)
        assertEquals(ids, sent.flatten())
        assertEquals(listOf(23, 4), results.answers.map { it.value })
        assertEquals(ids.toSet(), results.answeredIds)
        assertTrue(results.failed.isEmpty())
    }

    @Test
    fun `a later group failing keeps what the earlier one returned`() = runTest {
        val results = LineStatusBatch.request(ids) { chunk ->
            if (chunk.size == 4) throw TflException.RateLimited(null) else chunk.size
        }

        assertEquals(listOf(23), results.answers.map { it.value })
        assertEquals(ids.drop(23), results.failed)
        assertTrue(results.failure is TflException.RateLimited)
        assertTrue(results.anyAnswered)
    }

    @Test
    fun `after a failure the remaining groups aren't sent`() = runTest {
        var calls = 0
        val results = LineStatusBatch.request(ids) { calls++; null as Int? }

        assertEquals(1, calls)
        assertEquals(1, results.requests)
        // A null answer is a failure too, and the unsent group reads failed with it.
        assertEquals(ids, results.failed)
        assertEquals(false, results.anyAnswered)
    }

    @Test
    fun `a group TfL knows none of is an answer, and an empty one counts`() = runTest {
        val results = LineStatusBatch.request(ids) { chunk ->
            if (chunk.size == 23) throw TflException.NotFound(null) else emptyList<String>()
        }

        assertEquals(ids.take(23), results.unknown)
        assertEquals(listOf(emptyList<String>()), results.answers.map { it.value })
        assertEquals(ids.toSet(), results.answeredIds)
        assertTrue(results.anyAnswered)
        assertTrue(results.failed.isEmpty())
    }
}
