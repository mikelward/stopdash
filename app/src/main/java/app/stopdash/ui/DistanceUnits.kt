package app.stopdash.ui

import android.content.res.Resources
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stopdash.R
import app.stopdash.data.DistanceUnitsSetting
import app.stopdash.domain.DistanceSystem
import app.stopdash.domain.DistanceUnit
import app.stopdash.domain.StopDistance
import java.util.Locale

/**
 * The system near-me distances are written in on this screen (SPEC *Finding stops*), or null while
 * the stored choice is still being read: the list then shows no distance rather than one in units
 * the user may have turned away from. Meters outside [ProvideDistanceSystem] — a bare test render —
 * so a screenshot doesn't move with the test machine's locale.
 */
val LocalDistanceSystem = staticCompositionLocalOf<DistanceSystem?> { DistanceSystem.METERS }

/**
 * What [locale] measures distance in, from the platform's own locale data (CLDR): the UK's
 * system reads yards and miles, the US's (also Liberia's and Myanmar's) feet and miles, and
 * everywhere else meters and kilometers.
 */
internal fun localeDistanceSystem(locale: Locale): DistanceSystem =
    when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
        LocaleData.MeasurementSystem.UK -> DistanceSystem.YARDS
        LocaleData.MeasurementSystem.US -> DistanceSystem.FEET
        else -> DistanceSystem.METERS
    }

/**
 * Provides [LocalDistanceSystem] from the user's choice ([DistanceUnitsSetting], warmed in memory at
 * process start) and the current locale, so a change in Settings or in the phone's language
 * re-labels the list at once with no disk read on the render path.
 */
@Composable
internal fun ProvideDistanceSystem(content: @Composable () -> Unit) {
    val units by DistanceUnitsSetting.changes.collectAsStateWithLifecycle()
    val loaded by DistanceUnitsSetting.isLoaded.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    // The warm read starts at process start, so this is normally settled before any list has
    // distances to show; until it is, no label rather than a guess.
    val system = remember(units, loaded, locale) {
        if (loaded) units.resolve(localeDistanceSystem(locale)) else null
    }
    CompositionLocalProvider(LocalDistanceSystem provides system, content = content)
}

/**
 * [meters] as a stop's distance reads in [system], in the app's language ([StopDistance.reading]):
 * "120 m", "0.6 km", its decimal mark the language's own. One string format, so fine on any thread.
 */
internal fun distanceText(meters: Double, system: DistanceSystem, resources: Resources): String {
    val reading = StopDistance.reading(meters, system)
    val text = DISTANCE_TEXT.getValue(reading.unit)
    return if (reading.unit.tenths) resources.getString(text, reading.amount) else resources.getString(text, reading.count)
}

/** Each unit's words, its amount a whole number up close and to a tenth farther out ([DistanceUnit.tenths]). */
private val DISTANCE_TEXT = mapOf(
    DistanceUnit.METERS to R.string.distance_meters,
    DistanceUnit.KILOMETERS to R.string.distance_kilometers,
    DistanceUnit.YARDS to R.string.distance_yards,
    DistanceUnit.FEET to R.string.distance_feet,
    DistanceUnit.MILES to R.string.distance_miles,
)
