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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import app.stopdash.domain.DestinationViaChoice
import app.stopdash.domain.Pick
import app.stopdash.domain.PlaceArt
import app.stopdash.domain.TripVia
import app.stopdash.domain.ViaChoice
import app.stopdash.domain.abbreviateStationName

/**
 * Where a trip starts over where it goes (maintainer, 2026-09-28), each row labeled, beside the back
 * arrow: the To… search's bar, and the trip page's once it has routes. "From" names the start —
 * "Here" behind the crosshair, a place behind its own icon or what it is ([fromPlace], [fromIcon]), or
 * the From… station — and a tap opens the From… search to change it; "To" holds the search field, or on the trip page the
 * destination ([toField], given the row's remaining width). [actions] (the trip page's overflow) sit
 * at the From row's end. A trip sent through a stop ([via]; SPEC *Going via a stop*) names it in a
 * "Via" row between the two, a tap to change it and an × at the row's end, under the overflow, to go
 * any way again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripEndsBar(
    fromStation: String?,
    onChangeFrom: () -> Unit,
    onBack: () -> Unit,
    labelWidth: Dp,
    // What the place [fromStation] names is (a searched place's type, else plain); null for a station.
    fromPlace: PlaceArt? = null,
    // A saved place's own icon ([PlaceIconIndex]) to lead it with in place of [fromPlace]'s.
    fromIcon: String? = null,
    actions: (@Composable () -> Unit)? = null,
    // Whether TalkBack reads the "To" label: yes beside the search field, no beside [ToField], which
    // says "To ‹place›" itself.
    readToLabel: Boolean = true,
    // The stop the trip goes via, with its change and its ×; null shows no Via row.
    via: TripEndsVia? = null,
    toField: @Composable (Modifier) -> Unit,
) {
    val fromLabel = stringResource(R.string.trip_ends_from)
    val viaLabel = stringResource(R.string.trip_ends_via)
    val toLabel = stringResource(R.string.trip_ends_to)
    val labelStyle = MaterialTheme.typography.labelLarge
    // Each row its own: the back arrow's slot (empty under it), the labeled field, and the actions' slot at
    // its end where the bar has actions or a Via row (the overflow by From, Via's ×, empty by To), so a row's
    // trailing button sits level with its own field whatever the font scale, and every field ends at one edge.
    val trailing = actions != null || via != null
    @Composable
    fun BarRow(lead: (@Composable () -> Unit)?, trail: (@Composable () -> Unit)?, content: @Composable (Modifier) -> Unit) {
        // The arrow and the actions sit level with their row, all 48dp.
        Row(verticalAlignment = Alignment.Top) {
            // A fixed slot, so the fields (and the chips under them) start at [tripEndsFieldStart].
            Box(modifier = Modifier.size(ENDS_BACK_SLOT), contentAlignment = Alignment.Center) { lead?.invoke() }
            Spacer(modifier = Modifier.width(ENDS_BACK_GAP))
            content(Modifier.weight(1f))
            if (trailing) {
                Box(modifier = Modifier.size(ENDS_BACK_SLOT), contentAlignment = Alignment.Center) { trail?.invoke() }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                // 4dp before the back arrow, as a top app bar insets its navigation icon, and after its
                // actions. None below: the place chips sit close under the To field they serve
                // (maintainer, 2026-09-28), with only the progress bar's 4dp slot and the chips' own
                // touch target between.
                .padding(start = ENDS_BAR_START, end = if (trailing) ENDS_BAR_START else 16.dp, top = 8.dp)
                .testTag("tripEndsBar"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val back: @Composable () -> Unit = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
            // The From field says "From ‹start›" itself, so its label isn't read twice.
            BarRow(back, actions) { modifier ->
                EndRow(fromLabel, labelWidth, labelStyle, readLabel = false, modifier) { FromField(fromStation, fromPlace, fromIcon, onChangeFrom, Modifier.weight(1f)) }
            }
            if (via != null) {
                // The Via field says "Via ‹stop›" itself, as From's does.
                BarRow(null, { ClearViaButton(via) }) { modifier ->
                    EndRow(viaLabel, labelWidth, labelStyle, readLabel = false, modifier) { ViaField(via.name, via.onChange, Modifier.weight(1f)) }
                }
            }
            BarRow(null, null) { modifier ->
                EndRow(toLabel, labelWidth, labelStyle, readLabel = readToLabel, modifier) { toField(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The stop a trip goes via (SPEC *Going via a stop*), for [TripEndsBar]'s Via row: its [name], a tap to
 * pick another ([onChange]), and its × to go any way again ([onClear]).
 */
class TripEndsVia(val name: String, val onChange: () -> Unit, val onClear: () -> Unit)

/**
 * Keeps a trip's [DestinationViaChoice] over a rotation and a recreated process: the destination it belongs to,
 * then each of its stops' id and name, then each one's [Pick.Level.code], empty for none, on the device. A
 * shape saved before the levels were restores with none.
 */
internal val ViaChoiceSaver: Saver<DestinationViaChoice, Any> = listSaver(
    save = { held ->
        val stops = listOf(held.choice.via, held.choice.pending)
        listOf(held.destination.orEmpty()) +
            stops.flatMap { listOf(it?.stopId.orEmpty(), it?.name.orEmpty()) } +
            stops.map { it?.level?.code.orEmpty() }
    },
    restore = { saved ->
        fun at(index: Int, levelAt: Int) = saved.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let {
            TripVia(it, saved.getOrNull(index + 1).orEmpty(), Pick.Level.ofCode(saved.getOrNull(levelAt)))
        }
        DestinationViaChoice(saved.firstOrNull()?.takeIf { it.isNotEmpty() }, ViaChoice(via = at(1, 5), pending = at(3, 6)))
    },
)

/**
 * A trip's [ViaChoice] while the trip to [destination] is open, kept over a rotation and a recreated process
 * ([ViaChoiceSaver]). Another destination starts without one; the first one picked keeps what was chosen before
 * it (SPEC *Going via a stop*). [held] is the caller's, where the via has to outlast this composable — the near-me
 * trip's, over an overlay that takes the trip off screen, and set by a view's Via; null holds it here.
 */
@Composable
internal fun rememberViaChoice(destination: String?, held: MutableState<DestinationViaChoice>? = null): MutableState<ViaChoice> {
    val choice = held ?: rememberSaveable(stateSaver = ViaChoiceSaver) { mutableStateOf(DestinationViaChoice(destination)) }
    val now = choice.value.to(destination)
    // Kept as the destination's own once it is shown, so the next destination starts from it, not from before.
    SideEffect { if (choice.value != now) choice.value = now }
    return remember(choice, destination) { DestinationViaState(choice, destination) }
}

/** [held]'s choice as the trip to [destination] sees it ([DestinationViaChoice.to]), and set as its own. */
private class DestinationViaState(private val held: MutableState<DestinationViaChoice>, private val destination: String?) :
    MutableState<ViaChoice> {
    override var value: ViaChoice
        get() = held.value.to(destination).choice
        set(value) {
            held.value = DestinationViaChoice(destination, value)
        }

    override fun component1(): ViaChoice = value

    override fun component2(): (ViaChoice) -> Unit = { value = it }
}

/**
 * The width every [TripEndsBar] label takes: the widest of "From", "Via" and "To", so the fields start at
 * the same edge whatever the font scale, and don't move as a Via row comes and goes.
 */
@Composable
internal fun rememberTripEndsLabelWidth(): Dp {
    val fromLabel = stringResource(R.string.trip_ends_from)
    val viaLabel = stringResource(R.string.trip_ends_via)
    val toLabel = stringResource(R.string.trip_ends_to)
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(fromLabel, viaLabel, toLabel, labelStyle, density) {
        with(density) {
            listOf(fromLabel, viaLabel, toLabel).maxOf { measurer.measure(it, labelStyle).size.width }.toDp()
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
    modifier: Modifier = Modifier,
    field: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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

/**
 * The From row's field: "Here" behind the crosshair, a [place]'s name behind its own [icon] or what it
 * is, or the station's name; a tap changes it.
 */
@Composable
private fun FromField(station: String?, place: PlaceArt?, icon: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        when {
            station == null -> Icon(CrosshairIcon, contentDescription = null, modifier = Modifier.size(END_ICON_SIZE))
            place != null -> PlaceLead(place, icon)
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
 * The trip page's Via row's field: the stop every route passes by [name], as the To row names the
 * destination; a tap reopens the Via… search to pick another.
 */
@Composable
private fun ViaField(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.trip_via, name)
    val changeLabel = stringResource(R.string.trip_change_via)
    EndField(
        modifier = modifier
            .clip(END_FIELD_SHAPE)
            .clickable(role = Role.Button, onClickLabel = changeLabel, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("viaField"),
    ) {
        Text(
            abbreviateStationName(name),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Via row's ×, under the overflow: the trip goes any way again. */
@Composable
private fun ClearViaButton(via: TripEndsVia) {
    IconButton(onClick = via.onClear, modifier = Modifier.testTag("clearVia")) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.trip_clear_via))
    }
}

/**
 * The trip page's To row: the destination by [name], as the From row names the start, behind its own
 * [icon] or what it is when it's a [place]; a tap reopens the To… search to go somewhere else, from the
 * same start.
 */
@Composable
internal fun ToField(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, place: PlaceArt? = null, icon: String? = null) {
    val description = stringResource(R.string.trip_to, name)
    val changeLabel = stringResource(R.string.trip_change_to)
    EndField(
        modifier = modifier
            .clip(END_FIELD_SHAPE)
            .clickable(role = Role.Button, onClickLabel = changeLabel, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("toField"),
    ) {
        if (place != null) PlaceLead(place, icon)
        Text(
            abbreviateStationName(name),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What leads a place's name in a field, as the crosshair leads "Here" (maintainer, 2026-10-13). A place
 * picked from search leads with what it is ([art]), as its search row showed it (a pub's glass, a
 * street's sign), at once: that's the row the rider tapped, whatever saved place may share its name and
 * coordinate (Codex on #827). Any other place is a saved place's own [icon], as its chip shows it, or a
 * place pin (one nobody typed, or a saved place with no icon this build draws); until the saved places
 * are first read (a screen restored after process death) it can't tell which, so it holds the icon's
 * slot empty rather than draw one it may then swap: the name stays put either way. Decorative: the
 * field's description names the place.
 */
@Composable
private fun PlaceLead(art: PlaceArt, icon: String?) {
    if (art != PlaceArt.PLAIN) {
        PlaceArtIcon(art)
    } else if (!LocalPlaceIcons.current.ready) {
        Spacer(Modifier.size(END_ICON_SIZE).testTag("endPlaceIconPending"))
    } else if (icon != null && hasPlaceIcon(icon)) {
        PlaceIcon(icon, Modifier.size(END_ICON_SIZE).testTag("endPlaceIcon-$icon"))
    } else {
        PlaceArtIcon(art)
    }
}

@Composable
private fun PlaceArtIcon(art: PlaceArt) {
    Icon(painterResource(placeIcon(art)), contentDescription = null, modifier = Modifier.size(END_ICON_SIZE).testTag("endPlaceType-${art.code}"))
}

private val END_FIELD_SHAPE = RoundedCornerShape(8.dp)

// The size of the icon leading a field's name: the crosshair's, and a place's.
private val END_ICON_SIZE = 18.dp
