import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseCloseoutEvidenceTest(unittest.TestCase):
    def test_qualified_and_deferred_scripts_have_exact_native_and_generated_outcomes(self):
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for name, count in (("closeout", 18), ("closeout-missing", 6)):
            fixture, typed = DATA / (name + "-cases.json"), DATA / (name + "-brikk.json")
            cases, rows = json.loads(fixture.read_text())["execution"], json.loads(typed.read_text())
            self.assertEqual(count, len(cases))
            for engine in engines:
                report = json.loads((DATA / (name + "-native") / (engine["version"] + ".json")).read_text())
                self.assertEqual(engine, report["engine"])
                self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
                self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
                self.assertEqual(hashlib.sha256(typed.read_bytes()).hexdigest(), report["brikk_sha256"])
                for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                    self.assertEqual(case["id"], row["id"])
                    self.assertEqual(case["id"], original["id"])
                    self.assertEqual(case["id"], generated["id"])
                    self.assertEqual(case["sql"], row["input_sql"])
                    self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                    self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                    self.assertEqual(original["stdout"], generated["stdout"])
                    self.assertNotIn("skipped", original)
                    self.assertNotIn("skipped", generated)

    def test_only_qualified_successful_types_are_claimed_as_native_matches(self):
        cases = json.loads((DATA / "closeout-cases.json").read_text())["execution"]
        rows = json.loads((DATA / "closeout-brikk.json").read_text())
        native = json.loads((DATA / "closeout-native/26.10.2.103.json").read_text())["execution"]
        self.assertEqual(12, sum(case["qualified"] for case in cases))
        self.assertEqual(6, sum(not case["qualified"] for case in cases))
        for case, row, observed in zip(cases, rows, native, strict=True):
            self.assertEqual(case["qualified"], row["qualified"])
            self.assertEqual(case["types"], row["types"])
            self.assertEqual(case["rules"], row["rules"])
            if not case["qualified"]:
                self.assertTrue(case["gap"])
            if case["qualified"] and observed["returncode"] == 0:
                self.assertEqual(row["types"], [line.split("\t")[-1] for line in observed["stdout"].splitlines()])
            if case["rules"]:
                self.assertIn("Code: " + ("153" if case["rules"] == ["DIVISION_BY_ZERO"] else "407") + ".", observed["stderr"])

    def test_generic_fallback_deviations_remain_explicitly_unqualified(self):
        rows = {row["id"]: row for row in json.loads((DATA / "closeout-missing-brikk.json").read_text())}
        native = {row["id"]: row for row in json.loads((DATA / "closeout-missing-native/26.10.2.103.json").read_text())["execution"]}
        for id, frontend, actual in (("G01", "Nullable(Int32)", "UInt16"), ("G02", "Nullable(Float64)", "Float64")):
            self.assertFalse(rows[id]["qualified"])
            self.assertEqual([frontend], rows[id]["types"])
            self.assertTrue(native[id]["stdout"].endswith("\t" + actual + "\n"))
            self.assertNotEqual(frontend, actual)
        for id in ("G03", "G04", "G05", "G06"):
            self.assertFalse(rows[id]["qualified"])
            self.assertEqual(["UNKNOWN"], rows[id]["types"])
        report = (DATA / "closeout-README.md").read_text()
        for topic in ("Generic fallbacks", "Unqualified overloads", "External connection", "Cross-dialect", "consumer"):
            self.assertIn(topic, report)
