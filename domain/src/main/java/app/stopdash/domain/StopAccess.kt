package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A station's step-free access for its details: the [level] the street reaches (null where TfL's table
 * describes none), whether a lift out of service is what took it to [StepFreeLevel.NONE] ([liftOut]),
 * and whether only a lift makes it step-free at all ([byLift]), so its outages are worth watching.
 */
data class StopAccess(val level: StepFreeLevel?, val liftOut: Boolean, val byLift: Boolean)

/**
 * A station's step-free access in [table] with the lifts in [out] out of service, across [stopIds] (every
 * id TfL lists it under, the table describing some under one and some under another): for [lineId]
 * where given (the line it was opened from, calling there), else for every one of the [lines] through it
 * (the index's), each line's level the one all its platforms meet, and the station's the lowest of
 * those, so a rider can count on it whichever line they take. Null where the table describes none, or,
 * for every line, where it leaves any line through it undescribed (Heathrow Terminal 5's National Rail
 * beside its Elizabeth line): unknown, never a guess (Codex on #678). A line is read only under the ids
 * [linesById] says serve it (an id it doesn't list serves any), so St Pancras's Southeastern platforms
 * never answer for its East Midlands trains under the other id, and a line is unknown where any id
 * serving it is undescribed, since that id's platforms may be the ones without access (Codex on #678);
 * where none of them is listed as serving it, under the first id, the stop itself. Applying the outages rebuilds the table, so
 * this runs on the worker.
 */
@WorkerThread
fun stopAccess(
    table: StepFreeAccess,
    out: Set<String>,
    stopIds: List<String>,
    lines: List<LineRef>,
    lineId: String?,
    mode: String,
    linesById: Map<String, Set<String>> = emptyMap(),
): StopAccess {
    fun servedBy(line: String): List<String> =
        stopIds.filter { id -> linesById[id]?.contains(line) != false }.ifEmpty { stopIds.take(1) }
    fun lineLevel(at: StepFreeAccess, id: String, lineMode: String): StepFreeLevel? =
        servedBy(id).map { at.levelFor(it, id, lineMode) }.let { levels -> if (null in levels) null else levels.filterNotNull().minOrNull() }
    // The lines whose access is shown: the opened one, else every line through the station.
    val shown = if (lineId != null) listOf(lineId to mode) else lines.map { it.id to it.mode }
    fun levelIn(at: StepFreeAccess): StepFreeLevel? {
        if (shown.isEmpty()) return null
        val levels = shown.map { (id, lineMode) -> lineLevel(at, id, lineMode) }
        return if (levels.any { it == null }) null else levels.filterNotNull().minOrNull()
    }
    val usual = levelIn(table)
    val now = if (out.isEmpty()) usual else levelIn(table.withLiftsOut(out))
    // Only a lift those lines' platforms need is worth watching outages for, and only while they're
    // step-free at all: an outage only takes access away (Codex on #678).
    val byLift = usual.stepFree() && shown.any { (id, lineMode) -> servedBy(id).any { table.byLiftFor(it, id, lineMode) } }
    return StopAccess(now, liftOut = usual.stepFree() && !now.stepFree(), byLift = byLift)
}

/**
 * [stopAccess] for the stop [stopId] with its [links] (the index's): across the station's own ids where
 * the index lists them, so a platform id opened under one isn't read as a part of it the table leaves out.
 */
@WorkerThread
fun stopAccessFor(
    table: StepFreeAccess,
    out: Set<String>,
    stopId: String,
    links: StopLinks,
    lineId: String?,
    mode: String,
): StopAccess {
    val ids = links.linesById.keys.toList().ifEmpty { listOf(stopId) + links.ownIds }
    return stopAccess(table, out, ids, links.lines, lineId, mode, links.linesById)
}

// Whether a level is known and lets a rider through without a step at all.
private fun StepFreeLevel?.stepFree(): Boolean = this != null && this != StepFreeLevel.NONE
