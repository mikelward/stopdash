package app.stopdash.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rules the compiler can't hold for the app's sources: read as text from `src/main`, so a new call
 * site that breaks one fails here, naming its file.
 */
class WidgetSourceRulesTest {
    private val sources: List<File> = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    // Glance's update only recomposes a session it kept open, which draws the old models; only
    // redrawWidgets makes an open session draw again (maintainer bug report, 2026-10-05).
    @Test
    fun `every widget redraw goes through redrawWidgets`() {
        val direct = sources.filter { file ->
            file.readLines().any { line -> "StopDashWidget().update" in line && !line.trimStart().startsWith("//") }
        }.map { it.name }
        assertEquals(listOf("StopDashWidget.kt"), direct)
    }

}
