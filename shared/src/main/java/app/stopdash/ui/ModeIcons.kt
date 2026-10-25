package app.stopdash.ui

import androidx.annotation.DrawableRes
import app.stopdash.domain.ModeIcon
import app.stopdash.shared.R

/** A mode icon's drawable, shared so the app, the widget and the watch draw the same one. */
@DrawableRes
fun modeIcon(icon: ModeIcon): Int = when (icon) {
    ModeIcon.TUBE -> R.drawable.ic_mode_tube
    ModeIcon.RAIL -> R.drawable.ic_mode_rail
    ModeIcon.TRAM -> R.drawable.ic_mode_tram
    ModeIcon.RIVER -> R.drawable.ic_mode_river
    ModeIcon.CABLE_CAR -> R.drawable.ic_mode_cable_car
    ModeIcon.BUS -> R.drawable.ic_mode_bus
}
