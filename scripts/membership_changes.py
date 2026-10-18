#!/usr/bin/env python3
"""List what a bundle refresh changed in the station model's membership (dev-docs/station-model.md).

Compares two builds of stops.tsv and station_index.json and writes, as Markdown for the weekly
refresh's pull request body, every id whose station, stop, owning station or hub changed, so a
regrouping is reviewed before it ships. A hub change alone isn't enough: a pole moving between two
stops of one hub, or a bus stop changing owner between two stations of one interchange, changes
matching and planning without moving either hub. Each category lists at most --cap ids, then its
total, so the body stays inside GitHub's 65,536-character limit.

  membership_changes.py --old-stops A.tsv --old-index A.json --new-stops B.tsv --new-index B.json
"""
import argparse
import json
import sys

CATEGORIES = ("station", "stop", "owner", "hub")
DEFAULT_CAP = 100


def read_stops(text):
    """id -> {category: value} from a stops.tsv's rows (v1 rows carry none)."""
    found = {}
    for line in text.splitlines():
        fields = line.split("\t")
        if fields[0] != "S" or len(fields) < 2:
            continue
        fields += [""] * (16 - len(fields))
        ics, level, owner, hub = fields[12:16]
        if not level and not hub:
            continue
        if fields[2] == "B":
            found[fields[1]] = {"stop": level, "owner": owner, "hub": hub}
        else:
            found[fields[1]] = {"station": level, "hub": hub}
    return found


def read_index(document):
    """id -> {category: value} from a station index's records (interchange rows aside)."""
    return {
        entry["id"]: {"station": entry.get("station", ""), "hub": entry.get("modelHub", "")}
        for entry in document.get("stations", [])
        if not entry["id"].upper().startswith("HUB")
    }


def changes(old, new):
    """category -> sorted [(id, before, after)] for every id in both whose value changed, plus the
    ids only one side holds."""
    found = {c: [] for c in CATEGORIES}
    for sid in sorted(set(old) & set(new)):
        for category in CATEGORIES:
            before, after = old[sid].get(category), new[sid].get(category)
            if (before or after) and before != after:
                found[category].append((sid, before or "", after or ""))
    return found, sorted(set(new) - set(old)), sorted(set(old) - set(new))


def markdown(found, added, removed, cap=DEFAULT_CAP):
    """The changes as Markdown, at most [cap] ids a category, each category's total stated."""
    lines = ["### Membership changes", ""]
    if not any(found.values()) and not added and not removed:
        return "\n".join(lines + ["No station, stop, owner or hub changed."]) + "\n"
    labels = {"station": "Station", "stop": "Stop", "owner": "Owning station", "hub": "Hub"}
    for category in CATEGORIES:
        rows = found[category]
        if not rows:
            continue
        lines.append(f"**{labels[category]}** ({len(rows)} changed)")
        lines += [f"- `{sid}`: `{before or '—'}` → `{after or '—'}`" for sid, before, after in rows[:cap]]
        if len(rows) > cap:
            lines.append(f"- … and {len(rows) - cap} more")
        lines.append("")
    for label, ids in (("New ids", added), ("Removed ids", removed)):
        if ids:
            shown = ", ".join(f"`{sid}`" for sid in ids[:cap])
            more = f", and {len(ids) - cap} more" if len(ids) > cap else ""
            lines += [f"**{label}** ({len(ids)}): {shown}{more}", ""]
    return "\n".join(lines)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for side in ("old", "new"):
        parser.add_argument(f"--{side}-stops", required=True)
        parser.add_argument(f"--{side}-index", required=True)
    parser.add_argument("--cap", type=int, default=DEFAULT_CAP)
    args = parser.parse_args(argv)

    def load(stops_path, index_path):
        with open(stops_path, encoding="utf-8") as f:
            found = read_stops(f.read())
        with open(index_path, encoding="utf-8") as f:
            found.update(read_index(json.load(f)))
        return found

    found, added, removed = changes(load(args.old_stops, args.old_index), load(args.new_stops, args.new_index))
    sys.stdout.write(markdown(found, added, removed, args.cap))


if __name__ == "__main__":
    main(sys.argv[1:])
