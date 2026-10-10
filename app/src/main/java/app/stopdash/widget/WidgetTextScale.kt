package app.stopdash.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.domain.DEFAULT_FONT_SCALE
import app.stopdash.ui.FontSizeSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's own text size (SPEC *Display size*), for the widget: a factor on top of the system's
 * font scale, which a widget's sp text already follows. Off the render path — the size the app has
 * in force when this process holds it (so a change that didn't save still shows, as in the app),
 * else the stored one. A store that can't be read draws at the system's own size, which is what a
 * rider who never chose one has anyway, as does one it can't read within [SETTINGS_READ_BOUND_MILLIS].
 */
internal suspend fun widgetTextScale(context: Context): Float {
    // The size the app shows in this process, chosen or read, whether or not the store can be read now.
    FontSizeSetting.shownScale.value?.let { return it }
    return try {
        // Bounded: the settings flow retries an IOException forever, so an unbounded read on a
        // storage that keeps failing would hold the render back for good.
        withTimeoutOrNull(SETTINGS_READ_BOUND_MILLIS) {
            DataStoreAppSettings.from(context, warn = ::logAppSettingsWarning).fontSize().first().scale
        } ?: run {
            logWidgetSnapshotWarning("widget text size read timed out")
            DEFAULT_FONT_SCALE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget text size read failed: ${e::class.simpleName}")
        DEFAULT_FONT_SCALE
    }
}

/**
 * The app's text size the widget is drawn at, provided by [WidgetContent] and [WidgetTripContent].
 * Only the factor over the system's scale: the host already applies the system's to every sp.
 */
internal val LocalWidgetTextScale = staticCompositionLocalOf { DEFAULT_FONT_SCALE }

/** [size] sp at the app's text size, for every text the widget draws. */
@Composable
internal fun widgetSp(size: Float): TextUnit = (size * LocalWidgetTextScale.current).sp
