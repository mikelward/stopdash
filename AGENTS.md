---
trigger: always_on
alwaysApply: true
last_modified: 2026-11-07
---

# StopDash

An Android app (Kotlin, Compose; `:app`, pure-Kotlin `:domain`, `:shared`, Wear OS
`:wear`) showing live TfL departures on the lock screen, home screen, watch and app.
`SPEC.md` holds the decisions, `TODO.md` the plan, `dev-docs/` the developer detail.
Mirrors `mikelward/simmo` (the tiebreaker), `snoozemo` and `typelauncher`.

## Fleet conventions

Shared by every mikelward repo and the user-level agent config; canonical in mikelward/conf
`agents/AGENTS.md`. A repo's Project section overrides it.

### This file

- **Session start**. At the start of every session, print the full absolute path of the
  `AGENTS.md` you loaded and its `last_modified` date.
- **Canonical**. AGENTS.md is canonical; `CLAUDE.md` and `GEMINI.md` point to it. Keep it
  brief, generic to all agents (noting per-agent exceptions), and under 22,000 bytes.
  Bump `last_modified` whenever you edit this file.

### Talking to the user

- **Questions**. One at a time; wait for the answer.
- **Messages**. Send one only for a question, a blocker, something to act on, or a change
  of plan. No progress reports or narrating events.
- **Claude Code**. It reliably shows only a turn's last message; put everything there.
- **Replies**. Short, key point first, ending with open PR links and any pending
  question.
- **Network access**. If you want access to a host and you can't access it or a mirror,
  ask the user to allowlist the domain(s) along with the purpose.

### Git

- **Branches**. `<agent>/<topic>` off a fresh `origin/main` (fetch by refspec). Never
  commit to `main`.
- **Your branches**. Push, force-push with lease, and delete them freely. Ask before
  touching others', `main`, settings, secrets or paid services.
- **Commits**. One per logical change; fold in review fixes.
- **Subjects**. Changelog lines: imperative, the user-visible change, ≤ 70 chars. PR
  titles match.
- **Prefixes**. `docs:`, `test:`, `build:`, `refactor:` mark what users won't notice. No
  `feat:` or `fix:`.
- **Model names**. Never in anything pushed.
- **After a merge**. Branch afresh.
- **Shallow clones**. Unshallow before trusting history or calculating a version code.
- **Language**. US English everywhere.

### Pull requests

- **Opening**. Open the PR unasked; update its title and body before each push.
- **Watching**. PR events plus one scheduled check (events miss CI success and Codex's
  👍).
- **Auto-merge**. Enable it on PRs that target `main`; stack dependent ones.
- **Base moved**. After a merge, tell open PRs on that base whose body ends with a session
  URL.
- **Merge or close**. Answer remaining threads and stop watching.

### Reviews

- **Codex**. It reviews automatically. Fix, decline with a reason, or defer to `TODO.md`
  every comment; reply citing the commit, then resolve.
- **Merit**. Codex over-reads rules, privacy especially: quote and decline rather than
  cripple the code. Design changes are the user's call.
- **Verdict**. 👍 is clean, 👀 is reviewing. Only the code review gates merging: its
  findings (review comments, PR comments, reviews) block it. Security reviews often
  hang; don't wait on them.
- **Hung review**. `@codex review` after five quiet minutes, again an hour later, then
  tell the user.
- **After a 👍**. Ask before unneeded changes.
- **Reports**. Give each review's findings in your next message.

### Autonomy

- **Fix proactively**. Lint, test, presubmit, CI and review failures, unasked.
- **Proactive refactors**. Repeated bugs in one area, duplicated code, or a change with
  twins elsewhere: suggest the redesigned or shared version.
- **Drive to merge**. Once the details are agreed, see the change through unattended:
  implement it, open the PR, keep it mergeable (fix CI, answer reviews, rebase when it
  conflicts or falls behind), and merge once CI is green and Codex 👍s, or let
  auto-merge.
- **Drive**. Take `TODO.md` items one at a time and drive each to merge, until done or
  stopped.
- **Autopilot**. Drive without asking, logging guesses in `TODO.md` under *Decisions
  needing review*. Still ask before anything irreversible, outside this repo, or private.
- **Red baseline**. Fix existing failures first, in their own commit.

### Quality

- **Tests**. Every behavior change gets one; a bug fix's fails first. Assert behavior,
  not structure. No sleeps or retries; never disable a failing check.
- **Docs**. Keep `SPEC.md` and `README.md` current.
- **CI**. Wait for it before claiming success.
- **Errors**. Never swallow one; log it with context and clean up.
- **Cost**. State the cost and reliability of new dependencies or services up front.
- **Privacy**. No user data (personal paths, hostnames, tokens, keys, private remotes)
  leaves this machine; use placeholders.
- **Portability**. Flag anything that won't run on Linux and macOS.

## Project

### Principles, in order

`SPEC.md` *Engineering quality bar* has them in full; they win over the rules below.

1. Never show a departure stopdash doesn't stand behind.
2. Never fail silently.
3. Do the work ahead of time.
4. Hold still: never move what the user is reading or about to tap.
5. Jank-free UI.
6. Respect the battery.
7. Say why.

### Rules

- **Project documentation**. `SPEC.md` records what and why, not implementation detail;
  keep `TODO.md` current. Developer detail goes in `dev-docs/`; `docs/` faces users and
  the store.
- **Three surfaces**. The main view, widget and watch move together: check a change to
  one against the others. They differ only where SPEC *One widget, many surfaces* says
  why.
- **Shared libraries**. Prefer a shared `mikelward/*` library to an app-local copy.
- **Staleness**. Never show stale data as live (SPEC D4): render from the persisted
  snapshot and refresh in the background, never with the network on a render path. A
  surface PR states what it renders from and when it refreshes.
- **Main thread: read and dispatch only**. Composition, effects, click handlers and
  UI-called functions do no I/O and no work that scales with input before hopping to an
  injectable dispatcher; test each from a single-thread caller. Mark data-scaling domain
  work `@WorkerThread`; `app/lint-baseline.xml` only shrinks.
- **Worked answers**. Show a worker's answer only for the inputs it was worked out for:
  `rememberWorked`, or a `produceState` answer kept as `Worked` and read only while its
  key holds.
- **List stability**. The list's inputs live with its scroll position (`ListWork`), and
  an answer standing in for older inputs still draws everything on screen. A new
  grouping, setting or tap gets a case in `ListReturnScrollTest` and `TapHoldsStillTest`.
- **Logic**. It lives in pure `:domain`, tested against recorded TfL fixtures, never the
  live API or a user's location.
- **Privacy**. Coordinates, watched and home or work stops, and the user's TfL `app_key`
  never leave this machine; use `app_key=EXAMPLE` and `(51.5, -0.12)`. Major stations are
  fine, even from a bug report. Ask in chat, never on GitHub, before adding a bus stop or
  a neighborhood place, and never add anything pointing to where someone lives.
- **Debug log**. Coarse diagnostics only (stop ID, line id, HTTP status, a vehicle's
  terminus), never coordinates or keys. `docs/PRIVACY.md` lists what it carries about the
  user before it ships.
- **Bug reports**. A report the user starts may carry exact location, per-stop distances
  and a screenshot, only past a consent screen they can decline. Recent positions come
  from the in-memory `RecentPositions`, never the persisted log.
- **Error handling**. Log a caught exception redacted to its type and stack: a request URL
  in its message carries coordinates and the `app_key`. Show a failure honestly, never as
  a blank screen.
- **Build**. `./gradlew test` and `./gradlew lint` pass before every push;
  `.claude/hooks/session-start.sh` sets up the SDK. The lock screen, widget, watch and
  location need a device: say what you couldn't verify.
- **CI failures**. Read the failed step's log first; CI builds the PR merged with `main`.
- **Screenshots**. CI records them, under `en-rGB`; each new screenshot class needs its
  own CI step.
- **What's new**. Title every commit like a changelog line (maintainer, 2026-10-23): an
  imperative verb, then the feature in the rider's plain words (no identifiers), sentence
  case, ≤ ~70 chars, e.g. `Fix …` for what was broken (`Fix bus stop lists missing train
  stations`), `Improve …` (`Improve "Get off soon" notification timing`), `Show …`/`Add …`
  for what's new; an optional parenthetical says how (`Fix National Rail stop lists (using
  National Rail instead of TfL data)`). Never the scenario or mechanism (that's the body).
  PR titles and release-note bullets match. Rebase-merges land every subject on `main` and
  in release notes (Play "What's new", GitHub prerelease `v<versionCode>`).
- **Prefixes**. Prefix a subject with no user-visible effect, overriding the fleet's list:
  `ci:`, `docs:` (docs/`*.md`, including `docs/PRIVACY.md`), `todo:` (`TODO.md` alone),
  `internal:` (build/plumbing; a shipped dependency is bare), `refactor:`,
  `test:`/`tests:`. Never `build:`: CI doesn't filter it, so it ships. A bare subject means
  a rider could notice. The docs lane accepts only `docs:` and `todo:`.
- **500 characters**. Play caps "What's new" there (`deploy` drops subjects past it), so
  squash supporting commits into the one telling the rider-facing story. Commits touching
  only `.md` files or root dot-paths (`.github/`, `.gitignore`, …) never ship a bullet.
- **After a merge**. Report the versionCode (`git rev-list --count origin/main` after an
  unshallowed refspec fetch): `Need versionCode 72 (b81c23d) or higher to test PR #52's
  fix`.
- **Release notes since N**. Up to five `• ` end-user bullets worded as titles, one per
  feature however many commits built it (read bodies where a subject is unclear), in one
  code block for commits N+1..`origin/main` (freshly fetched). Rank by what the rider risks
  without it (maintainer, 2026-10-23): wrong information that could send them the wrong way
  first (`Fix National Rail stop lists`), polish last (`Improve UI transitions when swiping
  back`). Skip prefixed/docs/CI commits, dependency bumps unless nothing else changed (then
  one bullet says libraries were updated), and anything the range itself added then
  undid; range above, one line below naming what was omitted.
- **UI**. 4dp grid, the shortest unambiguous copy ("3 min"), standard Material icons.
- **Line pills**. Official TfL line colors with APCA black or white text; the six named
  Overground lines are hollow pills. Confirmed TfL hexes only.
- **en-GB**. Riders are in the UK: a string whose British spelling or usage differs gets
  its `values-en-rGB` override in the same change.
- **Translations**. A new string ships in English, marked for translation, until the
  maintainer approves it on a device.
- **Questions**. Ask only a real product trade-off, its premise checked in code first,
  with its context: what, why, and each option's cost.
- **Cost**. TfL's API is free; anything new leaving the device is a privacy and Data
  Safety decision. State the battery cost of any new wakeup, location request or
  refresh.
- **Shared script**. `scripts/publish-github-release.sh` is identical across the Android
  apps: change them all.
