package app.stopdash.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher

/**
 * Whatever leads a place's name in a trip's From or To field: a saved place's own icon, what the place
 * is, or the slot held for either until the saved places are read.
 */
internal val placeLead = SemanticsMatcher("leads a place's name") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("endPlace") == true
}
