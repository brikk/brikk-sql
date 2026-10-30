import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess

import probe_datafusion_spice as probe


class ReferenceProtocolTest(unittest.TestCase):
    def test_fixture_ids_and_execution_are_explicit(self):
        cases = probe.load_cases(probe.FIXTURES)
        by_id = {c["id"]: c for c in cases}
        for id in ["DFS-explain-analyze", "DFS-search-nested", "DFS-ai-named", "DFS-copy", "DFS-delete"]:
            self.assertFalse(by_id[id]["execute"], id)
        self.assertTrue(by_id["DFS-unknown-function"]["execute"])
        self.assertIn("DFS-script-bad-tail", by_id)
        self.assertIn("DFS-unicode-error", by_id)

    def test_failed_process_is_not_a_sql_rejection(self):
        with patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(1, "oracle")):
            with self.assertRaises(subprocess.CalledProcessError):
                probe.run_reference(Path("oracle"), [{"id": "x"}])

    def test_missing_duplicate_and_wrong_version_responses_are_errors(self):
        requests = [{"id": "a"}, {"id": "b"}]
        for rows in [[], [{"id": "a"}, {"id": "a"}],
                     [{"id": "a", "protocol_version": 2}, {"id": "b", "protocol_version": 2}]]:
            result = subprocess.CompletedProcess([], 0, "\n".join(json.dumps(r) for r in rows), "")
            with patch.object(subprocess, "run", return_value=result):
                with self.assertRaises(RuntimeError):
                    probe.run_reference(Path("oracle"), requests)

    def test_two_failures_do_not_establish_equivalence(self):
        self.assertEqual(probe.compare({"status": "rejected"}, {"status": "rejected"}), "original-not-accepted")

    def test_wrong_profile_and_unavailable_do_not_count_as_native_evidence(self):
        for status in ["accepted", "unavailable"]:
            row = {"id": "a", "protocol_version": 1, "status": status, "profile": {"id": "wrong"}}
            result = subprocess.CompletedProcess([], 0, json.dumps(row), "")
            with patch.object(subprocess, "run", return_value=result):
                with self.assertRaises(RuntimeError):
                    probe.run_reference(Path("oracle"), [{"id": "a"}], expected_profile="spice-2.4.0-parser")

    def test_type_changes_are_visible_even_when_values_match(self):
        left = {"status": "accepted", "fields": [{"name": "v", "type": "Int64"}], "rows": [["1"]]}
        right = {**left, "fields": [{"name": "v", "type": "Utf8"}]}
        self.assertEqual(probe.compare(left, right), "different-observation")
        self.assertEqual(probe.compare(left, left), "same-observation")

    def test_http_null_and_nested_types_remain_distinct(self):
        left = {"status": "accepted", "rows": [{"v": None}],
                "type_observation": {"status": "accepted", "rows": [{"v": "Utf8"}]}}
        right = {**left, "rows": [{"v": "null"}]}
        self.assertEqual(probe.compare(left, right), "different-observation")
        self.assertEqual(probe.compare(left, {**left, "type_observation": {"status": "rejected"}}), "incomplete-type-evidence")

    def test_wrong_runtime_binary_is_refused_before_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            binary = Path(tmp) / "spiced"
            binary.write_bytes(b"not the pinned runtime")
            with self.assertRaises(ValueError):
                with probe.spice_runtime(binary):
                    self.fail("runtime should not start")


class CommittedEvidenceTest(unittest.TestCase):
    def test_round_two_accepted_upstream_queries_keep_their_observations(self):
        root = probe.ROOT / "vendor/data/datafusion-spice/round2"
        capture = json.loads((root / "datafusion-observations.json").read_text())
        self.assertEqual(capture["fixtures_sha256"], probe.sha256(root / "cases.json"))
        self.assertEqual(capture["brikk_observations_sha256"], probe.sha256(root / "brikk-observations.json"))
        cases = capture["profiles"]["datafusion-55.2.0"]["cases"]
        self.assertEqual([c["id"] for c in cases], [c["id"] for c in probe.load_cases(root / "cases.json")])
        accepted = [c for c in cases if c["original"].get("execute", {}).get("status") == "accepted"]
        self.assertGreaterEqual(len(accepted), 31)
        self.assertTrue(all(c["comparison"] == "same-observation" for c in accepted))

    def test_captures_match_current_inputs_and_cover_all_expected_cases(self):
        fixtures = probe.load_cases(probe.FIXTURES)
        brikk = probe.ROOT / "vendor/data/datafusion-spice/brikk-observations.json"
        for relative, profile, execution_only in [
            ("datafusion/55.2.0/observations.json", "datafusion-55.2.0", False),
            ("spice/2.4.0/parser-observations.json", "spice-2.4.0-parser", False),
            ("spice/2.4.0/runtime-observations.json", "spice-2.4.0-runtime", True),
        ]:
            capture = json.loads((probe.ROOT / "vendor/data" / relative).read_text())
            self.assertEqual(capture["fixtures_sha256"], probe.sha256(probe.FIXTURES))
            self.assertEqual(capture["brikk_observations_sha256"], probe.sha256(brikk))
            expected = [c["id"] for c in fixtures if not execution_only or c["execute"]]
            self.assertEqual([c["id"] for c in capture["profiles"][profile]["cases"]], expected)

    def test_native_controls_keep_dialect_differences_and_utf16_locations(self):
        def cases(path, profile):
            root = json.loads((probe.ROOT / "vendor/data" / path).read_text())
            return {c["id"]: c for c in root["profiles"][profile]["cases"]}
        upstream = cases("datafusion/55.2.0/observations.json", "datafusion-55.2.0")
        spice = cases("spice/2.4.0/runtime-observations.json", "spice-2.4.0-runtime")
        parser = cases("spice/2.4.0/parser-observations.json", "spice-2.4.0-parser")
        self.assertEqual(upstream["DFS-xor-caret"]["original"]["execute"]["rows"], [["6"]])
        self.assertEqual(spice["DFS-xor-caret"]["original"]["rows"], [{"v": {"decimal_lexeme": "125.0"}}])
        self.assertEqual(spice["DFS-regex-flags"]["original"]["rows"], [{"v": True}])
        self.assertEqual(spice["DFS-case-folding"]["original"]["status"], "rejected")
        self.assertEqual(parser["DFS-unicode-error"]["original"]["parse"]["position"]["utf16_offset"], 25)
        self.assertEqual(parser["DFS-script-bad-tail"]["original"]["parse"]["status"], "rejected")

    def test_reference_lockfiles_match_the_provenance(self):
        for product, version in [("datafusion", "55.2.0"), ("spice", "2.4.0")]:
            manifest = json.loads((probe.ROOT / f"vendor/data/{product}/{version}/dependency-provenance.json").read_text())
            lock = manifest["reference_lockfile"]
            self.assertEqual(probe.sha256(probe.ROOT / lock["path"]), lock["sha256"])


if __name__ == "__main__":
    unittest.main()
