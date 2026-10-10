package app.stopdash.domain

// When the near-me "Automatically update widget?" card shows (SPEC *Settings*): pure rules over what the
// app has read, so they're tested here without Android. Which values they're given, and when, is the
// app's wiring.

/**
 * Whether the near-me "Automatically update widget?" card is definitely gone: answered, now ([closedNow])
 * or stored ([dismissed]), automatic updates turned on, or no widget placed. A value not read yet
 * ([widgetPlaced], [autoUpdate] or [dismissed] null, as while a recreated activity re-reads them) says
 * nothing either way, so it never ends a card already shown.
 */
fun widgetUpdateCardGone(widgetPlaced: Boolean?, autoUpdate: Boolean?, dismissed: Boolean?, closedNow: Boolean): Boolean =
    closedNow || dismissed == true || autoUpdate == true || widgetPlaced == false

/**
 * Whether the near-me "Automatically update widget?" card shows. Once [held] (it has been on screen) it
 * stays until it's definitely gone ([widgetUpdateCardGone]), so a re-read in progress, as after a
 * rotation, neither takes it away nor lets another card take its place. Before that it shows only when
 * everything is known: a widget placed, automatic updates off, not dismissed, and the questions it waits
 * behind settled ([questionsSettled]), so it never flashes at a rider it isn't for or lands where
 * another question then replaces it.
 */
fun widgetUpdateCardShown(
    widgetPlaced: Boolean?,
    autoUpdate: Boolean?,
    dismissed: Boolean?,
    closedNow: Boolean,
    questionsSettled: Boolean,
    held: Boolean = false,
): Boolean =
    if (held) {
        !widgetUpdateCardGone(widgetPlaced, autoUpdate, dismissed, closedNow)
    } else {
        questionsSettled && widgetPlaced == true && autoUpdate == false && dismissed == false && !closedNow
    }

/**
 * Whether the questions the widget card waits behind are settled, so neither can land in its place:
 * the telemetry choice read ([telemetryRead]), and the watch offer decided, by its stored dismissal
 * once read ([watchCardDismissed], null until then) or, where not dismissed, by the watches once
 * checked ([watchesChecked]).
 */
fun widgetQuestionsSettled(telemetryRead: Boolean, watchCardDismissed: Boolean?, watchesChecked: Boolean): Boolean =
    telemetryRead && watchCardDismissed != null && (watchCardDismissed || watchesChecked)
