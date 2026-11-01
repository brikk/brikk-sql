import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
CODES = {"DIVISION_BY_ZERO": 153, "DECIMAL_DIVISION_SCALE": 69, "DECIMAL_DIVISION_SCALE_OVERFLOW": 407}


class ClickhouseDecimalDiagnosticsEvidenceTest(unittest.TestCase):
    def test_every_reported_failure_has_its_own_native_category_and_no_success_is_flagged(self):
        rows = json.loads((DATA / "decimal-diagnostics-brikk.json").read_text())
        native = json.loads((DATA / "decimal-diagnostics-native/26.10.2.103.json").read_text())["execution"]
        self.assertEqual(47, len(rows))
        self.assertEqual(set(CODES), {rule for r in rows for rule in r["rules"]})
        for row, observed in zip(rows, native, strict=True):
            self.assertEqual(row["id"], observed["id"])
            for rule in row["rules"]:
                self.assertNotEqual(0, observed["returncode"])
                self.assertIn(f"Code: {CODES[rule]}.", observed["stderr"])
            if observed["returncode"] == 0:
                self.assertEqual([], row["rules"])

    def test_pinned_sources_cover_guard_order_narrowing_scaling_and_null_maps(self):
        capture = json.loads((DATA / "decimal-diagnostics-sources.json").read_text())
        self.assertEqual("Apache-2.0", capture["license"])
        self.assertEqual(1, len(capture["snapshots"]))
        snapshot = capture["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.DECIMAL_DIAGNOSTICS), len(snapshot["sources"]))
        for spec, row in zip(sources.DECIMAL_DIAGNOSTICS, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], row["path"])
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], row["url"])
            self.assertIn(spec[1], row["excerpts"][0]["text"])
        text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
        for guard in ("decimalCheckArithmeticOverflow", "left.getScale() + right.getScale()", "scale < x.scale",
                      "right_nullmap", "castConstantToNative", "mulOverflow(scale, scale", "ILLEGAL_DIVISION"):
            self.assertIn(guard, text)

    def test_refinements_do_not_replace_historical_typing_only_captures(self):
        old = {r["id"]: r for r in json.loads((DATA / "decimal-division-brikk.json").read_text())}
        new = {r["id"]: r for r in json.loads((DATA / "decimal-diagnostics-refinement-brikk.json").read_text())}
        refinements = json.loads((DATA / "decimal-diagnostics-refinements.json").read_text())
        self.assertEqual(5, len(refinements))
        for id, rules in refinements.items():
            self.assertEqual([], old[id]["rules"])
            self.assertEqual(rules, new[id]["rules"])
            self.assertEqual(old[id]["result_type"], new[id]["result_type"])

    def test_whole_script_setting_evidence_uses_native_sessions_and_generated_sql(self):
        fixture = DATA / "decimal-diagnostics-script-cases.json"
        brikk = DATA / "decimal-diagnostics-script-brikk.json"
        cases = json.loads(fixture.read_text())["execution"]
        rows = json.loads(brikk.read_text())
        self.assertEqual(8, len(cases))
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for engine in engines:
            report = json.loads((DATA / f"decimal-diagnostics-script-native/{engine['version']}.json").read_text())
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
                if engine["version"] == "26.10.2.103":
                    for rule in row["rules"]:
                        self.assertIn(f"Code: {CODES[rule]}.", original["stderr"])
