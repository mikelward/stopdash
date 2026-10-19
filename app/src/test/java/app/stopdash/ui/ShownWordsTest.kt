package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.IncidentDetail
import app.stopdash.domain.LineStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberShownWords]: National Rail's words until they grow too old, then TfL's link, read afresh for a
 * new status and for words already too old when first composed (a map kept while the page was away).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ShownWordsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val link = "See https://www.nationalrail.co.uk/service-disruptions/example/"
    private val words = "Disruption at Herne Hill"

    // The wall clock the words are judged by, moved with the test's own clock.
    private val start = Instant.parse("2026-10-19T18:00:00Z")
    private var wall = start
    private val now = { wall }

    private fun status(lastsMillis: Long) = LineStatus(
        "thameslink", 9, "Service Alert", fullText = link,
        incidentWords = mapOf(link to IncidentDetail(link, words, until = wall.plusMillis(lastsMillis))),
    )

    @Test
    fun national_rail_words_until_their_limit_then_the_link_and_fresh_words_for_a_new_status() {
        composeRule.mainClock.autoAdvance = false
        val shown = mutableStateOf(status(1_000))
        var read: String? = null
        composeRule.setContent {
            val current by shown
            read = rememberShownWords(current, now)
        }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(words, read)
        wall = start.plusMillis(2_000)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        assertEquals(link, read)
        // A refresh brings fresh words.
        shown.value = status(600_000)
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()
        assertEquals(words, read)
    }

    @Test
    fun words_already_too_old_read_as_the_link() {
        val lapsed = status(-1_000)
        var read: String? = "unread"
        composeRule.setContent { read = rememberShownWords(lapsed, now) }
        composeRule.waitForIdle()
        assertEquals(link, read)
    }

    @Test
    fun words_that_grew_too_old_while_the_screen_was_off_read_as_the_link_on_resume() {
        composeRule.mainClock.autoAdvance = false
        val shown = status(60_000)
        var read: String? = null
        composeRule.setContent { read = rememberShownWords(shown, now) }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(words, read)
        // Deep sleep: the words' age moves on, the page's own timer doesn't.
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        wall = start.plusMillis(120_000)
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()
        assertEquals(link, read)
    }
}
