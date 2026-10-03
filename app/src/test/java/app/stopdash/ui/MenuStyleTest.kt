package app.stopdash.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every dropdown is a [StopDashMenu], styled as the overflow menu is (maintainer, 2026-10-03): a bare
 * Material `DropdownMenu` anywhere else would open square-cornered, its items a size smaller.
 */
class MenuStyleTest {
    @Test
    fun every_dropdown_is_a_stopdash_menu() {
        val bare = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AppMenu.kt" }
            .filter { file -> Regex("""\bDropdownMenu\(""").containsMatchIn(file.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), bare)
    }
}
