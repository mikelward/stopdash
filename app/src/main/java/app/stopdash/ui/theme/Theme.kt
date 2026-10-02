package app.stopdash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.stopdash.ui.FontSizeState
import app.stopdash.ui.LocalBaseDensity
import app.stopdash.ui.LocalFontSizeState
import app.stopdash.ui.pinchFontSize
import app.stopdash.ui.rememberFontSizeState

// LDN Go's brand accent is red — a nod to the London transit palette — applied the
// Material way: it seeds `primary` (and its container/secondary/tertiary partners), so it
// surfaces as an accent on buttons, the refresh and progress indicators, and selection,
// over otherwise-neutral surfaces. It is deliberately *not* the app-bar container, to keep
// the red an accent rather than a wash. Tonal values are the Material 3 red-seed scheme, so
// contrast against the on-* roles holds in both themes. First pass at the brand color
// (maintainer, 2026-09-19) — revisit freely; see TODO.md.
private val LightColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775652),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF725B2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEDFA6),
    onTertiaryContainer = Color(0xFF261A00),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A9),
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFE0C38C),
    onTertiary = Color(0xFF3F2D04),
    tertiaryContainer = Color(0xFF584419),
    onTertiaryContainer = Color(0xFFFEDFA6),
)

// The border color that marks a starred (pinned-to-top) departure card — gold, so the pin
// reads as a mark of favor and stays distinct from the red brand accent. Two tones so it holds
// contrast on each card surface: a deeper gold on the light surface, a brighter one on the dark.
// Provided through [LocalStarredBorderColor] from the resolved theme, so a forced-dark preview
// (screenshot tests) gets the dark gold rather than following the system.
private val LightStarredBorder = Color(0xFFB8860B)
private val DarkStarredBorder = Color(0xFFE7C34C)

/** The gold border for a starred card, resolved to the current theme (see [StopDashTheme]). */
val LocalStarredBorderColor = staticCompositionLocalOf { LightStarredBorder }

/**
 * The app theme. Uses the LDN Go red-accent scheme (light or dark by the system setting), and
 * sizes the whole app from the user's chosen text size (SPEC *Display size*).
 *
 * Dynamic color (Material You) is **off by default**: it would let the device wallpaper
 * override the brand — which is exactly why the app read as a neutral charcoal on-device —
 * so the red identity is shown consistently instead. A caller can still opt into dynamic
 * color, and it is the one thing to flip if the brand ever yields to Material You.
 *
 * [fontSize] is the text-size handle: the theme sizes the app from it and hosts the two-finger
 * pinch above every screen, so a pinch resizes the app wherever the user is. Defaulted so every
 * call site — the app screens and the screenshot tests — is sized by the user's setting and
 * answers a pinch without saying so; a caller that also *shows* the setting reads
 * [LocalFontSizeState] so the slider and the gesture move one value.
 */
@Composable
fun StopDashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    fontSize: FontSizeState = rememberFontSizeState(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val starredBorder = if (darkTheme) DarkStarredBorder else LightStarredBorder
    // Only `fontScale` is overridden, so text grows while paddings, icons, and touch targets keep
    // the 4dp-grid layout — and it multiplies the system's own scale rather than replacing it, so
    // an accessibility setting made in Android is respected (SPEC *Display size*).
    val density = LocalDensity.current
    val scaled = remember(density, fontSize.scale) {
        Density(density.density, density.fontScale * fontSize.scale)
    }
    MaterialTheme(colorScheme = colorScheme) {
        // The gesture sits above every screen rather than on any one of them: a pinch resizes the
        // app, so it must work wherever the user is. Hosted **outside** the scaled density —
        // Compose restarts a pointer-input handler when the density under it changes, and every
        // preview frame of a pinch changes exactly that, so a gesture hosted under `scaled` would
        // die on its own first resize. Only `fontScale` differs between the two densities, so the
        // slop this reads is unchanged — it is a distance in fingers, not in text.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchFontSize(
                    enabled = { fontSize.pinchEnabled },
                    scale = { fontSize.scale },
                    onStart = fontSize::startGesture,
                    onPreview = fontSize::preview,
                    onSettled = fontSize::commit,
                ),
        ) {
            CompositionLocalProvider(
                LocalDensity provides scaled,
                // So a popup or dialog in its own window can re-establish both — neither the
                // density nor the gesture crosses a window boundary on its own.
                LocalFontSizeState provides fontSize,
                // And so a control that resizes text as it is dragged can host its own input where
                // the density does not move (the Settings slider).
                LocalBaseDensity provides density,
                // The gold pin border, resolved to the current theme (see [LocalStarredBorderColor]).
                LocalStarredBorderColor provides starredBorder,
                content = content,
            )
        }
    }
}
