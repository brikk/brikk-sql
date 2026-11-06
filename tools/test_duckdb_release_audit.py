import copy
import json
from pathlib import Path
import re
import unittest
from unittest.mock import patch

import duckdb_release_audit as audit

ROOT = Path(__file__).resolve().parents[1]


class DuckdbReleaseAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshot = json.loads((ROOT / "vendor/data/duckdb-2.0-release-triage.json").read_text())

    def test_every_original_release_pr_has_exactly_one_reproducible_disposition(self):
        actual = audit.audit(self.snapshot["source"])
        self.assertEqual(self.snapshot, actual)
        original = re.findall(r"https://github.com/duckdb/duckdb/pull/(\d+)", actual["source"]["body"])
        self.assertEqual([int(i) for i in original], [row["pr"] for row in actual["rows"]])
        self.assertEqual(len(original), len(set(original)))
        tracker = (ROOT / "dev-docs/TODO-duckdb-2.0.md").read_text()
        rows = re.findall(r"^\| (DDB20-\d{3}) \| (DONE|AUDIT|ADD|MONITOR|DEFER) \|", tracker, re.M)
        ids = {id for id, _ in rows}
        self.assertEqual({f"DDB20-{i:03}" for i in range(1, 79)}, ids)
        self.assertEqual(len(ids), len(rows), "Each stable ID must have exactly one tracker row")
        for row in actual["rows"]:
            self.assertTrue(set(row["owners"]) <= ids, row)
            self.assertTrue(row["reason"])
            self.assertNotIn(row["classification"], {"implemented", "done", "verified-executable"})

    def test_sql_facing_candidates_keep_their_real_stable_owner_ids(self):
        rows = self.snapshot["rows"]
        for needle, owner in [("Allow empty STRUCT", "DDB20-073"), ("SET SCHEMA", "DDB20-071"),
                              ("Table-Function Identifier", "DDB20-074"), ("AFTER INSERT FOR EACH ROW", "DDB20-020"),
                              ("ephemeral attach", "DDB20-019"), ("secret storage", "DDB20-075"),
                              ("dialect_compatibility_mode", "DDB20-078"), ("AliasColon", "DDB20-076")]:
            matches = [row for row in rows if needle.lower() in row["title"].lower()]
            self.assertTrue(matches, needle)
            self.assertTrue(all(owner in row["owners"] for row in matches), matches)

    def test_opaque_titles_and_engine_results_are_not_relabelled_as_implemented_parser_work(self):
        for row in self.snapshot["rows"]:
            if row["classification"] == "opaque-title-followup":
                self.assertEqual(["DDB20-063"], row["owners"])
                self.assertIn("unqualified", row["reason"])
            if row["classification"] == "engine-result-or-type-followup":
                self.assertIn("not certified", row["reason"])

    def test_changed_body_or_release_tag_requires_a_new_review(self):
        for field, value in [("body", self.snapshot["source"]["body"] + "\nnew change"), ("tag_name", "v2.0.1")]:
            changed = copy.deepcopy(self.snapshot["source"])
            changed[field] = value
            with self.assertRaises(ValueError):
                audit.audit(changed)

    def test_overlapping_explicit_dispositions_fail_instead_of_silently_overwriting(self):
        with patch.object(audit, "GROUPS", [([20], [0], "first"), ([19], [0], "second")]):
            with self.assertRaises(ValueError):
                audit.mapped_rows()


if __name__ == "__main__":
    unittest.main()
