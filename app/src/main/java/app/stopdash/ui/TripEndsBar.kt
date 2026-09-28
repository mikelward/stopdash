package app.stopdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.abbreviateStationName

/**
 * Where a trip starts over where it goes (maintainer, 2026-09-28), each row labeled, beside the back
 * arrow: the To… search's bar, and the trip page's once it has routes. "From" names the start —
 * "Here" behind the crosshair, or the From… station — and a tap opens the From… search to change it;
 * "To" holds the search field, or on the trip page the destination ([toField], given the row's
 * remaining width). [actions] (the trip page's overflow) sit at the From row's end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripEndsBar(
    fromStation: String?,
    onChangeFrom: () -> Unit,
    onBack: () -> Unit,
    labelWidth: Dp,
    actions: (@Composable () -> Unit)? = null,
    // Whether TalkBack reads the "To" label: yes beside the search field, no beside [ToField], which
    // says "To ‹place›" itself.
    readToLabel: Boolean = true,
    toField: @Composable (Modifier) -> Unit,
) {
    val fromLabel = stringResource(R.string.trip_ends_from)
    val toLabel = stringResource(R.string.trip_ends_to)
    val labelStyle = MaterialTheme.typography.labelLarge
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                // 4dp before the back arrow, as a top app bar insets its navigation icon, and after its
                // actions. None below: the place chips sit close under the To field they serve
                // (maintainer, 2026-09-28), with only the progress bar's 4dp slot and the chips' own
                // touch target between.
                .padding(start = ENDS_BAR_START, end = if (actions != null) ENDS_BAR_START else 16.dp, top = 8.dp)
                .testTag("tripEndsBar"),
            // The arrow and the actions sit level with the From row, all 48dp.
            verticalAlignment = Alignment.Top,
        ) {
            // A fixed slot, so the fields (and the chips under them) start at [tripEndsFieldStart].
            Box(modifier = Modifier.size(ENDS_BACK_SLOT), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
            Spacer(modifier = Modifier.width(ENDS_BACK_GAP))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The From field says "From ‹start›" itself, so its label isn't read twice.
                EndRow(fromLabel, labelWidth, labelStyle, readLabel = false) { FromField(fromStation, onChangeFrom, Modifier.weight(1f)) }
                EndRow(toLabel, labelWidth, labelStyle, readLabel = readToLabel) { toField(Modifier.weight(1f)) }
            }
            if (actions != null) {
                Box(modifier = Modifier.size(ENDS_BACK_SLOT), contentAlignment = Alignment.Center) { actions() }
            }
        }
    }
}

/**
 * The width both [TripEndsBar] labels take: the wider of "From" and "To", so the two fields start at
 * the same edge whatever the font scale.
 */
@Composable
internal fun rememberTripEndsLabelWidth(): Dp {
    val fromLabel = stringResource(R.string.trip_ends_from)
    val toLabel = stringResource(R.string.trip_ends_to)
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(fromLabel, toLabel, labelStyle, density) {
        with(density) {
            maxOf(measurer.measure(fromLabel, labelStyle).size.width, measurer.measure(toLabel, labelStyle).size.width).toDp()
        }
    }
}

/**
 * Where [TripEndsBar]'s fields start from the screen's edge, for anything to line up under them.
 * Summed in whole pixels, each part rounded as the bar's layout rounds it, so what lines up under a
 * field lands on its pixel rather than one off.
 */
internal fun tripEndsFieldStart(labelWidth: Dp, density: Density): Dp = with(density) {
    listOf(ENDS_BAR_START, ENDS_BACK_SLOT, ENDS_BACK_GAP, labelWidth, ENDS_LABEL_GAP).sumOf { it.roundToPx() }.toDp()
}

private val ENDS_BAR_START = 4.dp
private val ENDS_BACK_SLOT = 48.dp
private val ENDS_BACK_GAP = 4.dp
private val ENDS_LABEL_GAP = 12.dp

/** One labeled row of [TripEndsBar]: the label at [labelWidth], then its field. */
@Composable
private fun EndRow(
    label: String,
    labelWidth: Dp,
    labelStyle: TextStyle,
    readLabel: Boolean,
    field: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .width(labelWidth)
                .then(if (readLabel) Modifier else Modifier.clearAndSetSemantics {}),
        )
        Spacer(modifier = Modifier.width(ENDS_LABEL_GAP))
        field()
    }
}

/** A [TripEndsBar] field's box: the From row's, and the To search field's or [ToField], alike. */
@Composable
internal fun EndField(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(END_FIELD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The From row's field: "Here" behind the crosshair, or the station's name; a tap changes it. */
@Composable
private fun FromField(station: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val here = stringResource(R.string.from_here)
    // TalkBack hears the full name ("From King's Cross & St Pancras International"), not the
    // display abbreviation, and what a tap does.
    val description = stringResource(R.string.trip_from, station ?: here)
    val changeLabel = stringResource(R.string.trip_change_from)
    EndField(
        modifier = modifier
            .clip(END_FIELD_SHAPE)
            .clickable(role = Role.Button, onClickLabel = changeLabel, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("fromField"),
    ) {
        if (station == null) {
            Icon(CrosshairIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            station?.let(::abbreviateStationName) ?: here,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The trip page's To row: the destination by [name], as the From row names the start; a tap reopens
 * the To… search to go somewhere else, from the same start.
 */
@Composable
internal fun ToField(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.trip_to, name)
    val changeLabel = stringResource(R.string.trip_change_to)
    EndField(
        modifier = modifier
            .clip(END_FIELD_SHAPE)
            .clickable(role = Role.Button, onClickLabel = changeLabel, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("toField"),
    ) {
        Text(
            abbreviateStationName(name),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val END_FIELD_SHAPE = RoundedCornerShape(8.dp)
