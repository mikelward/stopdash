// Flags the weekly dependency batch for human review ONLY when the license
// metadata of what the app bundles actually changes — not when a version
// number moves.
//
// The AboutLibraries plugin aggregates each dependency's Maven POM license
// declaration into app/src/main/res/raw/aboutlibraries.json (the Licenses
// screen's data). The dependency-update workflow regenerates that file with
// every batch and commits it, so the interesting question is no longer "did
// the file change" (it changes on every bump) but "did the LICENSE NAMES
// change". This script projects each library to one line — `uniqueId:
// license-ids` — and diffs the projections between HEAD and the working
// tree. A new or changed line is a change to what the shipped app legally
// declares, so it exits nonzero and names the exact library; the workflow
// puts that output in the PR body as the reason a human is being asked to
// look. A library dropping out imposes no new obligation, so removals are
// reported but do not flag. License TEXT is deliberately out of scope: the
// ids are the metadata, the texts ride along with them.
//
// The same file also carries this app's LICENSE POLICY, because the policy
// is only worth having if it is tested and the tests live here. LDNGo ships
// proprietary — no LICENSE file, all rights reserved — so every bundled
// license id is sorted into one of three tiers: `fine` (the permissive ids
// and Google vendor terms already shipping), `forbidden` (copyleft and
// source-available terms, incompatible outright rather than review-worthy),
// and `review` (weak copyleft and anything this table has never seen — a NEW
// id gets an answer, not just an eyeball). The diff mode above annotates the
// libraries it flags with their tier, so the weekly batch's PR body says not
// only that a license changed but what that change means; `--forbidden`
// audits the whole working-tree inventory and exits nonzero on any entry
// that is not already `fine` (forbidden OR review-tier), which is what CI
// runs between regenerating the inventory and checking it for drift — so a
// manual dependency PR adding an unclassified or weak-copyleft license fails
// there too, not only on the unattended weekly path.
//
// Run from the repository root:
//   node scripts/check-license-inventory.mjs              (diff HEAD vs tree)
//   node scripts/check-license-inventory.mjs --forbidden  (audit the tree)

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

export const INVENTORY = "app/src/main/res/raw/aboutlibraries.json";

const projection = (json, label) => {
  const libraries = json?.libraries;
  // The failure mode here is a FALSE PASS — an inventory that parsed to
  // nothing has no lines to diff and would vouch for anything — so an
  // empty parse refuses instead of comparing.
  if (!Array.isArray(libraries) || libraries.length === 0) {
    throw new Error(`${label}: no libraries found in the inventory — refusing to compare`);
  }
  const lines = new Map();
  for (const lib of libraries) {
    const id = lib?.uniqueId;
    if (typeof id !== "string" || id === "") {
      throw new Error(`${label}: a library entry has no uniqueId — refusing to compare`);
    }
    const licenses = Array.isArray(lib.licenses) && lib.licenses.length > 0
      ? [...lib.licenses].sort().join(", ")
      : "(no license declared)";
    lines.set(id, licenses);
  }
  return lines;
};

// The permissive ids and Google vendor terms shipping today. Anything not
// here and not forbidden is `review`: a permissive id this table has never
// seen (ISC, say) costs one eyeball and one line added here, which is the
// right price for a proprietary app taking on a new license. An id listed
// here is fine whatever its display name says — this is also the escape
// hatch for a false positive below, so a human who has read the license
// records the verdict as one line instead of loosening a pattern.
export const FINE_LICENSES = new Set(["Apache-2.0", "BSD-3-Clause", "MIT", "ASDKL", "PCSDKToS"]);

// Matched against BOTH the license id and its display name, case-insensitively.
// AboutLibraries keys a license by its SPDX id when it has one and by a hash
// of the name when it does not, so a non-SPDX copyleft declaration can carry
// an opaque id and be recognizable only by name — matching the name is what
// keeps the gate from being an id-spelling test. The match is deliberately
// unanchored: a name that merely mentions a forbidden family ("MIT License
// (GPL compatible)") is forbidden too, because this gate fails closed — a
// false positive is a red CI naming the library and the exact license name,
// one eyeball, and one FINE_LICENSES entry; a false negative is copyleft
// shipping silently in a proprietary app. Recognizing only "proper" GPL
// name forms would trade the second for the first (Codex, PR #304).
export const FORBIDDEN_PATTERNS = [
  // GPL, LGPL, AGPL in every version and `-only`/`-or-later` spelling, the
  // version-suffixed abbreviations a POM may write instead (`GPLv3`,
  // `LGPLv2.1`, `GPL3`), and the long forms. The suffix alternative is what
  // makes `GPLv3` match: `\b` sees `L` and `v` as one word and would let a
  // hand-written `GNU GPLv3` through as `review` (Codex, PR #304).
  [/\b[AL]?GPL(?:v?\d|\b)/i, "GPL-family copyleft"],
  [/general public license/i, "GPL-family copyleft"],
  [/affero/i, "AGPL copyleft"],
  [/\bSSPL\b/i, "source-available (SSPL)"],
  [/server side public license/i, "source-available (SSPL)"],
  [/\bBUSL\b/i, "source-available (BUSL)"],
  [/business source license/i, "source-available (BUSL)"],
  // CC BY-NC in any of its combinations (BY-NC, BY-NC-SA, BY-NC-ND, ...).
  [/\bCC[- ]BY(?:[- ][A-Z]{2})*[- ]NC\b/i, "non-commercial (CC BY-NC)"],
  [/non-?commercial/i, "non-commercial"],
  [/commons clause/i, "Commons Clause"],
];

/**
 * Sorts one license into its tier. `name` is the display name from the
 * inventory's `licenses` map when the id resolves there; the forbidden
 * patterns see both. Returns `{ tier, reason }` where `reason` names the
 * matched family for a forbidden id and is null otherwise.
 */
export const classifyLicense = (id, name = "") => {
  if (FINE_LICENSES.has(id)) return { tier: "fine", reason: null };
  for (const [pattern, reason] of FORBIDDEN_PATTERNS) {
    if (pattern.test(id) || pattern.test(name)) return { tier: "forbidden", reason };
  }
  return { tier: "review", reason: null };
};

const TIER_RANK = { fine: 0, review: 1, forbidden: 2 };

/**
 * Classifies every library in a parsed inventory: a library's tier is the
 * worst tier across its declared licenses, and a library declaring no
 * license at all is `review` — nobody has said what it may be used under.
 * Returns the non-fine libraries as report lines bucketed by tier; an
 * empty inventory refuses, for the same false-pass reason as the diff.
 */
export const classifyInventory = (json, label = "inventory") => {
  projection(json, label); // the same shape checks, same refusals
  const names = json.licenses && typeof json.licenses === "object" ? json.licenses : {};
  const forbidden = [];
  const review = [];
  for (const lib of json.libraries) {
    const ids = Array.isArray(lib.licenses) ? lib.licenses : [];
    let worst = ids.length === 0 ? { tier: "review", reason: null, id: "(no license declared)" } : null;
    for (const id of ids) {
      const verdict = { ...classifyLicense(id, names[id]?.name ?? ""), id };
      if (worst === null || TIER_RANK[verdict.tier] > TIER_RANK[worst.tier]) worst = verdict;
    }
    if (worst.tier === "forbidden") {
      const name = names[worst.id]?.name;
      forbidden.push(`${lib.uniqueId}: ${worst.id}${name ? ` (${name})` : ""} — ${worst.reason}`);
    } else if (worst.tier === "review") {
      const name = names[worst.id]?.name;
      review.push(`${lib.uniqueId}: ${worst.id}${name ? ` (${name})` : ""}`);
    }
  }
  return { forbidden, review };
};

export const auditReport = ({ forbidden, review }, librariesCount) => {
  const out = [];
  if (forbidden.length > 0) {
    out.push("FORBIDDEN licenses bundled — copyleft or source-available terms are incompatible with this app's proprietary distribution:");
    for (const line of forbidden) out.push(`  - ${line}`);
  }
  if (review.length > 0) {
    out.push("Licenses needing REVIEW — weak copyleft or an id this policy has not classified (scripts/check-license-inventory.mjs, FINE_LICENSES):");
    for (const line of review) out.push(`  - ${line}`);
  }
  if (out.length === 0) {
    out.push(`All ${librariesCount} bundled libraries carry a license this app already ships under.`);
  }
  return out.join("\n");
};

/**
 * Whether an audit is policy-clean: no forbidden AND no review-tier entries.
 * Review-tier is deliberately not clean — the id is unclassified or weak
 * copyleft and owes a one-line verdict (add it to FINE_LICENSES, or it is
 * forbidden) before it ships. This is what the CI `--forbidden` audit gates
 * on, so a manual dependency PR can't slip a new license past green.
 */
export const auditIsClean = ({ forbidden, review }) =>
  forbidden.length === 0 && review.length === 0;

/**
 * Diffs the license projections of two parsed inventories. Returns the
 * changes bucketed the way the report prints them; `flagged` is true when
 * the batch needs a human — a library appeared, or one changed license.
 */
export const compareInventories = (oldJson, newJson) => {
  const before = projection(oldJson, "HEAD inventory");
  const after = projection(newJson, "regenerated inventory");
  const changed = [];
  const added = [];
  const removed = [];
  for (const [id, licenses] of after) {
    if (!before.has(id)) {
      added.push(`${id}: ${licenses}`);
    } else if (before.get(id) !== licenses) {
      changed.push(`${id}: ${before.get(id)} → ${licenses}`);
    }
  }
  for (const [id, licenses] of before) {
    if (!after.has(id)) removed.push(`${id}: ${licenses}`);
  }
  return { changed, added, removed, flagged: changed.length > 0 || added.length > 0 };
};

export const report = ({ changed, added, removed, flagged }, librariesCount) => {
  const out = [];
  if (changed.length > 0) {
    out.push("License CHANGED for bundled libraries:");
    for (const line of changed) out.push(`  - ${line}`);
  }
  if (added.length > 0) {
    out.push("NEW bundled libraries (new license metadata):");
    for (const line of added) out.push(`  - ${line}`);
  }
  if (removed.length > 0) {
    out.push("No longer bundled (no new obligations, listed for the record):");
    for (const line of removed) out.push(`  - ${line}`);
  }
  if (out.length === 0) {
    out.push(`License metadata unchanged across ${librariesCount} bundled libraries.`);
  } else if (!flagged) {
    out.push("Nothing here adds or changes a license — not flagging.");
  }
  return out.join("\n");
};

/**
 * The tier verdicts for just the libraries a diff flagged, so the batch's
 * "a license changed" reason also says what the new license means. Lines
 * come back in the audit report's shape; nothing is printed for a flagged
 * library whose new license is `fine`.
 */
export const classifyFlagged = ({ changed, added }, newJson) => {
  const flaggedIds = new Set([...changed, ...added].map((line) => line.slice(0, line.indexOf(": "))));
  const subset = {
    licenses: newJson.licenses,
    libraries: newJson.libraries.filter((lib) => flaggedIds.has(lib.uniqueId)),
  };
  return subset.libraries.length === 0
    ? { forbidden: [], review: [] }
    : classifyInventory(subset, "flagged libraries");
};

const main = (mode) => {
  const newJson = JSON.parse(readFileSync(INVENTORY, "utf8"));
  if (mode === "--forbidden") {
    const audit = classifyInventory(newJson, "working-tree inventory");
    console.log(auditReport(audit, newJson.libraries.length));
    // Fail on `review` as well as `forbidden` (auditIsClean): on a MANUAL
    // dependency PR the regenerated inventory is committed in the same PR, so
    // diff mode sees no change and only this audit gates the batch — and the
    // `review` tier is exactly "a NEW id gets an answer, not just an eyeball"
    // (above). Letting it pass green would leave an unclassified or weak-
    // copyleft license shipping with the warning buried in a successful step's
    // log. Stricter than the fleet's forbidden-only audit on purpose — see the
    // PR that added this.
    process.exitCode = auditIsClean(audit) ? 0 : 1;
    return;
  }
  if (mode !== undefined) {
    throw new Error(`unknown argument ${mode}; expected no argument (diff mode) or --forbidden`);
  }
  const oldJson = JSON.parse(
    // `HEAD:./` is cwd-relative on purpose, matching the working-tree read
    // above, so the script compares like with like wherever it is run from.
    execFileSync("git", ["show", `HEAD:./${INVENTORY}`], { encoding: "utf8" }),
  );
  const result = compareInventories(oldJson, newJson);
  console.log(report(result, newJson.libraries.length));
  if (result.flagged) {
    const verdicts = classifyFlagged(result, newJson);
    if (verdicts.forbidden.length > 0 || verdicts.review.length > 0) {
      console.log(auditReport(verdicts, newJson.libraries.length));
    }
  }
  process.exitCode = result.flagged ? 1 : 0;
};

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  main(process.argv[2]);
}
