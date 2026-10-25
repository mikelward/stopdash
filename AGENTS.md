---
trigger: always_on
alwaysApply: true
last_modified: 2026-10-25
---

# AGENTS.md

`CLAUDE.md` and `GEMINI.md` are symlinks to this file. Edit `AGENTS.md` and bump `last_modified`.

**At the start of every session, print the full absolute path of the `AGENTS.md` you
loaded and its `last_modified` date**, so a stale or wrong copy is caught first.

StopDash is an Android app (Kotlin + Compose; `:app`, pure-Kotlin `:domain`, `:shared`,
Wear OS `:wear`) showing live TfL departures on lock screen, home screen, watch, and app.
Product/architecture decisions live in `SPEC.md`, phased plan in `TODO.md`. Mirrors
`mikelward/simmo` (tiebreaker), `snoozemo`, and `typelauncher`.

**Prefer a shared `mikelward/*` library over an app-local copy** (unless told otherwise).

**`./gradlew test` and `./gradlew lint` must pass before every push.**

Keep this file short — one-liners over essays, **under 24,000 bytes** (`wc -c AGENTS.md`):
Antigravity caps rule files at 24 KB and silently truncates the tail. State the rule and
the failure it prevents; incident narratives belong in commits. Add a rule the first time
something bites, rewrite/trim rather than append, and delete rules that stopped biting.

## Project documentation

- Keep `SPEC.md` current when changing behavior, architecture, persistence, permissions,
  navigation, or testing. Records *what* and *why*, not low-level detail ("still true if
  rewritten?"; if not, code comment).
- Keep `TODO.md` current: check items off as they land, add new work to the right phase.
- Developer detail (state models, refresh timing, diagrams) lives in `dev-docs/`; `docs/`
  is user/store-facing.
- **The main view, widget and watch move together**: check any change to one for whether it
  applies to the others; they differ only where SPEC *One widget, many surfaces* says why.

## Engineering quality bar

`SPEC.md` *Engineering quality bar* is source of truth (in priority order: never show a
departure stopdash doesn't stand behind, never fail silently, do the work ahead of time,
hold still — never move what the user is reading or about to tap — jank-free UI, respect
the battery, say why); principles win over rules below:

- **Correctness**: matches `SPEC.md` and intent; handles edge cases (no watched stops, TfL
  unreachable/rate-limited, location denied, no predictions, config change, process death);
  preserves invariants. New behavior gets a unit test; bug fixes get a test failing before
  and passing after.
- **Staleness contract**: never present stale data as live (SPEC D4). Network is never on
  a render path — render from persisted snapshot and refresh in background. Surface PRs
  state what they render from and when they refresh.
- **Jank-free UI**: render from snapshot/in-memory state; no I/O in composition; standard
  Compose discipline (`remember`/`derivedStateOf`/stable types). Screens appear at once
  with a stamp/placeholder if not ready, never a delayed first frame.
- **Main thread: read and dispatch only.** Composition, `remember`/`LaunchedEffect`, click
  handlers, and UI-called data/domain functions do no I/O and no work scaling with input
  (loop, filter, map, sort, parse, decode) before hopping to an injectable dispatcher:
  read precomputed state and hand off, hop first ("usually fast" is no exemption: #483,
  #502). Test each from a single-thread caller that checks the work ran elsewhere. Mark
  data-scaling domain work `@WorkerThread` (enforced by lint's `WorkerThreadCall`;
  `app/lint-baseline.xml` only shrinks).
- **A worker's answer shows only for what it was worked out for**: `rememberWorked`
  (`Worked.kt`, `keep` for what may stand in), or a `produceState` answer kept as `Worked`
  with its key and read only while that key holds. `produceState` keeps its last value across
  new keys, and a reset inside the producer still leaves a frame of the old one (#1036, #1044).
- **The list holds still through its worker**: what its items are worked out from lives with its scroll
  position (`ListWork`, above the pages, never a `remember` in the list), and an answer standing in for
  older inputs still draws everything on screen; else it measures short for a frame and its position
  snaps. `ListReturnScrollTest` holds the worker on a return; `TapHoldsStillTest` steps it
  after a tap that opens part of the list. A new grouping, setting or tap gets a case in each.

When unable to verify locally (no emulator/device), say so in chat ("verified by unit test;
lock-screen placement needs a device check").

## Spacing

4dp grid (`4`, `8`, `12`, `16`, `24`, …); reuse sibling values; symmetry by default
(justify asymmetry in one PR sentence). Flag off-grid or inconsistent spacing outside the
diff (file, line, fix) without fixing it in the same commit.

## Concise copy

Shortest unambiguous user-facing text ("3 min", not "3 minutes until departure";
"Victoria line: severe delays", not a paragraph); justify longer phrasing in the PR.

## Icons

- **Use a standard Material icon by default** (Material Icons/Symbols), not hand-drawn;
  ask if none or two fit.

## Line pill colors

Official TfL line color (tube by id, other single-color modes by mode) with APCA
black/white text (WCAG-2 AA is a flag, not a veto). The six named Overground lines resolve
by id as a **hollow** pill (surface fill, line-color border + label, contrast-nudged
accent) since their colors collide with tube lines. Confirmed TfL hexes only (`SPEC.md`).

## Privacy

- **Never put user data in any artifact leaving this machine** (commits, PRs, replies,
  issues, branches, comments, fixtures, screenshots, logs): **coordinates, watched stops,
  home/work stops, and the user's TfL `app_key`**. Use stand-ins (`app_key=EXAMPLE`,
  synthetic `(51.5, -0.12)` for rider position).
- **Never include anyone's location without asking** (maintainer, 2026-09-27). Major
  stations/interchanges (King's Cross, Euston, Victoria, Waterloo, Tottenham Court Road,
  etc.) are fine with public TfL data/distances, **even from a bug report** (maintainer,
  2026-09-30). Ask before adding a bus stop or neighborhood place. Never add data pointing
  to where the maintainer or a user lives: nothing else from anyone's device, location,
  starred stops, bug reports or screenshots (paraphrase; never a coordinate), and no
  recurring neighborhood examples. Governs new work (existing data isn't rewritten); ask in
  chat when unsure, never on GitHub.
- **On-device debug log (narrow exception)**: coarse diagnostics only — stop ID, line id,
  HTTP status, vehicle TfL terminus (never where the rider alights) — never coordinates or
  API keys, and no field without a failure it diagnoses. `docs/PRIVACY.md` must list what
  the log carries about the **user** (stops, trips, places, position, devices) before
  shipping; app internals (counts, durations, error kinds, shipped-data reads) need no
  line (maintainer, 2026-10-13).
- **User-initiated, consent-gated bug report (second exception)** (maintainer, 2026-09-20):
  may carry **exact location**, per-stop distances, and a **reporting-screen screenshot**
  off-device, disclosed on a consent screen the user can decline (`SPEC.md`,
  `docs/PRIVACY.md`; nothing leaves without consent); the location-redacted export stays
  separate. Recent positions come from bounded in-memory `RecentPositions` (15 min, 20
  entries), never the persisted log.

## Error handling

- **Don't silently swallow exceptions.** No bare `catch (_: Throwable) {}`. Every catch
  **logs** the exception, redacted to its type and stack (a request URL in its message
  carries coordinates and the `app_key`), with sanitized context (*Privacy* applies —
  "arrivals fetch failed: 429 for stop <id>", never a coordinate/key), **cleans up** what
  `try` acquired (closeables, in-flight requests, partial UI state), and **handles the
  edge case explicitly** (a typed error the surface renders honestly, per SPEC principle 2
  — never blanking the screen). Narrow the type or rethrow `CancellationException` first.
  To ignore a failure on purpose, state why in a one-line comment and still log at debug.

## Testing expectations

- Product logic lives in pure `:domain` (`app.stopdash.domain`), JVM-tested against
  **recorded TfL fixtures**, never the live API or a user's location (hubs/interchanges are
  fine; bus stops or neighborhood places need the maintainer's OK, per *Privacy*).
- Compose screens and Glance widgets get Robolectric + Roborazzi screenshot tests in
  `.github/workflows/ci.yml` (add each new `*ScreenshotTest` class to CI's `--tests`
  allow-list). Render under `en-rGB` in every class and method `qualifiers`.
- CI commits screenshot drift back to same-repo PRs (`ci: refresh recorded screenshots`) and
  posts a before/after comment: `git pull` after a UI push before pushing again; don't
  hand-commit PNGs (fork PRs must). A rebase that carries CI's refresh commit unchanged
  (still authored by github-actions[bot]) isn't hand-committing; review it as CI's.
- Run `./gradlew test` and `./gradlew lint` before pushing when the environment can;
  otherwise state what was verified by inspection only.
- **Fix preexisting test failures as the first commit** (confirm before skipping if
  genuinely unrelated).
- **No sleeps, retries, or bumped timeouts** for flaky/racy tests — use an injected clock
  and test dispatcher. **Never disable a failing check.**

## Git workflow

- Assumes `origin` (without remote Git, commit locally and state nothing was pushed).
  Feature branches: `<agent>/<short-topic>` (`claude/…` Claude Code, `gemini/…` Gemini /
  Antigravity, `codex/…` Codex); one topic per branch, never `main`.
- **Your `<agent>/` branches are yours** — create, push, `--force-with-lease`, rename, or
  delete the ones this session created or was assigned, freely (this file is the standing
  grant); don't fold unrelated work into a pinned branch. Any other branch or `main`
  requires asking.
- **Sync before you start**: `git fetch origin +refs/heads/main:refs/remotes/origin/main`
  (`git fetch origin main` leaves single-branch `origin/main` stale) and rebase before the
  first commit, resolving conflicts rather than abandoning the rebase. If unable to fetch,
  say the tree may be behind.
- **Use `git worktree` when available**, one per branch.
- **One commit per logical change**, each green. Rewrite unmerged commits freely (amend,
  `--fixup` + autosquash, squash, reorder, split) so review fixes fold into their commit
  with no `fix CI`/`wip` churn. `--force-with-lease` after rebase; never bare `--force` or
  force-pushing `main`.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene first:**
  fetch main by refspec, cut a fresh `<agent>/<short-topic>` off `origin/main` (unless
  sandbox-pinned), and announce the switch; if unable to fetch, say follow-up needs a
  synced checkout rather than branching off a stale `origin/main`.
- **Committer line**: rebase/squash merges rewrite the committer to the merger — expected;
  never re-author or amend merged commits, or narrate it. It is not a finding.
- **Unshallow before history-depth queries** (`git rev-list --count`, versionCode): the
  session-start hook runs `scripts/unshallow.sh` in Claude Code only. If `git rev-parse
  --is-shallow-repository` is `true`, run it and re-check; if still `true`, say history is
  truncated rather than quote a versionCode.

## Commit messages

- **Title like a changelog line** (maintainer, 2026-10-23): an imperative verb, then the
  feature in the rider's plain words (no identifiers), sentence case, ≤ ~70 chars, e.g.
  `Fix …` for what was broken (`Fix bus stop lists missing train stations`), `Improve …`
  (`Improve "Get off soon" notification timing`), `Show …`/`Add …` for what's new; an optional
  parenthetical says how (`Fix National Rail stop lists (using National Rail instead of
  TfL data)`). Never the scenario or mechanism (that's the body). PR titles and
  release-note bullets match. Rebase-merges land every subject on `main` and in release
  notes (Play "What's new", GitHub prerelease `v<versionCode>`), so title **every** commit
  for end users.
- **Prefix a subject with no user-visible effect**: `ci:`, `docs:` (docs/`*.md`, including
  `docs/PRIVACY.md`), `todo:` (`TODO.md` alone), `internal:` (build/plumbing; a shipped
  dependency is bare), `refactor:`, `test:`/`tests:`. A bare subject means a user could
  notice. `docs` and `todo` are the two prefixes the docs lane accepts (`.github/lanes.conf`).
- **Play caps "What's new" at 500 characters** (`deploy` drops subjects past it), so squash
  supporting commits into the one telling the user-facing story. Commits touching only
  `.md` files or root dot-paths (`.github/`, `.gitignore`, …) never ship a bullet.
- **`scripts/publish-github-release.sh` is byte-identical across the four sibling Android
  repos**; change it in all four.
- **No model identifier** — never name the coding agent's model or version (`Opus 4.8`,
  `Sonnet`, `Gemini …`, `claude-*`, `gemini-*`) in a commit, PR, comment, or pushed
  artifact; keep it to chat. Generic agent attribution (`Co-Authored-By: Claude` / `Gemini`,
  `Claude-Session:`, "Generated with Claude Code") has no model name/version and is fine.

## Autonomy

- **Open the PR without being asked** when a branch is ready (this file is the repo owner's
  standing request for that PR), unless told "just commit / no PR yet".
- **Watch your own PRs by subscription + one scheduled check.** Subscribe on open
  (automatic in Claude Code, or `subscribe_pr_activity` where `claude-code-remote` exposes
  it; a client with neither, like Antigravity, relies on the scheduled check alone).
  Because subscriptions miss CI pass, merges, and Codex `+1`, keep one relative-delay
  check armed while open (`send_later` in Claude Code; `schedule` with `DurationSeconds`
  in Antigravity, canceled via `manage_task` `kill`). **Read `mergeable_state` at every
  check, wake, and drive step**: if `dirty` or required `behind`, fetch base and head by
  refspec (unshallowing), confirm your branch has every remote head commit, rebase,
  confirm the head hasn't moved since the tip you noted before fetching (ask if unsure),
  and `--force-with-lease` push unasked (+1'd head included). Settle a fired check first
  and end each turn with one check naming the PR (not a SHA). On merge/close, do one last
  reply-and-resolve pass (follow-up PR for real work), then cancel the check and
  unsubscribe, if subscribed.
- **A scheduler, GitHub, or `git push` prompt is said once, then carry on.**
- **"Drive"**: loop through actionable `TODO.md` items (not deferred or awaiting a product
  decision) — implement, open PR, wait for Codex, address comments, merge when CI is green
  and Codex +1s the head, repeat until done or stopped. **"Autopilot"**: drive without
  blocking, taking the best reversible guess and logging it under `TODO.md` *Decisions
  needing review*; still wait for a real answer on destructive or irreversible actions
  outside the loop, anything reaching beyond this repo, or privacy uncertainty.
- **Ordered work ("next", "then", "after that", "follow-up")** may open in parallel but
  merges in order after every earlier session item and named PR merges (any repo, no time
  cap; hold and ask if one stalls, autopilot included; no auto-merge until its turn; check
  the session record).
- **Wake PRs a merge leaves behind.** First turn after your PR merges, message every other
  open PR on that base whose body ends with a reachable session URL: name only that PR and
  say its base now points at `<sha>`, whatever its state reads (name no merge or other PR).
  On receipt, fetch base and PR head by refspec (unshallow if needed) and test against the
  fetched base tip, not `<sha>`: `behind` if `git merge-base --is-ancestor <tip> <head>`
  exits 1, `dirty` if `git merge-tree --write-tree` (Git 2.38+) exits 1 (report any other
  exit); rebase per your rules. Skip if no session URL or local-only messaging; don't retry
  failed sends.
- **A red baseline is the next task** — fix `./gradlew test`/`lint` before pulling from
  `TODO.md` (confirm with the user before skipping as unrelated, never an autopilot guess).

## Working with PRs

- Prefer `mcp__github__*` tools where mounted (Claude Code requires them); otherwise fall
  back to `gh`.
- **Codex and Claude review every PR automatically** (not Copilot; Claude posts as
  github-actions[bot], marking findings 🔴 blocking, 🟡 nit, 🟣 pre-existing). Address every
  comment — fix and fold into its commit, or reply declining with reasons — then resolve the
  thread (`resolve_review_thread` or `gh api graphql` `resolveReviewThread` takes `PRRT_*`, not
  `PRRC_*`; push fix, reply citing sha, resolve). Never silently dismiss. Codex's +1 gates
  merge, Claude's review doesn't (a decline can still earn it with a rebuttal or `TODO.md`
  entry, though fixing is often quicker).
- **Defer out-of-scope findings** by pushing a `TODO.md` entry, replying with that sha, and
  resolving.
- **Judge comments on merit.** Check what a cited rule actually says; over-strict privacy
  readings quietly cost needed capability. Genuine rule-vs-need conflicts go to the
  maintainer.
- **A second verified finding in the same mechanism is a design question**, not another
  bug: ask whether a different design deletes the class; a design change is the
  maintainer's call.
- **Read the Codex verdict on the PR body** (`eyes` = reading, `+1` = clean, revoked on
  push; `+1` + green CI = merge). Read `get_review_comments`, `get_comments`, and
  `get_reviews` to the last page. A pending `codex` status with nothing left to address
  gets an `@codex review` poke 5 min after the last review/rebuttal/push, as does a review
  stuck at running (👀) 10 min; a poke that hangs the same way gets a second an hour later,
  and only a second that hangs too is escalated, never the first (maintainer, 2026-10-22).
  A security review stuck at running holds no merge: a clean code review and green CI with
  nothing else blocking merge without it (maintainer, 2026-10-23).
- **Once Codex +1s a head, make no unnecessary change without asking** (maintainer,
  2026-10-13): tidy-ups wait for a yes or a follow-up PR; required rebases (`dirty`/`behind`),
  red-CI fixes, and drive/autopilot steps proceed unasked.
- **Report each review in your next message** (*Talking to the user*): commit read, finding
  count, one bullet per finding (most blocking first: claim, location, action). Update PR
  title/body before each push (body first, then push) to match the full branch diff; with
  no PR yet, push, then do it as soon as one is opened.
- **Link every open PR in the stack, one URL per line.**
- **After every merge to `main`, report versionCode** (`git rev-list --count origin/main`
  after refspec fetch): `Need versionCode 72 (b81c23d) or higher to test PR #52's fix`.
- **"Release notes since N"**: up to five `• ` end-user bullets worded as titles are
  (*Commit messages*), one per feature however many commits built it (read bodies where a
  subject is unclear), in one code block for commits N+1..`origin/main` (freshly fetched).
  **Rank by what the rider risks without it** (maintainer, 2026-10-23): wrong information
  that could send them the wrong way first (`Fix National Rail stop lists`), polish last
  (`Improve UI transitions when swiping back`), however many commits each took. Skip
  prefixed/docs/CI commits, dependency bumps unless nothing else changed (then one bullet
  says libraries were updated), and anything the range itself added then undid; range
  above, one line below naming what was omitted.
- **On CI failure**, read the failed step log first (artifacts only hold `:app`); reproduce
  the PR merge with `main` (what CI builds) in a throwaway `git worktree`, and check
  whether `main` is already red.
- Skip your own webhook echoes silently.

## Talking to the user

- **One question at a time**, in the client's question tool (`AskUserQuestion` in Claude
  Code, `ask_question` in Antigravity; plain chat only without one), carrying its own context
  (what, why, each option's cost) so it answers without scrolling back (maintainer,
  2026-10-23). Stop and wait; acknowledge the answer before acting. Never ask mid-typing.
- **Ask only a real product trade-off** (maintainer, 2026-09-30). Fix clear bugs and fetch
  feature data through existing caches without asking. Check a question's premise in code
  first.
- **Answer a mid-turn message first**, before any further tool call (except merge cues,
  whose hygiene runs first).
- **Message only when the user needs to see it** (maintainer, 2026-10-15): each notifies
  their phone. Work in silence; speak up for a question/blocker, something to merge or act
  on, a merge, an autopilot decision (to review when seen, never waited on), or a change
  from the plan, `SPEC.md` or their instructions (review-prompted included). Never for
  progress (a client's nudge doesn't count), a review alone (rides the next message),
  routine machinery, or a mistake caught and fixed before reaching the repo. The
  session-start `AGENTS.md` line still opens every session.
- **Keep replies short**; lead with the single most important point.
- **A pending decision stays answerable**: asked in the tool, don't repeat it; asked in chat,
  restate it whole (context and options, never a bare one-liner) as the last line of a turn
  that speaks anyway; a routine event never gets a message just to repeat it (maintainer,
  2026-10-23: one-liners buried the context). Nothing pending, no line.

## Language and spelling

US English everywhere (base strings, commits, PRs, comments, KDoc, identifiers, docs).
Because users are in the UK, **en-GB is first-tier**: any user-facing string whose British
spelling/usage differs ("favorites", "licenses", "percent") gets its
`values-en-rGB/strings.xml` override in the same change (`EnGbStringsTest`). Watch:
`gray`/`grayed`/`grays`, `canceled`/`canceling` (one `l`), `color`, `behavior`, `dialog`,
`-ize` over `-ise`, `license`, `center`, `labeled`, `traveling`. Framework
(`CancellationException`) and TfL line/place names keep their own spellings.

## Remote build environments

- `.claude/hooks/session-start.sh` provisions the Android SDK at `/opt/android-sdk` (only
  auto-invoked in Claude Code); if `/opt/android-sdk` is empty in a remote container, run
  `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` (then export `ANDROID_HOME` and
  `ANDROID_SDK_ROOT` as the path it reports ready: only Claude Code keeps the hook's
  environment), don't hand-install.
- No KVM, so no emulator: lock-screen, widget, watch and location behavior needs a device.
- Check before concluding the sandbox can't build: `ls /opt/android-sdk` and an HTTP probe
  of `https://maven.google.com/` (403 = blocked).

**Translations** (SPEC *Language*): a new user-facing string ships in English only, under a
`<!-- TODO: translate -->` comment and `tools:ignore="MissingTranslation"`, for the
maintainer to confirm on a device; the translations pass (`TODO.md`) then fills every
`values-<lang>` using `dev-docs/translations.md` and drops both marks. A string whose
meaning changes deletes its now-stale translations and takes the same marks.

## Cost and reliability

Call out cost and reliability up front when adding infrastructure or an external call
(dollar figure, failure modes, rate limits, user-visible fallback; say so if nil).
StopDash's one dependency (TfL Unified API) is free (£0; ~50 req/min keyless, ~500 with
user key) — anything new leaving the device is a distribution/privacy decision, named with
its dollar figure and Play Data Safety consequence. Battery is the running cost: state any
new wakeup, location request, or refresh interval as one.

## CI timing

Report a significant CI slowdown (>25% or >30 s on a job under ~5 min) with its likely
cause, comparing the same job on the same kind of ref — PR against PR, `main` against
`main` (`main` does release work a PR skips).
