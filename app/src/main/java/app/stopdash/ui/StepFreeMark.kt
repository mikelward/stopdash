package app.stopdash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel

/**
 * TfL's step-free table (SPEC *Step-free access*), provided at the composition root once it's read
 * off the main thread. Null (the default, and until it's read) marks nothing — never a guess.
 */
val LocalStepFree = staticCompositionLocalOf<StepFreeAccess?> { null }

/** TfL's corporate blue, the color of its step-free symbols. */
private val TflStepFreeBlue = Color(0xFF0019A8)

/** Whether [level] earns a mark: none (or no data) shows nothing. */
internal fun StepFreeLevel?.marked(): Boolean = this != null && this != StepFreeLevel.NONE

/**
 * A station's step-free mark, TfL's own symbols (maintainer, 2026-10-02): a white wheelchair on a
 * blue disc where the rider can get from the street onto the train without a step
 * ([StepFreeLevel.LEVEL]); a blue wheelchair on a white disc where they can reach the platform but
 * need a step or a staff ramp onto the train ([StepFreeLevel.PLATFORM], [StepFreeLevel.RAMP]).
 * Nothing for [StepFreeLevel.NONE]. Drawn, not read: the row it sits in says the level.
 */
@Composable
internal fun StepFreeMark(level: StepFreeLevel, modifier: Modifier = Modifier) {
    if (!level.marked()) return
    val toTrain = level == StepFreeLevel.LEVEL
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            if (toTrain) {
                // A white rim keeps the blue disc distinct on a dark surface, as on TfL's map.
                drawCircle(Color.White, radius)
                drawCircle(TflStepFreeBlue, radius - 1.dp.toPx())
            } else {
                drawCircle(TflStepFreeBlue, radius)
                drawCircle(Color.White, radius - 1.5.dp.toPx())
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_step_free),
            contentDescription = null,
            tint = if (toTrain) Color.White else TflStepFreeBlue,
            // The figure four-fifths of the disc, so it scales with the text the mark is sized to.
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}
