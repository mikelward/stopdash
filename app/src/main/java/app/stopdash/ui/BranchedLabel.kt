package app.stopdash.ui

import android.content.res.Resources
import app.stopdash.R

/**
 * The two strings the destination line renders for a branching row — the [terminus] and the
 * [branch] (slash included). The composable lays the branch out at its natural width and gives the
 * terminus the leftover (ellipsized), so these strings are already chosen to fit that split. The
 * full-name [contentDescription] is kept for a screen reader when the visible terminus is shortened
 * or hidden, and is null when the terminus shows in full. [branchDescription] does the same for the
 * branch ([BranchSpoken]): the full branch ("via Newbury Park") when a shortened one shows beside the
 * terminus, or the whole route ("Hainault via Newbury Park") when the branch stands alone; null when
 * the branch shows in full.
 */
internal data class BranchedLabel(
    val terminus: String,
    val branch: String,
    val contentDescription: String?,
    val branchDescription: BranchSpoken? = null,
)

/**
 * What a screen reader hears for a branch shown shortened or alone: its full [branch], after the full
 * [terminus] when there is one to name ("Hainault via Newbury Park"), else on its own ("via Newbury
 * Park"). Kept as its parts so the words joining them are the app's, in its language ([spokenText]).
 */
internal data class BranchSpoken(val branch: String, val terminus: String? = null)

/** [BranchSpoken]'s words, in the app's language: "via Newbury Park", "Hainault via Newbury Park". */
internal fun BranchSpoken.spokenText(resources: Resources): String =
    terminus?.let { resources.getString(R.string.branch_spoken_with_terminus, it, branch) }
        ?: resources.getString(R.string.branch_spoken, branch)

/**
 * Chooses the destination-line strings for a branching row from the glyph widths the caller
 * measured (all in px), so the rule is unit-testable apart from the measuring composable. The
 * branch joins with a slash, "/$branch".
 *
 * The **branch is never cut** — it is the cue that tells a branching line's two trunks apart, so
 * truncating it would lose which train this is. **Both sides are abbreviated before either is cut**
 * (maintainer, 2026-09-25): the full branch is kept while the terminus, in full or word-abbreviated,
 * fits beside it (the terminus yields first); otherwise the branch takes its abbreviated form and the
 * terminus the rest — its full name when that fits, its word-abbreviated form when it doesn't, and
 * its floor (first word + initials, `DestinationAbbreviations.floor`) otherwise, which the caller
 * ellipsizes with a single "…" (never a mid-glyph cut) if even the floor overflows the leftover.
 *
 * The one degenerate case is a row too narrow for even the terminus floor's first glyph beside the
 * whole short branch, at a large accessibility font scale: there the branch takes the row **alone
 * and bare** (no leading slash, so no orphaned "/"), the full name kept for a screen reader.
 *
 * @param label the full terminus name
 * @param abbreviatedLabel [label] with common whole words shortened (`DestinationAbbreviations.abbreviate`)
 * @param floorLabel [label] reduced to its first word plus later-word initials (`DestinationAbbreviations.floor`)
 * @param branch the via-branch as TfL gives it here ("Charing X", or a longer raw form)
 * @param abbreviatedBranch [branch] word-abbreviated (`abbreviateBranch`)
 * @param maxWidth the width available to the whole destination line
 * @param labelWidth measured width of [label]
 * @param abbrevLabelWidth measured width of [abbreviatedLabel]
 * @param fullBranchWidth measured width of "/$branch"
 * @param abbrevBranchWidth measured width of "/$abbreviatedBranch"
 * @param minStubWidth measured width of the terminus floor's first glyph **plus an ellipsis** — the
 *   smallest a recognizable stub can render beside the whole branch; below it the render would show
 *   only "…", so the branch takes the row alone instead
 */
internal fun branchedLabel(
    label: String,
    abbreviatedLabel: String,
    floorLabel: String,
    branch: String,
    abbreviatedBranch: String,
    maxWidth: Int,
    labelWidth: Int,
    abbrevLabelWidth: Int,
    fullBranchWidth: Int,
    abbrevBranchWidth: Int,
    minStubWidth: Int,
): BranchedLabel {
    // Longest terminus form that fits a budget, down to the floor; the render ellipsizes the floor
    // cleanly if even it overflows.
    fun terminusFor(budget: Int) = when {
        labelWidth <= budget -> label
        abbrevLabelWidth <= budget -> abbreviatedLabel
        else -> floorLabel
    }
    fun kept(branchStr: String, terminusStr: String) = BranchedLabel(
        terminus = terminusStr,
        branch = branchStr,
        contentDescription = if (terminusStr != label) label else null,
        // A screen reader hears the full branch whenever a shortened one shows (SPEC: the full name
        // stays the accessible label).
        branchDescription = if (branchStr != "/$branch") BranchSpoken(branch) else null,
    )
    // Both sides are abbreviated before either is cut: the full branch stays while the terminus in
    // full or word-abbreviated form fits beside it (the terminus yields first)...
    if (labelWidth + fullBranchWidth <= maxWidth) return kept("/$branch", label)
    if (abbrevLabelWidth + fullBranchWidth <= maxWidth) return kept("/$branch", abbreviatedLabel)
    // ...then the branch takes its abbreviated form too, and only beside that does the terminus
    // drop to its floor or elide. Keep the short branch whole — unless not even a recognizable stub
    // (a glyph plus its ellipsis) fits beside it, on a very narrow row at a large accessibility font
    // scale: there the branch takes the row alone and bare (no leading slash), the full name kept
    // for a screen reader. Reserving the ellipsis room keeps the stub from rendering as a bare "…".
    val budget = maxWidth - abbrevBranchWidth
    if (budget < minStubWidth) {
        return BranchedLabel(
            terminus = "",
            branch = abbreviatedBranch,
            contentDescription = label,
            branchDescription = BranchSpoken(branch, terminus = label),
        )
    }
    return kept("/$abbreviatedBranch", terminusFor(budget))
}
