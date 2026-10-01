package app.stopdash.domain

/**
 * The words that negate what follows them in TfL's alert prose: "not", "never", "no" (and "no
 * longer"), "cannot", "unable to", and any "-n't". One list for every reader of that prose — the
 * stops an alert leaves out ([AlertStops]), the label a catch-all names ([resolveDisruption]), and a
 * route said not to run ([RouteDisruption.scopable]) — since three kept apart drifted, each missing a
 * word the others had (Codex, PR #455). A pattern, to splice into a regex.
 */
internal const val NEGATION = """(?:\b(?:not|never|no|cannot|unable\s+to)\b|n't\b)"""
