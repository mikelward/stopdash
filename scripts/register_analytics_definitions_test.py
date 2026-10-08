#!/usr/bin/env python3
"""Tests for register_analytics_definitions.py, against a fake Admin API (no network, no account).

Standard library only. Run it directly: python3 scripts/register_analytics_definitions_test.py
"""

import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(__file__))
import register_analytics_definitions as script  # noqa: E402
from register_analytics_definitions import Definition  # noqa: E402


class FakeApi:
    """A property's custom dimensions, held in memory; records what the script asked of it."""

    def __init__(self, existing=(), fail_on=None):
        self.dimensions = [dict(d) for d in existing]
        self.created = []
        self.archived = []
        self.excluded = []
        self.fail_on = fail_on

    def __call__(self, property_id, token, quota_project):
        self.property_id, self.token, self.quota_project = property_id, token, quota_project
        return self

    def list_dimensions(self):
        return list(self.dimensions)

    def create(self, definition):
        if definition.name == self.fail_on:
            raise script.ApiError(f"POST properties/1/customDimensions: HTTP 403 no access to {definition.name}")
        self.created.append(definition)

    def archive(self, dimension):
        self.archived.append(dimension["parameterName"])

    def exclude_from_ads(self, dimension):
        self.excluded.append(dimension["parameterName"])


def dimension(scope, name, display_name=None, ads_allowed=False):
    held = {
        "name": f"properties/123/customDimensions/{name}",
        "parameterName": name,
        "displayName": display_name or name.replace("_", " ").capitalize(),
        "scope": scope,
    }
    # As the script makes a user-scoped one, unless a test says otherwise; the API leaves out a false one.
    if scope == "USER" and not ads_allowed:
        held["disallowAdsPersonalization"] = True
    return held


def definitions_file(text):
    handle = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8")
    handle.write(text)
    handle.close()
    return handle.name


SMALL = definitions_file(
    "# scope\tname\tdisplay name\n"
    "event\tkind\tTap kind\n"
    "event\ttube\tNearby tube stops\n"
    "user\twalking_speed\tWalking speed\n"
)


def run(argv, api, environ=None, gcloud=None):
    out = io.StringIO()
    calls = []

    def fake_run(command, **kwargs):
        calls.append(command)
        if gcloud is None:
            raise FileNotFoundError("gcloud")
        return gcloud

    code = script.main(argv, environ={"GA_ACCESS_TOKEN": "secret-token"} if environ is None else environ,
                       run=fake_run, api_factory=api, out=out)
    return code, out.getvalue(), calls


class LoadDefinitionsTest(unittest.TestCase):
    def test_the_repositorys_list_reads_cleanly_with_both_scopes(self):
        definitions = script.load_definitions(script.DEFINITIONS)
        scopes = {d.scope for d in definitions}
        self.assertEqual({"EVENT", "USER"}, scopes)
        self.assertIn(Definition("EVENT", "outcome", "Outcome"), definitions)
        self.assertIn(Definition("USER", "walking_speed", "Walking speed"), definitions)

    def test_a_malformed_line_is_named_by_its_line(self):
        for text, message in [
            ("event\tkind\n", "expected scope, name and display name"),
            ("item\tkind\tTap kind\n", "isn't one of"),
            ("event\tKind\tTap kind\n", "isn't a name"),
            ("user\t" + "a" * 25 + "\tToo long\n", "isn't a name"),
            ("event\tkind\t1 kind\n", "isn't a display name"),
            ("event\tkind\tTap kind\nevent\tkind\tAgain\n", "listed twice"),
        ]:
            with self.subTest(text=text):
                path = definitions_file("# header\n" + text)
                with self.assertRaises(ValueError) as caught:
                    script.load_definitions(path)
                self.assertIn(message, str(caught.exception))
                self.assertIn(path + ":", str(caught.exception))


class MainTest(unittest.TestCase):
    def test_a_dry_run_lists_what_it_would_create_and_changes_nothing(self):
        api = FakeApi([dimension("EVENT", "kind", "Tap kind")])
        code, out, _ = run(["123", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertEqual([], api.created)
        self.assertIn("1 already there", out)
        self.assertIn("tube", out)
        self.assertIn("walking_speed", out)
        self.assertIn("Dry run", out)

    def test_apply_creates_only_the_missing_ones_in_their_scopes(self):
        api = FakeApi([dimension("EVENT", "kind", "Tap kind")])
        code, out, _ = run(["123", "--apply", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertEqual(
            [Definition("EVENT", "tube", "Nearby tube stops"), Definition("USER", "walking_speed", "Walking speed")],
            api.created,
        )
        self.assertIn("Done.", out)

    def test_a_name_held_under_the_other_scope_is_still_created_in_its_own(self):
        api = FakeApi([dimension("USER", "kind", "Tap kind")])
        run(["123", "--apply", "--definitions", SMALL], api)
        self.assertIn(Definition("EVENT", "kind", "Tap kind"), api.created)

    def test_nothing_to_do_when_all_are_there(self):
        api = FakeApi([dimension("EVENT", "kind", "Tap kind"), dimension("EVENT", "tube", "Nearby tube stops"),
                       dimension("USER", "walking_speed", "Walking speed")])
        code, out, _ = run(["123", "--apply", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertIn("Nothing to do.", out)
        self.assertEqual([], api.created)

    def test_a_user_dimension_made_without_the_ads_exclusion_gets_it(self):
        existing = [dimension("EVENT", "kind", "Tap kind"), dimension("EVENT", "tube", "Nearby tube stops"),
                    dimension("USER", "walking_speed", "Walking speed", ads_allowed=True)]
        api = FakeApi(existing)
        code, out, _ = run(["123", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertIn("ads personalization", out)
        self.assertEqual([], api.excluded, "a dry run changes nothing")
        api = FakeApi(existing)
        code, _, _ = run(["123", "--apply", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertEqual(["walking_speed"], api.excluded)
        self.assertEqual([], api.created)

    def test_a_display_name_changed_since_is_reported_and_left(self):
        api = FakeApi([dimension("EVENT", "kind", "Kind")])
        _, out, _ = run(["123", "--definitions", SMALL], api)
        self.assertIn("Display name differs", out)

    def test_a_stale_dimension_is_listed_and_archived_only_when_asked(self):
        existing = [dimension("EVENT", "kind", "Tap kind"), dimension("EVENT", "train", "Nearby train stops"),
                    dimension("ITEM", "item_thing", "Not ours to judge")]
        api = FakeApi(existing)
        code, out, _ = run(["123", "--apply", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertIn("No longer listed", out)
        self.assertIn("train", out)
        self.assertNotIn("item_thing", out)
        self.assertEqual([], api.archived)

        api = FakeApi(existing)
        run(["123", "--archive-stale", "--definitions", SMALL], api)
        self.assertEqual([], api.archived, "a dry run archives nothing either")

        api = FakeApi(existing)
        code, _, _ = run(["123", "--apply", "--archive-stale", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertEqual(["train"], api.archived)

    def test_passing_the_propertys_limit_creates_nothing(self):
        full = [dimension("USER", f"other_{i}") for i in range(25)]
        api = FakeApi(full)
        code, out, _ = run(["123", "--apply", "--definitions", SMALL], api)
        self.assertEqual(1, code)
        self.assertIn("limits", out)
        self.assertIn("--archive-stale", out)
        self.assertEqual([], api.created)
        # Archiving frees no slot in the same run (Analytics takes up to 48 hours): the archives go, the
        # creates wait for a later run.
        api = FakeApi(full)
        code, out, _ = run(["123", "--apply", "--archive-stale", "--definitions", SMALL], api)
        self.assertEqual(1, code)
        self.assertEqual(25, len(api.archived))
        self.assertEqual([], api.created)
        self.assertIn("Run it again", out)

    def test_archiving_stale_ones_within_the_limit_creates_the_rest_at_once(self):
        api = FakeApi([dimension("EVENT", "kind", "Tap kind"), dimension("EVENT", "train", "Nearby train stops")])
        code, _, _ = run(["123", "--apply", "--archive-stale", "--definitions", SMALL], api)
        self.assertEqual(0, code)
        self.assertEqual(["train"], api.archived)
        self.assertEqual(2, len(api.created))

    def test_a_failure_part_way_stops_and_says_to_run_again(self):
        api = FakeApi(fail_on="tube")
        code, out, _ = run(["123", "--apply", "--definitions", SMALL], api)
        self.assertEqual(1, code)
        self.assertEqual([Definition("EVENT", "kind", "Tap kind")], api.created)
        self.assertIn("run it again", out)

    def test_a_create_failing_leaves_the_stale_ones_unarchived(self):
        # An archive can't be undone: the replacements are made first, so a failure leaves the old ones in place.
        api = FakeApi([dimension("EVENT", "train", "Nearby train stops")], fail_on="tube")
        code, _, _ = run(["123", "--apply", "--archive-stale", "--definitions", SMALL], api)
        self.assertEqual(1, code)
        self.assertEqual([Definition("EVENT", "kind", "Tap kind")], api.created)
        self.assertEqual([], api.archived)

    def test_the_token_comes_from_the_environment_else_gcloud_and_is_never_printed(self):
        api = FakeApi()
        _, out, calls = run(["123", "--definitions", SMALL], api)
        self.assertEqual("secret-token", api.token)
        self.assertEqual([], calls)
        self.assertNotIn("secret-token", out)

        gcloud = type("Result", (), {"returncode": 0, "stdout": "gcloud-token\n"})()
        api = FakeApi()
        _, _, calls = run(["123", "--definitions", SMALL], api, environ={}, gcloud=gcloud)
        self.assertEqual("gcloud-token", api.token)
        self.assertEqual([["gcloud", "auth", "application-default", "print-access-token"]], calls)

        code, out, _ = run(["123", "--definitions", SMALL], FakeApi(), environ={})
        self.assertEqual(2, code)
        self.assertIn("No access token", out)

    def test_the_quota_project_comes_from_the_flag_or_the_environment(self):
        api = FakeApi()
        run(["123", "--quota-project", "stopdash-example", "--definitions", SMALL], api)
        self.assertEqual("stopdash-example", api.quota_project)
        api = FakeApi()
        run(["123", "--definitions", SMALL], api,
            environ={"GA_ACCESS_TOKEN": "t", "GOOGLE_CLOUD_QUOTA_PROJECT": "from-env"})
        self.assertEqual("from-env", api.quota_project)

    def test_a_property_id_is_its_number(self):
        api = FakeApi()
        self.assertEqual(0, run(["properties/123", "--definitions", SMALL], api)[0])
        self.assertEqual("123", api.property_id)
        code, out, _ = run(["G-ABC123", "--definitions", SMALL], FakeApi())
        self.assertEqual(2, code)
        self.assertIn("isn't a property ID", out)


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def read(self):
        return self.body.encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class AdminApiTest(unittest.TestCase):
    def test_it_pages_through_the_list_with_its_token_and_quota_project(self):
        requests, timeouts = [], []
        pages = [
            {"customDimensions": [dimension("EVENT", "kind")], "nextPageToken": "page2"},
            {"customDimensions": [dimension("USER", "watch")]},
        ]

        def opener(request, timeout):
            requests.append(request)
            timeouts.append(timeout)
            return FakeResponse(json.dumps(pages[len(requests) - 1]))

        api = script.AdminApi("123", "secret-token", "stopdash-example", opener=opener)
        self.assertEqual(["kind", "watch"], [d["parameterName"] for d in api.list_dimensions()])
        self.assertEqual(2, len(requests))
        self.assertIn("properties/123/customDimensions?pageSize=200", requests[0].full_url)
        self.assertIn("pageToken=page2", requests[1].full_url)
        self.assertEqual("Bearer secret-token", requests[0].get_header("Authorization"))
        self.assertEqual("stopdash-example", requests[0].get_header("X-goog-user-project"))
        # Each request is bounded, so a stalled one can't hang the run.
        self.assertEqual([script.TIMEOUT_SECONDS] * 2, timeouts)

    def test_a_create_posts_the_definition_and_an_archive_its_name(self):
        requests = []

        def opener(request, timeout):
            requests.append(request)
            return FakeResponse("{}")

        api = script.AdminApi("123", "t", opener=opener)
        api.create(Definition("USER", "watch", "Watch"))
        api.create(Definition("EVENT", "kind", "Tap kind"))
        api.archive(dimension("EVENT", "train"))
        api.exclude_from_ads(dimension("USER", "watch"))
        self.assertEqual("POST", requests[0].get_method())
        # A user property is kept out of ads personalization; an event parameter can't be marked.
        self.assertEqual(
            {"parameterName": "watch", "displayName": "Watch", "scope": "USER", "disallowAdsPersonalization": True},
            json.loads(requests[0].data),
        )
        self.assertEqual({"parameterName": "kind", "displayName": "Tap kind", "scope": "EVENT"}, json.loads(requests[1].data))
        self.assertTrue(requests[2].full_url.endswith("properties/123/customDimensions/train:archive"))
        self.assertEqual("PATCH", requests[3].get_method())
        self.assertTrue(requests[3].full_url.endswith("properties/123/customDimensions/watch?updateMask=disallowAdsPersonalization"))
        self.assertEqual({"disallowAdsPersonalization": True}, json.loads(requests[3].data))
        self.assertIsNone(requests[0].get_header("X-goog-user-project"))

    def test_an_http_error_says_what_analytics_said_and_never_the_token(self):
        def opener(request, timeout):
            body = io.BytesIO(json.dumps({"error": {"message": "Request had insufficient authentication scopes."}}).encode())
            raise urllib.error.HTTPError(request.full_url, 403, "Forbidden", {}, body)

        api = script.AdminApi("123", "secret-token", opener=opener)
        with self.assertRaises(script.ApiError) as caught:
            api.list_dimensions()
        self.assertIn("HTTP 403", str(caught.exception))
        self.assertIn("insufficient authentication scopes", str(caught.exception))
        self.assertNotIn("secret-token", str(caught.exception))

    def test_a_request_with_no_answer_in_time_stops_with_an_error(self):
        def opener(request, timeout):
            raise TimeoutError("The read operation timed out")

        api = script.AdminApi("123", "secret-token", opener=opener)
        with self.assertRaises(script.ApiError) as caught:
            api.create(Definition("EVENT", "kind", "Tap kind"))
        self.assertIn(f"no answer in {script.TIMEOUT_SECONDS} seconds", str(caught.exception))
        self.assertNotIn("secret-token", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
