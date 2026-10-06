package app.stopdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopdash.R

/**
 * The app-wide overflow actions a screen's own menu offers besides its own: whether Play has a
 * newer version (the red dot and "Update available"), the bug report, and the licenses About opens.
 * The activity provides them ([LocalAppMenu]) once, so a screen several layers down — a trip — gets
 * the same menu as the list without each layer passing them on.
 */
data class AppMenuActions(
    val updateAvailable: Boolean,
    val onOpenAppListing: () -> Unit,
    val onSendBugReport: () -> Unit,
    val onOpenLicenses: () -> Unit,
    // Opens Settings on the disruptions summary's page, whose Back goes up to Settings itself: what the
    // lines page offers, the row's own settings being what a rider there reaches for. Null offers none.
    val onOpenDisruptionsSettings: (() -> Unit)? = null,
)

/** The activity's [AppMenuActions]; null (a test, a preview) offers none. */
val LocalAppMenu = compositionLocalOf<AppMenuActions?> { null }

/**
 * A top bar's overflow button and its menu, as every screen shows them: the button with a red dot
 * while an update is available ([updateAvailable]), and the menu opening from it — the screen's
 * [items], each given a `close` to call as it acts, then "Update available" last when there is
 * one, so the other items never shift. The menu opens its own window, which doesn't inherit the theme's scaled density or pinch
 * handler: [FontSizeWindow] re-applies the chosen size to the items, and [pinchFontSizeHost] lets a
 * pinch resize while it's open (SPEC *Display size*); the host consumes only a two-finger pinch.
 */
@Composable
internal fun AppOverflowMenu(
    updateAvailable: Boolean,
    onOpenAppListing: () -> Unit,
    // Called as the menu opens, before any item can be picked.
    onOpen: () -> Unit = {},
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Button and menu wrapped together so the dropdown anchors to the overflow button and opens from
    // it; a bare DropdownMenu sibling anchors to the row slot instead and drops from the wrong place.
    Box {
        IconButton(onClick = {
            onOpen()
            expanded = true
        }) {
            Box {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                if (updateAvailable) {
                    val updateDescription = stringResource(R.string.update_available)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            // Off-grid 2dp: an optical nudge seating the dot into the icon's
                            // top-right corner (the standard badge spot).
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                            .semantics { contentDescription = updateDescription }
                            .testTag(UPDATE_AVAILABLE_DOT_TAG),
                    )
                }
            }
        }
        StopDashMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
            // Last, not first: the screen's own items keep the same positions whether or not an
            // update is pending, so a tap learned by position never lands on the Play listing.
            if (updateAvailable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.update_available)) },
                    onClick = {
                        expanded = false
                        onOpenAppListing()
                    },
                )
            }
        }
    }
}

/**
 * The app's overflow on a screen with no menu of its own — On the way, a route's page, a station,
 * search, Settings — offering what a trip's page does, so a problem can be reported from wherever
 * it's seen (maintainer, 2026-09-29): [items] first, then "Send bug report" and About, from the
 * activity's [LocalAppMenu]. Nothing where none is provided (a test, a preview). [onOpen] runs as
 * the menu opens: a screen showing something the report's screenshot must not carry hides it there.
 */
@Composable
internal fun AppMenuOverflow(
    onOpen: () -> Unit = {},
    items: @Composable (close: () -> Unit) -> Unit = {},
) {
    val menu = LocalAppMenu.current ?: return
    var showAbout by rememberSaveable { mutableStateOf(false) }
    AppOverflowMenu(menu.updateAvailable, menu.onOpenAppListing, onOpen) { close ->
        items(close)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_send_bug_report)) },
            onClick = {
                close()
                menu.onSendBugReport()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_about)) },
            onClick = {
                close()
                showAbout = true
            },
        )
    }
    if (showAbout) {
        AboutDialog(
            onOpenLicenses = {
                showAbout = false
                menu.onOpenLicenses()
            },
            onDismiss = { showAbout = false },
        )
    }
}

/**
 * Every StopDash dropdown menu (the overflow menu, a row's or a trip card's long-press menu), styled
 * alike (maintainer, 2026-09-27, as in the sibling Clothescast): Material 3's surface with
 * corners rounded to [MENU_CORNER] rather than its near-square 4dp, and its items' text one step
 * up, `bodyLarge` rather than `labelLarge`, so they read a little larger. The menu opens its own
 * window, so it also re-applies the chosen font size ([FontSizeWindow]) and hosts the pinch
 * ([pinchFontSizeHost]) there, as each menu did on its own before.
 */
@Composable
internal fun StopDashMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(MENU_CORNER),
        modifier = Modifier.pinchFontSizeHost(),
    ) {
        FontSizeWindow {
            // A menu item sets its text from the theme's labelLarge: point that at bodyLarge here,
            // inside the menu's own window, so every item takes it without each passing a style.
            val typography = MaterialTheme.typography
            MaterialTheme(typography = typography.copy(labelLarge = typography.bodyLarge)) {
                Column { content() }
            }
        }
    }
}

/** A dropdown menu's corner radius: a soft card, not a sharp box. On the 4dp grid. */
internal val MENU_CORNER = 12.dp
