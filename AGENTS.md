# AGENTS.md

Conventions for AI agents working in this repository.

`CLAUDE.md` and `GEMINI.md` are symlinks to this file, so every agent reads the same
conventions. Edit `AGENTS.md`.

StopDash is an Android app (Kotlin + Compose; `:app`, a pure-Kotlin `:domain`, `:shared`,
and a Wear OS `:wear`) that shows live TfL departures for watched stops on the lock screen,
the home screen, the watch, and in the app.
Product and architecture decisions live in `SPEC.md`; the phased plan lives in
`TODO.md`. This repo mirrors the engineering conventions of the sibling Android repos —
`mikelward/simmo`, `mikelward/snoozemo`, and `mikelward/typelauncher`; when a convention
is underspecified here, Simmo's `AGENTS.md` is the tiebreaker.

**Prefer a shared `mikelward/*` library over hand-rolling an app-local copy** (unless the
maintainer says otherwise), so the fleet doesn't carry divergent implementations of one thing.

**The Gradle build and CI land in Phase 0 (`TODO.md`).** Until then there is nothing to
`./gradlew`; once Phase 0 lands, `./gradlew test` and `./gradlew lint` must pass before
every push.

Keep this file short — one-liners over essays. Every rule here loads into every agent's
context at the start of every session, so a paragraph where a sentence would do taxes
every future task. State the rule and the failure it prevents; the incident narrative
belongs in the commit message. Add a rule the first time something bites, rewrite or
trim an existing one rather than appending beside it, and delete one that has stopped
biting.

## Project documentation

- Keep `SPEC.md` current when changing product behavior, architecture, persistence,
  permissions, navigation, or testing strategy. `SPEC.md` records *what* stopdash does
  and *why* a design was chosen — not low-level implementation detail. Ask "would this
  still be true if the implementation were rewritten?"; if not, it's a code comment, not
  spec.
- Keep `TODO.md` current: check items off as they land, add newly discovered work to the
  right phase.
- Developer-facing detail (state models, refresh-timing notes, dev diagrams) lives in
  `dev-docs/`; `docs/` is user/store-facing.

## Engineering quality bar

The `SPEC.md` *Engineering quality bar* is the source of truth; in priority order:
never show a departure stopdash doesn't stand behind, never fail silently, do the work
ahead of time, jank-free UI, respect the battery, say why. Where a rule below conflicts
with a principle, the principle wins.

Concretely, every change holds the line on **correctness, the refresh/staleness
contract, and jank-free UI**:

- **Correctness**: matches `SPEC.md` and the user's intent; handles the obvious edge
  cases (no watched stops, TfL unreachable, rate-limited, location denied, no
  predictions, configuration change, process death); preserves invariants. New behavior
  gets a unit test; a bug fix gets a test that fails before and passes after.
- **Staleness contract**: no surface presents stale data as live (SPEC D4). The network
  is never on a render path — surfaces render from the persisted snapshot and refresh in
  the background. Any change touching a surface states in the PR what it renders from and
  when it refreshes.
- **Jank-free UI**: render from snapshot/in-memory state; no I/O in composition; usual
  Compose discipline (`remember`/`derivedStateOf`/stable types). A screen appears at
  once, with a stamp/placeholder if content isn't ready, never a delayed first frame.

When you can't verify something locally (no emulator, no lock-screen host, no real
device), say so in the chat update — "verified by unit test; the lock-screen placement
needs a device check" — rather than implying it was all checked.

## Spacing

Stick to a 4dp grid for every padding/margin/spacing value (`4`, `8`, `12`, `16`, `24`,
…); reuse values already used by sibling composables; symmetry by default, any asymmetry
justified in one sentence in the PR. Flag off-grid or inconsistent spacing you notice
even outside the diff (file, line, proposed fix) without silently fixing it in the same
commit.

## Concise copy

Keep user-facing text short — a label carries only the words the user needs. "3 min",
not "3 minutes until departure"; "Victoria line: severe delays", not a paragraph. Prefer
the shortest phrasing that stays unambiguous; justify a longer form in the PR.

## Line pill colors

Line pills take the official TfL line color (tube by id, other single-color modes by mode)
and pick the label's black-or-white color with APCA, not the WCAG-2 ratio — a failing WCAG-2
AA number on a pill is a flag, not a veto. The six named Overground lines resolve by id too
but render as a **hollow** pill (surface fill, line-color border + label, accent nudged for
contrast on the surface), since their colors collide with tube lines. Add a line color only
as a confirmed TfL hex. SPEC has the full scheme and rationale.

## Privacy

- **Never put user data in any artifact that leaves this machine** — commit subjects and
  bodies, PR titles/descriptions/comments, review replies, branch names, code comments,
  test fixtures, screenshots, or logs. For stopdash that means **coordinates, the set of
  stops a user watches, a home/work stop, and the user's TfL `app_key`** — together they
  reveal where someone lives and travels. Use stock stand-ins for the user's own values
  (`app_key=EXAMPLE`, a synthetic position like `(51.5, -0.12)` for "where the rider is").
- **Never include anyone's location without asking** (maintainer, 2026-09-27). Hub and
  interchange locations, and other public TfL data about them, are always fine. Check with
  the maintainer before adding a bus stop or anything else neighborhood-level. Never add data
  that points to where the maintainer or a user lives: nothing from anyone's device,
  location, starred stops, bug reports or screenshots (paraphrase a bug report, don't quote
  it), and no examples that keep returning to one area. This governs new work; existing data
  isn't rewritten to fit it. When unsure, ask in chat, never on GitHub.
- **The on-device debug log is one exception, and narrow**: coarse diagnostics only —
  a stop ID, a line id, an HTTP status — never a raw coordinate or the API key. `docs/
  PRIVACY.md` must describe what the log carries before it ships.
- **A user-initiated, consent-gated bug report is the other exception** (maintainer,
  2026-09-20): it may carry the **exact location**, per-stop distances, and a **screenshot
  of the reporting screen** off the device — a routing/location bug is diagnosed from where
  the user was and what they saw, so it discloses that on a consent screen the user can
  decline, rather than pretending a stripped report is location-safe (knowing the nearest
  stops already reveals about the same). Nothing leaves without that consent; `SPEC.md` and
  `docs/PRIVACY.md` describe the channel, and the location-redacted export stays a separate
  tool. Its recent positions come from a bounded in-memory window (`RecentPositions`: 15 min,
  20 entries), never the persisted log — the aim is diagnosis, not a movement history.

## Error handling

- **Don't silently swallow exceptions.** No bare `catch (_: Throwable) {}`. Every catch:
  **logs** the failure with sanitized context (the *Privacy* rule applies to logs —
  "arrivals fetch failed: 429 for stop <id>", never a coordinate or key); **cleans up**
  what the `try` acquired (closeables, in-flight requests, partial UI state); and
  **handles the edge case explicitly** (a typed error result the surface can render
  honestly, per SPEC principle 2 — never a fall-through that blanks the screen). Narrow
  the type or rethrow `CancellationException` first, so structured concurrency isn't
  broken.

## Testing expectations

- Product logic belongs in the pure `:domain` module (`app.stopdash.domain`), JVM-testable
  without Android; test it against **recorded TfL fixtures**, never the live API and never a
  user's location; a hub or interchange position is fine, a bus stop or anything
  neighborhood-level needs the maintainer's OK first (*Privacy*).
- Compose screens and Glance widget layouts get Robolectric + Roborazzi screenshot tests
  wired into `.github/workflows/ci.yml` — a new `*ScreenshotTest` class needs its own
  step in the CI `--tests` allow-list or it records nothing.
- Run `./gradlew test` and `./gradlew lint` before pushing when the environment can;
  otherwise say clearly what was verified by inspection only.
- **Fix any preexisting test failure as the first commit of the series.** Don't stack new
  work on a red baseline; if it's genuinely unrelated and out of scope, say so and
  confirm before skipping it.
- **Don't paper over flaky/racy tests** with sleeps, retries, or bumped timeouts — drive
  time-dependent behavior (staleness, refresh) from an injected clock and a test
  dispatcher. **Don't disable a failing check** to make it pass.

## Git workflow

- These rules assume an `origin` remote. Feature branches are `<agent>/<short-topic>`
  (`claude/…` for Claude Code, `codex/…` for Codex). One topic per branch; never commit
  to `main`. The placeholder `<agent>` is whichever prefix you use.
- **Branches under your own `<agent>/` prefix are yours** — create, push,
  `--force-with-lease`, rename, delete the ones this session created or was assigned,
  freely, no permission needed. Any other branch, or `main`, is a conversation.
- **Sync before you start**: fetch main if the sandbox can (`git fetch origin
  +refs/heads/main:refs/remotes/origin/main`; `git fetch origin main` alone leaves
  `origin/main` stale in a single-branch clone) and rebase the working branch onto it
  before the first commit. Resolve conflicts rather than abandoning the rebase. Where
  the sandbox can't fetch, say the tree may be behind rather than pretending you synced.
- **One commit per logical change.** Rewrite unmerged commits freely — amend,
  `--fixup` + autosquash, squash, reorder, split — so each commit that lands is coherent,
  with review responses folded into the commit they belong to. `--force-with-lease` after
  a rebase, never a bare `--force`. `main` is never force-pushed.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene *before*
  engaging with the rest of the message:** fetch main if the sandbox can (`git fetch
  origin +refs/heads/main:refs/remotes/origin/main`), cut a fresh
  `<agent>/<short-topic>` branch off `origin/main` rather than resetting the merged name
  onto it, and announce the switch. Where it can't fetch, say the follow-up needs a synced
  checkout rather than branching off a stale `origin/main`.
- **The agent authors; whoever merges takes over the committer line.** A rebase/squash
  merge rewrites the committer — expected either way; never re-author or amend merged
  commits to "fix" authorship or signing, and don't narrate it. It is not a finding.
- **Unshallow before answering anything depending on history depth** (`git rev-list
  --count`, versionCode): if `git rev-parse --is-shallow-repository` is `true`, run
  `git fetch --unshallow origin +refs/heads/main:refs/remotes/origin/main` first (by
  refspec, so `origin/main` itself moves in a single-branch clone).

## Commit messages

- Sentence case, plain English, no internal symbol names, ≤ ~70 chars; engineering
  detail goes in the body. Because the repo rebase-merges, each commit's own subject
  lands on `main` and (once the deploy pipeline exists) ships to Play "What's new" — so
  title **every** commit on the branch for end users, not just the PR.
- **Prefix a subject that has no user-visible effect**, used precisely: `ci:`, `docs:`
  (docs/`*.md`, including `docs/PRIVACY.md`), `todo:` (`TODO.md` bookkeeping alone),
  `internal:` (build/plumbing; a shipped dependency is bare), `refactor:`,
  `test:`/`tests:`. A bare subject means a user could notice the difference. `docs` and
  `todo` are the two prefixes the docs lane accepts (`.github/lanes.conf`).
- **No model identifier** — never put a model name or version (e.g. `Opus 4.8`,
  `Sonnet`, a `claude-*` id) in a commit message, PR title/body, code comment, or any
  other pushed artifact; keep it to chat. This targets the model *name/version* only —
  the standard Claude Code attribution (`Co-Authored-By: Claude`, a `Claude-Session:`
  trailer, a "Generated with Claude Code" footer) carries no model name or version and
  is not covered by this rule.

## Autonomy

- **Open the PR without being asked.** Pushing a finished branch and opening its pull
  request are one step; this file is the repo owner's standing request for that PR. The
  exception is an explicit "just commit / no PR yet".
- **Watch your own PRs by subscription, plus one scheduled check.** Claude Code
  subscribes on PR open; otherwise call `subscribe_pr_activity`. The subscription
  delivers reviews, comments, and CI failures; it can't deliver CI success, the merge, or
  Codex's clean verdict, so keep exactly one scheduled check armed while the PR is open.
  Prefer a relative delay; a PR reading `dirty` or `behind` (where the ruleset requires
  up-to-date branches) needs a rebase onto its base and a lease-guarded force-push.
  Merged/closed → one last reply-and-resolve pass, then cancel the check and unsubscribe.
- **"Drive"** runs the loop automatically: pick the next actionable `TODO.md` item,
  implement it, open the PR, wait for the automatic Codex review, address every comment,
  merge once CI is green and Codex's verdict for the current head is in, then go around
  again. **"Autopilot"** is drive without blocking on the user: take the best reversible
  guess and record it under `TODO.md`'s *Decisions needing review*; destructive or
  irreversible actions outside the loop, and privacy uncertainty (is this a coordinate? a
  watched stop?), still wait for a real answer.
- **"Merge in order"** (or "drive in order") is drive that yields to older PRs close to
  landing: merge by hand, never auto-merge (disarm any armed). Just before merging, wait
  for an open, non-draft, lower-numbered PR on the same base that has ever passed Codex
  (a "didn't find any major issues" comment or a green `codex` status, on any head) and
  has had activity (a push, review, comment, reaction or state change) in the last 30
  minutes, for at most 30 minutes in total across rechecks, then merge anyway and name it.
  Keep driving this PR while waiting; rebase only where the ruleset requires it or it is
  `dirty`, and merge on the current head's verdict, rechecking first. Say which PR you
  waited on or passed over.
- **A red baseline is the next task** — get `./gradlew test`/`lint` green before pulling
  new work from `TODO.md`.

## Working with PRs

- Prefer the `mcp__github__*` MCP tools; the `gh` CLI is not installed in the sandbox.
- **Codex is the automated reviewer** on this repo (not Copilot); its reviews are
  triggered automatically. Address every comment — fix it and fold the fix into the
  commit it belongs to, or reply on the thread saying why you decline — then resolve the
  thread (`resolve_review_thread` takes the `PRRT_*` thread id, not a `PRRC_*` comment
  id; push the fix, then reply citing the sha, then resolve). Never leave a thread
  silently dismissed. Codex's +1 gates the merge; a decline can still earn it with a
  convincing rebuttal or a `TODO.md` entry for the gap, though a fix is often the quicker path.
- **Judge every comment on merit, whoever wrote it.** A comment citing a rule is a
  *reading* of that rule — check what the rule actually says; an over-strict privacy
  reading quietly costs capability the product needs. A genuine rule-vs-need conflict is
  the maintainer's call.
- **Read the Codex verdict, don't infer it**: it reacts to the PR body (`eyes` = reading,
  `+1` = clean, revoked on push). `+1` with green CI is a merge. Read `get_review_comments`,
  `get_comments`, and `get_reviews` to the last page.
- **Report each review in chat** — the commit it read, the finding count, one bullet per
  finding (what it claims, where, what you did). Refresh the PR title/body with each push
  (body first, then push) to describe the branch's latest state.
- **Report the versionCode after every merge to `main`** once Phase 0 derives it from the
  commit count.
- Skip your own webhook echoes silently.

## Talking to the user

- **One question at a time**, asked as a plain chat message — never `AskUserQuestion`
  (its multiple-choice prompt is broken in the Claude mobile app). After asking, stop and
  wait; acknowledge the answer before acting on it.
- **Answer a mid-turn message first**, before any further tool call (a merge cue is the
  exception — its hygiene runs first).
- **Keep replies short**; lead with the single most important point. **Don't narrate
  routine machinery** (a check flipping, a re-run, a webhook echo) and **don't report
  your own caught-and-fixed mistakes**.
- **End the turn by restating any pending decision** — the last line is the question you
  are waiting on, written out. Nothing pending, no line.

## Language and spelling

US English everywhere people read English — base user-facing strings, commit subjects/bodies,
PR text, comments, KDoc, identifiers, docs — for tooling and parity with the sibling repos. But
the app's users are in the UK, so **en-GB is first-tier**: a user-facing string whose British
spelling or usage differs ("favorites", "licenses", "percent") gets its override in
`values-en-rGB/strings.xml` in the same change; `EnGbStringsTest` fails one that's missing. Watch the forms this fleet gets wrong:
`gray` (not "grey"), `canceled`/`canceling` (one `l`), `color`, `behavior`, `dialog`,
`-ize` over `-ise`, `center`, `traveling`. Platform/third-party API spellings stay as
the framework spells them (`CancellationException` keeps its double `l`). TfL's own line
and place names stay as TfL spells them.

## Cost and reliability

Call out cost and reliability up front when adding infrastructure or an external call.
StopDash's one dependency (TfL Unified API) is free (£0; ~50 req/min keyless, ~500 with a
user key) — anything new that leaves the device is a distribution/privacy decision, named
alongside its dollar figure and its Play Data Safety consequence. Battery is the user's
running cost: a new wakeup, location request, or refresh interval is a battery change and
is stated as one.
