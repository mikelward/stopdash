package app.stopdash.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the near-me "Automatically update widget?" card shows, holds its place, and goes. */
class WidgetUpdateCardRulesTest {
    @Test
    fun `the card shows only with a widget placed, updates off and not dismissed`() {
        assertTrue(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = false, closedNow = false, questionsSettled = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = false, autoUpdate = false, dismissed = false, closedNow = false, questionsSettled = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = true, dismissed = false, closedNow = false, questionsSettled = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = true, closedNow = false, questionsSettled = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = false, closedNow = true, questionsSettled = true))
        // Behind a question not known yet: it waits, rather than being replaced as that lands.
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = false, closedNow = false, questionsSettled = false))
        // Not read yet: no flash.
        assertFalse(widgetUpdateCardShown(widgetPlaced = null, autoUpdate = false, dismissed = false, closedNow = false, questionsSettled = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = null, dismissed = false, closedNow = false, questionsSettled = true))
    }

    @Test
    fun `the questions ahead are settled once the telemetry choice and the watch offer are known`() {
        assertTrue(widgetQuestionsSettled(telemetryRead = true, watchCardDismissed = false, watchesChecked = true))
        // Dismissed for good: the watches needn't be checked.
        assertTrue(widgetQuestionsSettled(telemetryRead = true, watchCardDismissed = true, watchesChecked = false))
        assertFalse(widgetQuestionsSettled(telemetryRead = false, watchCardDismissed = true, watchesChecked = true))
        // The watch card's dismissal not read yet, though the watches are checked.
        assertFalse(widgetQuestionsSettled(telemetryRead = true, watchCardDismissed = null, watchesChecked = true))
        assertFalse(widgetQuestionsSettled(telemetryRead = true, watchCardDismissed = false, watchesChecked = false))
    }

    @Test
    fun `a held card stays through a re-read and goes only once it's definitely gone`() {
        // As after a rotation: everything being read again.
        assertTrue(widgetUpdateCardShown(widgetPlaced = null, autoUpdate = null, dismissed = null, closedNow = false, questionsSettled = false, held = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = null, autoUpdate = null, dismissed = null, closedNow = false, questionsSettled = false, held = false))
        // Definitely gone, held or not.
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = true, dismissed = false, closedNow = false, questionsSettled = true, held = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = false, autoUpdate = false, dismissed = false, closedNow = false, questionsSettled = true, held = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = true, closedNow = false, questionsSettled = true, held = true))
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = false, closedNow = true, questionsSettled = true, held = true))
        assertFalse(widgetUpdateCardGone(widgetPlaced = null, autoUpdate = null, dismissed = null, closedNow = false))
    }

    @Test
    fun `the stored dismissal not read yet shows nothing before the card is held`() {
        assertFalse(widgetUpdateCardShown(widgetPlaced = true, autoUpdate = false, dismissed = null, closedNow = false, questionsSettled = true))
    }
}
