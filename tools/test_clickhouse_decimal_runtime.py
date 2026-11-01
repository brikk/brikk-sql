import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
RUNTIME_RULES = {"DECIMAL_DIVISION_NARROWING_OVERFLOW", "DECIMAL_DIVISION_MULTIPLIER_OVERFLOW", "DECIMAL_DIVISION_NUMERATOR_OVERFLOW"}


class ClickhouseDecimalRuntimeEvidenceTest(unittest.TestCase):
    def test_reported_runtime_failures_match_native_407_and_successes_are_never_flagged(self):
        rows = json.loads((DATA / "decimal-runtime-brikk.json").read_text())
        native = json.loads((DATA / "decimal-runtime-native/26.10.2.103.json").read_text())["execution"]
        self.assertEqual(56, len(rows))
        self.assertEqual(RUNTIME_RULES, {rule for r in rows for rule in r["rules"] if rule in RUNTIME_RULES})
        for row, observed in zip(rows, native, strict=True):
            self.assertEqual(row["id"], observed["id"])
            for rule in row["rules"]:
                code = 153 if rule == "DIVISION_BY_ZERO" else 69 if rule == "DECIMAL_DIVISION_SCALE" else 407
                self.assertIn(f"Code: {code}.", observed["stderr"])
                if rule == "DECIMAL_DIVISION_NARROWING_OVERFLOW":
                    self.assertIn("operand does not fit", observed["stderr"])
            if observed["returncode"] == 0:
                self.assertEqual([], row["rules"])

    def test_big_decimal_wrapping_controls_are_preserved_as_successful_unproved_dispatch(self):
        cases = json.loads((DATA / "decimal-runtime-cases.json").read_text())["execution"]
        typed = {r["id"]: r for r in json.loads((DATA / "decimal-runtime-brikk.json").read_text())}
        native = {r["id"]: r for r in json.loads((DATA / "decimal-runtime-native/26.10.2.103.json").read_text())["execution"]}
        controls = [c for c in cases if "9999999999999999999" in c["sql"]]
        self.assertEqual(2, len(controls))
        for case in controls:
            self.assertEqual([], typed[case["id"]]["rules"])
            self.assertEqual(0, native[case["id"]]["returncode"])
            self.assertTrue(native[case["id"]]["stdout"].startswith("-"))

    def test_source_pins_constant_guard_order_and_disabled_big_integer_multiply_checks(self):
        captured = json.loads((DATA / "decimal-runtime-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(1, len(captured["snapshots"]))
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.DECIMAL_RUNTIME), len(snapshot["sources"]))
        for spec, row in zip(sources.DECIMAL_RUNTIME, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], row["path"])
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], row["url"])
            self.assertIn(spec[1], row["excerpts"][0]["text"])
        text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
        for guard in ("castConstantToNative", "mulOverflow(scale, scale", "mulOverflow(a, scale",
                      "Overflow check is not implemented for big integers.", "mulOverflow(Int128", "mulOverflow(Int256"):
            self.assertIn(guard, text)
        wide = snapshot["sources"][-1]["excerpts"][0]["text"]
        self.assertGreaterEqual(wide.count("return false;"), 4)

    def test_named_refinements_strengthen_only_the_three_previously_unproved_runtime_cases(self):
        old = {r["id"]: r for r in json.loads((DATA / "decimal-diagnostics-brikk.json").read_text())}
        new = {r["id"]: r for r in json.loads((DATA / "decimal-runtime-refinement-brikk.json").read_text())}
        refinements = json.loads((DATA / "decimal-runtime-refinements.json").read_text())
        self.assertEqual(3, len(refinements))
        for id, row in old.items():
            self.assertEqual(refinements.get(id, row["rules"]), new[id]["rules"])
            if id in refinements:
                self.assertEqual([], row["rules"])

    def test_native_session_settings_and_overrides_match_generated_scripts(self):
        fixture = DATA / "decimal-runtime-script-cases.json"
        brikk = DATA / "decimal-runtime-script-brikk.json"
        cases = json.loads(fixture.read_text())["execution"]
        rows = json.loads(brikk.read_text())
        self.assertEqual(8, len(cases))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((DATA / f"decimal-runtime-script-native/{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
            for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                self.assertEqual(case["id"], row["id"])
                self.assertEqual(case["rules"], row["rules"])
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                self.assertEqual(original["stdout"], generated["stdout"])
