---
trigger: always_on
alwaysApply: true
last_modified: 2026-10-26
---

# AGENTS.md

`CLAUDE.md` and `GEMINI.md` are symlinks to this file. Edit `AGENTS.md` and bump `last_modified`.

**At the start of every session, print the full absolute path of the `AGENTS.md` you
loaded and its `last_modified` date**, so a stale or wrong copy is caught first.

StopDash is an Android app (Kotlin + Compose; `:app`, pure-Kotlin `:domain`, `:shared`,
Wear OS `:wear`) showing live TfL departures on lock screen, home screen, watch, and app.
Product/architecture decisions live in `SPEC.md`, phased plan in `TODO.md`. Mirrors
`mikelward/simmo` (tiebreaker), `snoozemo`, and `typelauncher`.

**Prefer a shared `mikelward/*` library over an app-local copy** unless told otherwise.

**`./gradlew test` and `./gradlew lint` must pass before every push.**

Keep this file short — one-liners over essays, **under 22,000 bytes** (`wc -c AGENTS.md`):
Antigravity caps rule files at 24 KB and silently truncates the tail. State the rule and
the failure it prevents; incident narratives belong in commits. Add a rule the first time
something bites, rewrite/trim rather than append, and delete rules that stopped biting.

## Project documentation

- Keep `SPEC.md` current when changing behavior, architecture, persistence, permissions,
  navigation, or testing: *what* and *why*, not detail ("still true if rewritten?"; if
  not, code comment). Keep `TODO.md` current: check items off, file new work by phase.
- Developer detail (state models, refresh timing, diagrams) lives in `dev-docs/`; `docs/`
  is user/store-facing.
- **The main view, widget and watch move together**: check a change to one against the
  others; they differ only where SPEC *One widget, many surfaces* says why.

## Engineering quality bar

`SPEC.md` *Engineering quality bar* is source of truth (in order: never show a departure
stopdash doesn't stand behind, never fail silently, do the work ahead of time, hold still
— never move what the user is reading or about to tap — jank-free UI, respect the
battery, say why); principles win over rules below:

- **Correctness**: matches `SPEC.md` and intent, preserves invariants, handles edge cases
  (no watched stops, TfL unreachable/rate-limited, location denied, no predictions, config
  change, process death). New behavior gets a unit test; a bug fix, one failing before.
- **Staleness contract**: never present stale data as live (SPEC D4). Network is never on
  a render path — render from persisted snapshot, refresh in background; surface PRs state
  what they render from and when they refresh.
- **Jank-free UI**: no I/O in composition; `remember`/`derivedStateOf`/stable types;
  screens appear at once (stamp/placeholder if not ready), never a delayed frame.
- **Main thread: read and dispatch only.** Composition, `remember`/`LaunchedEffect`, click
  handlers, and UI-called data/domain functions do no I/O and no work scaling with input
  (loop, filter, map, sort, parse, decode) before hopping to an injectable dispatcher
  ("usually fast" is no exemption: #483, #502). Test each from a single-thread caller
  that checks the work ran elsewhere. Mark data-scaling domain work `@WorkerThread`
  (lint's `WorkerThreadCall`; `app/lint-baseline.xml` only shrinks).
- **A worker's answer shows only for what it was worked out for**: `rememberWorked`
  (`Worked.kt`, `keep` for what may stand in), or a `produceState` answer kept as `Worked`
  with its key and read only while that key holds: `produceState` keeps its last value
  across keys, and a reset inside the producer still leaves a frame of it (#1036, #1044).
- **The list holds still through its worker**: its items' inputs live with its scroll
  position (`ListWork`, above the pages, never a `remember` in the list), and an answer
  standing in for older inputs still draws everything on screen; else it measures short
  for a frame and its position snaps. `ListReturnScrollTest` holds the worker on a return;
  `TapHoldsStillTest` steps it after a tap that opens part of the list. A new grouping,
  setting or tap gets a case in each.

Unable to verify locally (no device)? Say so ("verified by unit test; lock-screen placement
needs a device check").

## Spacing

4dp grid (`4`, `8`, `12`, `16`, `24`, …); reuse sibling values; symmetry by default
(justify asymmetry in one PR sentence). Flag off-grid or inconsistent spacing outside the
diff (file, line, fix) without fixing it in the same commit.

## Concise copy

Shortest unambiguous user-facing text ("3 min", not "3 minutes until departure");
justify longer phrasing in the PR.

## Icons

- **Use a standard Material icon by default**, not hand-drawn; ask if none or two fit.

## Line pill colors

Official TfL line color (tube by id, other single-color modes by mode) with APCA
black/white text (WCAG-2 AA flags, doesn't veto). The six named Overground lines are a
**hollow** pill (surface fill, line-color border + label, contrast-nudged accent), since
their colors collide with tube lines. Confirmed TfL hexes only (`SPEC.md`).

## Privacy

- **Never put user data in any artifact leaving this machine** (commits, PRs, replies,
  issues, branches, comments, fixtures, screenshots, logs): **coordinates, watched stops,
  home/work stops, the user's TfL `app_key`**. Stand-ins: `app_key=EXAMPLE`, synthetic
  `(51.5, -0.12)` for rider position.
- **Never include anyone's location without asking** (maintainer, 2026-09-27). Major
  stations/interchanges (King's Cross, Euston, Victoria, Waterloo, etc.) are fine with
  public TfL data/distances, **even from a bug report** (maintainer, 2026-09-30). Ask before
  adding a bus stop or neighborhood place. Nothing pointing to where the maintainer or a
  user lives, nothing else from anyone's device, location, starred stops, bug reports or
  screenshots (paraphrase; never a coordinate), no recurring neighborhood examples. Governs
  new work (existing data stays); ask in chat when unsure, never on GitHub.
- **On-device debug log (narrow exception)**: coarse diagnostics only — stop ID, line id,
  HTTP status, vehicle TfL terminus (never where the rider alights) — never coordinates or
  API keys, and no field without a failure it diagnoses. `docs/PRIVACY.md` lists what it
  carries about the **user** (stops, trips, places, position, devices) before shipping;
  app internals (counts, durations, error kinds, shipped-data reads) need no line
  (maintainer, 2026-10-13).
- **User-initiated, consent-gated bug report (second exception)** (maintainer, 2026-09-20):
  may carry **exact location**, per-stop distances, and a **reporting-screen screenshot**
  off-device, only after a consent screen the user can decline (`SPEC.md`,
  `docs/PRIVACY.md`); the location-redacted export stays separate. Recent positions come
  from bounded in-memory `RecentPositions` (15 min, 20 entries), never the persisted log.

## Error handling

- **Don't silently swallow exceptions.** No bare `catch (_: Throwable) {}`. Every catch
  **logs** the exception, redacted to its type and stack (a request URL in its message
  carries coordinates and the `app_key`), with sanitized context ("arrivals fetch failed:
  429 for stop <id>"), **cleans up** what `try` acquired (closeables, in-flight requests,
  partial UI state), and **handles the edge case explicitly** (a typed error the surface
  renders honestly, never a blank screen). Narrow the type or rethrow
  `CancellationException` first. Ignoring one on purpose: say why, still log at debug.

## Testing expectations

- Product logic lives in pure `:domain` (`app.stopdash.domain`), JVM-tested against
  **recorded TfL fixtures**, never the live API or a user's location (*Privacy*).
- Compose screens and Glance widgets get Robolectric + Roborazzi screenshot tests (add each
  new `*ScreenshotTest` class to `ci.yml`'s `--tests` allow-list), rendered under `en-rGB`
  in every class and method `qualifiers`.
- CI commits screenshot drift to same-repo PRs (`ci: refresh recorded screenshots`) and
  posts a before/after comment: `git pull` after a UI push before pushing again; don't
  hand-commit PNGs (fork PRs must). Rebasing CI's refresh commit unchanged (still by
  github-actions[bot]) isn't hand-committing; review it as CI's.
- Where the environment can't run `./gradlew test`/`lint`, state what was verified by
  inspection only. **Fix preexisting failures as the first commit** (*Autonomy*).
- **No sleeps, retries, or bumped timeouts** for flaky/racy tests — use an injected clock
  and test dispatcher. **Never disable a failing check.**

## Git workflow

- Assumes `origin` (without remote Git, commit locally and say nothing was pushed).
  Branches: `<agent>/<short-topic>` (`claude/…` Claude Code, `gemini/…` Gemini /
  Antigravity, `codex/…` Codex); one topic each, never `main`.
- **Your `<agent>/` branches are yours** — create, push, `--force-with-lease`, rename, or
  delete the ones this session created or was assigned (this file is the standing grant);
  don't fold unrelated work into a pinned branch. Any other branch or `main`: ask.
- **Sync before you start**: `git fetch origin +refs/heads/main:refs/remotes/origin/main`
  (a bare `git fetch origin main` leaves it stale) and rebase before the first commit,
  resolving conflicts; if unable to fetch, say the tree may be behind. One `git worktree`
  per branch when available.
- **One commit per logical change**, each green. Rewrite unmerged commits freely so review
  fixes fold into their commit with no `fix CI`/`wip` churn. `--force-with-lease` after
  rebase; never bare `--force` or force-pushing `main`.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene first:**
  fetch main by refspec, cut a fresh `<agent>/<short-topic>` off `origin/main` (unless
  sandbox-pinned), announce the switch; if unable to fetch, say follow-up needs a synced
  checkout.
- **Committer line**: merges rewrite the committer to the merger — expected; never
  re-author or amend merged commits, or narrate it. Not a finding.
- **Unshallow before history-depth queries** (`git rev-list --count`, versionCode): the
  session-start hook runs `scripts/unshallow.sh` in Claude Code only. If `git rev-parse
  --is-shallow-repository` is `true`, run it; if still `true`, say history is truncated.

## Commit messages

- **Title like a changelog line** (maintainer, 2026-10-23): an imperative verb, then the
  feature in the rider's plain words (no identifiers), sentence case, ≤ ~70 chars:
  `Fix …` (`Fix bus stop lists missing train stations`), `Improve …`, `Show …`/`Add …`; an
  optional parenthetical says how (`Fix National Rail stop lists (using National Rail
  instead of TfL data)`). Scenario and mechanism go in the body. PR titles and
  release-note bullets match. Every subject lands in release notes (Play "What's new",
  GitHub prerelease `v<versionCode>`): title **every** commit for users.
- **Prefix a subject with no user-visible effect**: `ci:`, `docs:` (docs/`*.md`, including
  `docs/PRIVACY.md`), `todo:` (`TODO.md` alone), `internal:` (build/plumbing; a shipped
  dependency is bare), `refactor:`, `test:`/`tests:`. Bare means a user could notice. The
  docs lane accepts only `docs` and `todo` (`.github/lanes.conf`).
- **Play caps "What's new" at 500 characters** (`deploy` drops the rest): squash supporting
  commits into the user-facing one. Commits touching only `.md` files or root dot-paths
  (`.github/`, `.gitignore`, …) never ship a bullet.
- **`scripts/publish-github-release.sh` is byte-identical across the four sibling Android
  repos**; change it in all four.
- **No model identifier** — never name the coding agent's model or version (`Opus 4.8`,
  `Sonnet`, `Gemini …`, `claude-*`, `gemini-*`) in a commit, PR, comment, or pushed
  artifact; keep it to chat. Generic attribution (`Co-Authored-By: Claude` / `Gemini`,
  `Claude-Session:`, "Generated with Claude Code") is fine.

## Autonomy

- **Open the PR without being asked** when a branch is ready (this file is the owner's
  standing request), unless told "just commit / no PR yet".
- **Watch your own PRs by subscription + one scheduled check.** Subscribe on open
  (automatic in Claude Code, or `subscribe_pr_activity` where `claude-code-remote` exposes
  it; Antigravity relies on the check alone). Subscriptions miss CI pass, merges and
  Codex `+1`, so keep one relative-delay check armed while open (`send_later` in Claude
  Code; `schedule` with `DurationSeconds` in Antigravity, canceled via `manage_task`
  `kill`). **Read `mergeable_state` at every check, wake, and drive step**: if `dirty` or
  required `behind`, fetch base and head by refspec (unshallowing), confirm your branch
  has every remote head commit, rebase, confirm the head hasn't moved since the tip you
  noted before fetching (ask if unsure), and `--force-with-lease` unasked (+1'd head
  included). Settle a fired check first; end each turn with one check naming the PR (not
  a SHA). On merge/close, one last reply-and-resolve pass (follow-up PR for real work),
  then cancel the check and unsubscribe.
- **A scheduler, GitHub, or `git push` prompt is said once, then carry on.**
- **"Drive"**: loop through actionable `TODO.md` items (not deferred or awaiting a product
  decision) — implement, open PR, address Codex, merge when CI is green and Codex +1s the
  head, repeat until done or stopped. **"Autopilot"**: drive without blocking, logging
  each best reversible guess under `TODO.md` *Decisions needing review*; destructive or
  irreversible acts outside the loop, anything beyond this repo, and privacy uncertainty
  still wait for a real answer.
- **Ordered work ("next", "then", "after that", "follow-up")** may open in parallel but
  merges after every earlier session item and named PR (any repo, no time cap; hold and
  ask if one stalls, autopilot included; no auto-merge until its turn; check the record).
- **Wake PRs a merge leaves behind.** First turn after your PR merges, message every other
  open PR on that base whose body ends with a session URL: name only that PR, saying its
  base now points at `<sha>`, whatever its state reads (name no merge or other PR). On
  receipt, fetch base and head by refspec (unshallow) and test against the fetched base tip,
  not `<sha>`: `behind` if `git merge-base --is-ancestor <tip> <head>` exits 1, `dirty` if
  `git merge-tree --write-tree` (Git 2.38+) exits 1 (report any other exit). Skip if no
  session URL or local-only messaging; don't retry failed sends.
- **A red baseline is the next task** — fix `./gradlew test`/`lint` before pulling from
  `TODO.md` (skipping as unrelated needs the user's answer, never an autopilot guess).

## Working with PRs

- Prefer `mcp__github__*` tools where mounted (Claude Code requires them); else `gh`.
- **Codex is the automated reviewer** (not Copilot). Address every comment — fix and fold
  into its commit, or decline with reasons — then push, reply citing the sha, and resolve
  (`resolve_review_thread` or `gh api graphql` `resolveReviewThread`, `PRRT_*` not
  `PRRC_*`). Never silently dismiss. Codex's +1 gates
  merge (a rebuttal or `TODO.md` entry can still earn it; fixing is often quicker).
- **Defer out-of-scope findings**: push a `TODO.md` entry, reply with that sha, resolve.
- **Judge comments on merit.** Check what a cited rule actually says; over-strict privacy
  readings quietly cost capability. Rule-vs-need conflicts go to the maintainer.
- **A second verified finding in one mechanism is a design question**: would another
  design delete the class? (The maintainer's call.)
- **Read the Codex verdict on the PR body** (`eyes` = reading, `+1` = clean, revoked on
  push; `+1` + green CI = merge), and `get_review_comments`, `get_comments`, `get_reviews`
  to the last page. A pending `codex` status with nothing left to address gets `@codex
  review` 5 min after the last review/rebuttal/push, as does a review stuck at running (👀)
  10 min; a poke that hangs too gets a second an hour later, and only a second that hangs is
  escalated, never the first (maintainer, 2026-10-22). A security review stuck at running
  holds no merge: a clean code review plus green CI merges (maintainer, 2026-10-23).
- **Once Codex +1s a head, make no unnecessary change without asking** (maintainer,
  2026-10-13): tidy-ups wait for a yes or a follow-up PR; required rebases, red-CI fixes
  and drive/autopilot steps proceed unasked.
- **Report each review in your next message**: commit read, finding count, a bullet per
  finding (most blocking first: claim, location, action). Update PR title/body before each
  push (body first) to the full branch diff; with no PR yet, push, then do it once opened.
- **Link every open PR in the stack, one URL per line.**
- **After every merge to `main`, report versionCode** (`git rev-list --count origin/main`,
  refspec-fetched): `Need versionCode 72 (b81c23d) or higher to test PR #52's fix`.
- **"Release notes since N"**: up to five `• ` end-user bullets worded as titles, one per
  feature (read bodies where a subject is unclear), in one code block for commits
  N+1..`origin/main` (freshly fetched). **Rank by what the rider risks without it**
  (maintainer, 2026-10-23): wrong information that could send them the wrong way first
  (`Fix National Rail stop lists`), polish last. Skip prefixed/docs/CI commits, dependency
  bumps unless nothing else changed (then one bullet), and anything the range added then
  undid; range above, one line below naming what was omitted.
- **On CI failure**, read the failed step log first (artifacts only hold `:app`); reproduce
  the PR merged with `main` in a throwaway `git worktree`; check whether `main` is red.
- Skip your own webhook echoes silently.

## Talking to the user

- **One question at a time**, in the client's question tool (`AskUserQuestion` in Claude
  Code, `ask_question` in Antigravity; else plain chat), carrying its own context (what,
  why, each option's cost) so it answers without scrolling back (maintainer, 2026-10-23).
  Stop and wait; acknowledge the answer before acting. Never ask mid-typing.
- **Ask only a real product trade-off** (maintainer, 2026-09-30), its premise checked in
  code first; fix clear bugs and fetch feature data through existing caches unasked.
- **Answer a mid-turn message first**, before any further tool call (except merge cues,
  whose hygiene runs first). **Claude Code only** (maintainer, 2026-10-25): its app reliably
  shows just a turn's last message, so reply only there, answering a mid-turn message at a
  stopping point soon.
- **Message only when the user needs to see it** (maintainer, 2026-10-15): each notifies
  their phone. Speak up for a question/blocker, something to merge or act on, a merge, an
  autopilot decision (to review when seen, never waited on), or a change from the plan,
  `SPEC.md` or their instructions (review-prompted included). Never for progress (a
  client's nudge doesn't count), a review alone (rides the next message), routine
  machinery, or a mistake fixed before reaching the repo. The session-start `AGENTS.md`
  line still opens every session.
- **Keep replies short**; lead with the key point.
- **A pending decision stays answerable**: asked in the tool, don't repeat it; asked in
  chat, restate it whole (context and options, never a bare one-liner) as the last line of
  a turn that speaks anyway, never a message just to repeat it (maintainer, 2026-10-23:
  one-liners buried the context). Nothing pending, no line.

## Language and spelling

US English everywhere (base strings, commits, PRs, comments, KDoc, identifiers, docs).
Users are in the UK, so **en-GB is first-tier**: a user-facing string whose British
spelling/usage differs ("favorites", "licenses", "percent") gets its
`values-en-rGB/strings.xml` override in the same change (`EnGbStringsTest`). Watch:
`gray`/`grayed`/`grays`, `canceled`/`canceling` (one `l`), `color`, `behavior`, `dialog`,
`-ize` over `-ise`, `license`, `center`, `labeled`, `traveling`. Framework
(`CancellationException`) and TfL names keep their own spellings.

## Remote build environments

- `.claude/hooks/session-start.sh` provisions the SDK at `/opt/android-sdk` (auto-invoked
  only in Claude Code); if it's empty in a remote container, run
  `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` and export `ANDROID_HOME` and
  `ANDROID_SDK_ROOT` as the path it reports (only Claude Code keeps its env); don't
  hand-install.
- No KVM, so no emulator: lock-screen, widget, watch and location behavior needs a device.
- Before concluding the sandbox can't build: `ls /opt/android-sdk`, probe
  `https://maven.google.com/` (403 = blocked).

**Translations** (SPEC *Language*): a new user-facing string ships English-only, with
`<!-- TODO: translate -->` and `tools:ignore="MissingTranslation"`, for the maintainer to
confirm on a device; the translations pass (`TODO.md`, `dev-docs/translations.md`) fills
every `values-<lang>` and drops both marks. A string whose meaning changes deletes its
stale translations and takes the same marks.

## Cost and reliability

Call out cost and reliability up front for new infrastructure or an external call (dollar
figure, failure modes, rate limits, user-visible fallback; say so if nil). The one
dependency (TfL Unified API) is free (~50 req/min keyless, ~500 with a user key); anything
new leaving the device is a distribution/privacy decision, named with its dollar figure
and Play Data Safety consequence. Battery is the running cost: state any new wakeup,
location request, or refresh interval.

## CI timing

Report a significant CI slowdown (>25% or >30 s on a job under ~5 min) with its likely
cause, comparing the same job on the same kind of ref (`main` does release work a PR
skips).
