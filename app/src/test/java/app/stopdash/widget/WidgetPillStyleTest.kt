package app.stopdash.widget

import app.stopdash.ui.accentEdgeOn
import app.stopdash.ui.accentInkOn
import app.stopdash.ui.lineFillColor
import app.stopdash.ui.overgroundAccentColor
import app.stopdash.ui.textColorOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's line pills: hollow for a named Overground line, as the app draws it (SPEC *Line pill colors*). */
class WidgetPillStyleTest {
    @Test
    fun `a named Overground line is hollow, its accent nudged for each theme's background`() {
        for (lineId in listOf("lioness", "mildmay", "windrush", "weaver", "suffragette", "liberty")) {
            val style = widgetPillStyle(lineId.replaceFirstChar { it.uppercase() }, lineId, "overground")
            assertTrue(lineId, style is WidgetPillStyle.Hollow)
            style as WidgetPillStyle.Hollow
            val accent = overgroundAccentColor(lineId)!!
            assertEquals(lineId, accentInkOn(accent, WIDGET_SURFACE_DAY), style.day.label)
            assertEquals(lineId, accentEdgeOn(accent, WIDGET_SURFACE_DAY), style.day.border)
            assertEquals(lineId, accentInkOn(accent, WIDGET_SURFACE_NIGHT), style.night.label)
            assertEquals(lineId, accentEdgeOn(accent, WIDGET_SURFACE_NIGHT), style.night.border)
        }
        // The same accent reads differently on a light and a dark background (Windrush: darker on
        // the light one, brighter on the dark), so the two themes don't share one color.
        val windrush = widgetPillStyle("Windrush", "windrush", "overground") as WidgetPillStyle.Hollow
        assertNotEquals(windrush.day.label, windrush.night.label)
    }

    @Test
    fun `a tube line, a mode and a legacy Overground id stay solid, and an unknown line neutral`() {
        val central = widgetPillStyle("Central", "central", "tube")
        assertEquals(WidgetPillStyle.Solid(lineFillColor("central", "tube")!!, textColorOn(lineFillColor("central", "tube")!!)), central)
        val dlr = widgetPillStyle("DLR", "dlr", "dlr")
        assertTrue(dlr is WidgetPillStyle.Solid)
        // Not one of the six: the single mode orange, solid, as the app shows it.
        val legacy = widgetPillStyle("London Overground", "london-overground", "overground")
        assertEquals(WidgetPillStyle.Solid(lineFillColor("london-overground", "overground")!!, textColorOn(lineFillColor("london-overground", "overground")!!)), legacy)
        assertEquals(WidgetPillStyle.Neutral, widgetPillStyle("Unknown", "no-such-line", "no-such-mode"))
    }
}
