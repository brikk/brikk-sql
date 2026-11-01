import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseScriptTypingEvidenceTest(unittest.TestCase):
    def test_original_and_generated_whole_sessions_match_the_exact_pins(self):
        fixture = DATA / "script-typing-cases.json"
        brikk = DATA / "script-typing-brikk.json"
        cases = json.loads(fixture.read_text())["execution"]
        rows = json.loads(brikk.read_text())
        self.assertEqual(24, len(cases))
        self.assertEqual([c["id"] for c in cases], [r["id"] for r in rows])
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((DATA / f"script-typing-native/{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
            for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                self.assertEqual(case["id"], original["id"])
                self.assertEqual(case["id"], generated["id"])
                self.assertEqual(case["sql"], row["input_sql"])
                self.assertEqual(case["types"], row["types"])
                self.assertEqual(case["rules"], row["rules"])
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                self.assertEqual(original["stdout"], generated["stdout"])
                self.assertNotIn("skipped", original)
                self.assertNotIn("skipped", generated)

    def test_known_successful_types_match_native_but_observed_defaults_do_not_become_facts(self):
        rows = json.loads((DATA / "script-typing-brikk.json").read_text())
        native = json.loads((DATA / "script-typing-native/26.10.2.103.json").read_text())["execution"]
        for row, observed in zip(rows, native, strict=True):
            if observed["returncode"] != 0:
                continue
            types = [line.split("\t")[-1] for line in observed["stdout"].splitlines()]
            self.assertEqual(len(row["types"]), len(types))
            for typed, actual in zip(row["types"], types, strict=True):
                if typed != "UNKNOWN":
                    self.assertEqual(typed, actual, row["id"])
        typed = {r["id"]: r for r in rows}
        for suffix in ("E01", "E10", "E14", "E16"):
            self.assertEqual(["UNKNOWN"], typed["CHSEM-018-" + suffix]["types"])
        self.assertEqual(["String", "UNKNOWN"], typed["CHSEM-018-E24"]["types"])

    def test_conditional_types_parse_and_runtime_failure_categories_are_not_conflated(self):
        rows = {r["id"]: r for r in json.loads((DATA / "script-typing-brikk.json").read_text())}
        native = {r["id"]: r for r in json.loads((DATA / "script-typing-native/26.10.2.103.json").read_text())["execution"]}
        for suffix, type, rule, code in (
            ("E18", "UNKNOWN", "DECIMAL_DIVISION_SCALE_OVERFLOW", 407),
            ("E19", "Decimal(9, 0)", "DECIMAL_DIVISION_NARROWING_OVERFLOW", 407),
            ("E20", "UInt8", "DIVISION_BY_ZERO", 153),
        ):
            id = "CHSEM-018-" + suffix
            self.assertEqual([type], rows[id]["types"])
            self.assertEqual([rule], rows[id]["rules"])
            self.assertIn(f"Code: {code}.", native[id]["stderr"])
