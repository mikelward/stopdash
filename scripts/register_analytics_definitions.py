#!/usr/bin/env python3
"""Registers StopDash's Analytics custom definitions (dev-docs/firebase.md step 6).

Reads dev-docs/analytics-definitions.tsv, the event parameters and user properties the app sends, and
creates a custom dimension for each one the Google Analytics property doesn't have yet, through the
Analytics Admin API, user-scoped ones kept out of ads personalization (one already there but not, made
by hand say, is updated to be). Safe to run again: a definition already there is otherwise left as it
is. A dry run unless
--apply; --archive-stale also archives the property's event- and user-scoped dimensions that are no
longer in the list (a renamed parameter's old name). Analytics can take up to 48 hours to free an
archived dimension's slot, so a run that would pass a limit creates nothing; run it again later.

    python3 scripts/register_analytics_definitions.py PROPERTY_ID [--apply] [--archive-stale]
        [--quota-project PROJECT_ID] [--definitions PATH]

PROPERTY_ID is the number in Analytics' Admin -> Property settings. The access token, which needs the
analytics.edit scope, comes from $GA_ACCESS_TOKEN, else from
`gcloud auth application-default print-access-token`; dev-docs/firebase.md says how to get one. It's
never printed. --quota-project (or $GOOGLE_CLOUD_QUOTA_PROJECT) names the Cloud project the calls are
billed to, which a user's own credentials need: the Firebase project's ID will do.

Standard library only.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFINITIONS = REPO / "dev-docs" / "analytics-definitions.tsv"
API = "https://analyticsadmin.googleapis.com/v1beta"

# The Admin API's scope for each of the file's, and the property's limits for each (a standard property).
SCOPES = {"event": "EVENT", "user": "USER"}
LIMITS = {"EVENT": 50, "USER": 25}
NAMES = {"event": re.compile(r"[a-z][a-z0-9_]{0,39}"), "user": re.compile(r"[a-z][a-z0-9_]{0,23}")}
DISPLAY_NAME = re.compile(r"[A-Za-z][A-Za-z0-9 _]{0,81}")
# How long one request waits for Analytics: a stalled one stops the run, which can be run again, rather
# than hanging it.
TIMEOUT_SECONDS = 30


@dataclass(frozen=True)
class Definition:
    scope: str  # EVENT or USER, as the Admin API names them
    name: str
    display_name: str


class ApiError(Exception):
    pass


def load_definitions(path):
    """The definitions in [path]: tab-separated scope, name and display name, # comments skipped."""
    definitions = []
    seen = set()
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        where = f"{path}:{number}"
        if len(fields) != 3:
            raise ValueError(f"{where}: expected scope, name and display name, tab-separated")
        scope, name, display_name = fields
        if scope not in SCOPES:
            raise ValueError(f"{where}: scope {scope!r} isn't one of {', '.join(SCOPES)}")
        if not NAMES[scope].fullmatch(name):
            raise ValueError(f"{where}: {name!r} isn't a name Analytics takes for a {scope} parameter")
        if not DISPLAY_NAME.fullmatch(display_name):
            raise ValueError(f"{where}: {display_name!r} isn't a display name Analytics takes")
        if (scope, name) in seen:
            raise ValueError(f"{where}: {scope} {name} is listed twice")
        seen.add((scope, name))
        definitions.append(Definition(SCOPES[scope], name, display_name))
    return definitions


@dataclass
class Plan:
    create: list
    present: list
    renamed: list  # (definition, its display name in the property)
    stale: list  # the property's dimensions no longer listed, as the API returns them
    # The property's listed user-scoped dimensions not yet kept out of ads personalization (one made by
    # hand, say), as the API returns them.
    unmarked: list


def plan(wanted, existing):
    """What registering [wanted] on a property holding [existing] (the API's dimensions) takes."""
    held = {(d.get("scope"), d.get("parameterName")): d for d in existing}
    create, present, renamed, unmarked = [], [], [], []
    for definition in wanted:
        there = held.get((definition.scope, definition.name))
        if there is None:
            create.append(definition)
            continue
        present.append(definition)
        if there.get("displayName") != definition.display_name:
            renamed.append((definition, there.get("displayName")))
        if definition.scope == "USER" and there.get("disallowAdsPersonalization") is not True:
            unmarked.append(there)
    listed = {(d.scope, d.name) for d in wanted}
    stale = [d for key, d in held.items() if key[0] in LIMITS and key not in listed]
    return Plan(create, present, renamed, stale, unmarked)


def over_limits(existing, steps):
    """
    The scopes whose dimensions would pass the property's limit once [steps]' creates are made. An archive
    frees no slot in the same run: Analytics can take up to 48 hours to release it.
    """
    over = []
    for scope, limit in LIMITS.items():
        count = sum(1 for d in existing if d.get("scope") == scope)
        count += sum(1 for d in steps.create if d.scope == scope)
        if count > limit:
            over.append(f"{scope.lower()}-scoped: {count} of {limit}")
    return over


class AdminApi:
    """The Analytics Admin API's custom dimensions of one property."""

    def __init__(self, property_id, token, quota_project=None, opener=urllib.request.urlopen):
        self.property = f"properties/{property_id}"
        self.token = token
        self.quota_project = quota_project
        self.opener = opener

    def _call(self, method, path, body=None):
        request = urllib.request.Request(
            f"{API}/{path}",
            data=None if body is None else json.dumps(body).encode("utf-8"),
            method=method,
        )
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("Content-Type", "application/json")
        if self.quota_project:
            request.add_header("x-goog-user-project", self.quota_project)
        try:
            with self.opener(request, timeout=TIMEOUT_SECONDS) as response:
                text = response.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            # Analytics' own message says what's wrong (a missing scope, a property not found); the
            # request's token is never in it.
            try:
                message = json.loads(e.read().decode("utf-8")).get("error", {}).get("message", "")
            except (ValueError, AttributeError):
                message = ""
            raise ApiError(f"{method} {path}: HTTP {e.code} {message}".rstrip()) from None
        except urllib.error.URLError as e:
            raise ApiError(f"{method} {path}: {e.reason}") from None
        except TimeoutError:
            # Connected, then no answer in time.
            raise ApiError(f"{method} {path}: no answer in {TIMEOUT_SECONDS} seconds") from None
        return json.loads(text) if text else {}

    def list_dimensions(self):
        dimensions = []
        token = None
        while True:
            query = {"pageSize": "200"}
            if token:
                query["pageToken"] = token
            page = self._call("GET", f"{self.property}/customDimensions?{urllib.parse.urlencode(query)}")
            dimensions += page.get("customDimensions", [])
            token = page.get("nextPageToken")
            if not token:
                return dimensions

    def create(self, definition):
        body = {
            "parameterName": definition.name,
            "displayName": definition.display_name,
            "scope": definition.scope,
        }
        # Never for ads: the rider's setup (step-free needs among it) is for reading usage against.
        if definition.scope == "USER":
            body["disallowAdsPersonalization"] = True
        return self._call("POST", f"{self.property}/customDimensions", body)

    def exclude_from_ads(self, dimension):
        """Keeps an existing user-scoped [dimension] out of ads personalization, as [create] makes one."""
        return self._call(
            "PATCH",
            f"{dimension['name']}?updateMask=disallowAdsPersonalization",
            {"disallowAdsPersonalization": True},
        )

    def archive(self, dimension):
        return self._call("POST", f"{dimension['name']}:archive", {})


def access_token(environ, run):
    """The token from $GA_ACCESS_TOKEN, else gcloud's application-default one; None if neither gives one."""
    token = environ.get("GA_ACCESS_TOKEN", "").strip()
    if token:
        return token
    try:
        result = run(
            ["gcloud", "auth", "application-default", "print-access-token"],
            capture_output=True, text=True, check=False,
        )
    except FileNotFoundError:
        return None
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else None


def describe(definition):
    return f"{definition.scope.lower():5}  {definition.name:20}  {definition.display_name}"


def main(argv=None, environ=os.environ, run=subprocess.run, api_factory=AdminApi, out=sys.stdout):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("property_id", help="the GA4 property's ID (Admin -> Property settings)")
    parser.add_argument("--apply", action="store_true", help="make the changes; a dry run without it")
    parser.add_argument("--archive-stale", action="store_true", help="also archive dimensions no longer listed")
    parser.add_argument("--quota-project", default=environ.get("GOOGLE_CLOUD_QUOTA_PROJECT"),
                        help="the Cloud project to bill the calls to (the Firebase project's ID)")
    parser.add_argument("--definitions", default=str(DEFINITIONS), help="the definitions file")
    args = parser.parse_args(argv)

    def say(text=""):
        print(text, file=out)

    property_id = args.property_id.removeprefix("properties/")
    if not property_id.isdigit():
        say(f"{args.property_id!r} isn't a property ID: the number in Admin -> Property settings.")
        return 2
    try:
        wanted = load_definitions(args.definitions)
    except (OSError, ValueError) as e:
        say(f"Couldn't read the definitions: {e}")
        return 2
    token = access_token(environ, run)
    if token is None:
        say("No access token: set GA_ACCESS_TOKEN, or sign in with gcloud (dev-docs/firebase.md step 6).")
        return 2
    api = api_factory(property_id, token, args.quota_project)

    try:
        existing = api.list_dimensions()
    except ApiError as e:
        say(f"Couldn't list the property's custom dimensions: {e}")
        return 1
    steps = plan(wanted, existing)

    say(f"Property {property_id}: {len(wanted)} definitions listed, {len(steps.present)} already there.")
    if steps.create:
        say(f"To create ({len(steps.create)}):")
        for definition in steps.create:
            say(f"  {describe(definition)}")
    for definition, there in steps.renamed:
        say(f"Display name differs, left as it is: {definition.name} is {there!r} there, {definition.display_name!r} here.")
    if steps.stale:
        verb = "To archive" if args.archive_stale else "No longer listed (archived with --archive-stale)"
        say(f"{verb} ({len(steps.stale)}):")
        for dimension in steps.stale:
            say(f"  {dimension.get('scope', '').lower():5}  {dimension.get('parameterName', ''):20}  {dimension.get('displayName', '')}")
    if steps.unmarked:
        say(f"To keep out of ads personalization ({len(steps.unmarked)}):")
        for dimension in steps.unmarked:
            say(f"  user   {dimension.get('parameterName', '')}")
    # Over the limit, nothing is created: the archives (if asked for) still go, to free their slots for a
    # later run, as Analytics can take up to 48 hours to release them; so do the ads exclusions, which
    # take no slot.
    over = over_limits(existing, steps)
    archive = steps.stale if args.archive_stale else []
    create = [] if over else steps.create
    if over:
        say("Creating them would pass the property's limits (" + "; ".join(over) + "), so none are created.")
        if steps.stale and not archive:
            say("Archiving the dimensions no longer listed (--archive-stale) frees their slots, within 48 hours.")
        if not archive and not steps.unmarked:
            return 1
    if not create and not archive and not steps.unmarked:
        say("Nothing to do.")
        return 0
    if not args.apply:
        say("Dry run: nothing changed. Run again with --apply to make these changes.")
        return 1 if over else 0

    # Creates first, archives last: an archive can't be undone, so a run that stops part way never leaves an
    # old dimension archived and its replacement missing. The creates fit without the archives' slots, as
    # [over_limits] never counts on them.
    try:
        for definition in create:
            api.create(definition)
            say(f"Created {definition.name}.")
        for dimension in steps.unmarked:
            api.exclude_from_ads(dimension)
            say(f"Kept {dimension.get('parameterName')} out of ads personalization.")
        for dimension in archive:
            api.archive(dimension)
            say(f"Archived {dimension.get('parameterName')}.")
    except ApiError as e:
        say(f"Stopped: {e}")
        say("What's done stays done; run it again to finish.")
        return 1
    if over:
        say("Run it again once Analytics has freed the archived slots (up to 48 hours) to create the rest.")
        return 1
    say("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
