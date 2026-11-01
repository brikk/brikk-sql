import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe
import capture_clickhouse_semantic_contract_sources as sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseScriptSettingsEvidenceTest(unittest.TestCase):
    def test_scripts_run_in_exact_isolated_sessions_with_original_generated_agreement(self):
        fixture = DATA / "script-settings-cases.json"
        brikk = DATA / "script-settings-brikk.json"
        cases = json.loads(fixture.read_text())["execution"]
        typed = json.loads(brikk.read_text())
        self.assertEqual(18, len(cases))
        self.assertEqual([c["id"] for c in cases], [r["id"] for r in typed])
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((DATA / f"script-settings-native/{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
            for case, row, original, generated in zip(cases, typed, report["execution"], report["brikk_execution"], strict=True):
                self.assertEqual(case["id"], row["id"])
                self.assertEqual(case["rules"], row["rules"])
                self.assertEqual(case["sql"], row["input_sql"])
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                self.assertEqual(original["stdout"], generated["stdout"])
                if engine["version"] == "26.10.2.103" and row["rules"]:
                    self.assertIn("Code: 153.", original["stderr"])

    def test_source_captures_pin_session_application_and_reset_order(self):
        captured = json.loads((DATA / "script-settings-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.SCRIPT_SETTINGS), len(snapshot["sources"]))
        for spec, record in zip(sources.SCRIPT_SETTINGS, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], record["path"])
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], record["url"])
            self.assertIn(spec[1], record["excerpts"][0]["text"])
        text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
        for guard in ("getSessionContext", "applySettingsChanges(changes)", "resetSettingsToDefaultValue", "Keyword::DEFAULT", "compatibility"):
            self.assertIn(guard, text)
