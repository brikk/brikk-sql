import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseSemanticRoundsEvidenceTest(unittest.TestCase):
    rounds = ("qualification",)

    def test_rounds_have_complete_hash_pinned_original_and_generated_evidence(self):
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for name in self.rounds:
            fixture = DATA / f"{name}-cases.json"
            brikk = DATA / f"{name}-brikk.json"
            cases = json.loads(fixture.read_text())["execution"]
            rows = json.loads(brikk.read_text())
            self.assertEqual([c["id"] for c in cases], [r["id"] for r in rows])
            for case, row in zip(cases, rows, strict=True):
                self.assertEqual(case["sql"], row["input_sql"])
                self.assertEqual(case["type"], row["result_type"])
                self.assertEqual(sorted(case["rules"]), row["rules"])
                self.assertTrue(row["generated"])
            for engine in engines:
                version = engine["version"]
                report = json.loads((DATA / f"{name}-native/{version}.json").read_text())
                self.assertEqual(engine, report["engine"])
                self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
                self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
                self.assertEqual(version + "\n", report["version_query"]["stdout"])
                self.assertEqual(0, report["version_query"]["returncode"])
                for phase in ("execution", "brikk_execution", "parsed"):
                    self.assertEqual([c["id"] for c in cases], [r["id"] for r in report[phase]])
                originals = {r["id"]: r["formatted"] for r in report["parsed"]}
                formatted = [json.loads(line) for line in report["brikk_parse_query"]["stdout"].splitlines()]
                self.assertEqual(0, report["brikk_parse_query"]["returncode"])
                self.assertEqual([c["id"] for c in cases if probe.parsing_expectation(c, version)], [r["id"] for r in formatted])
                for row in formatted:
                    self.assertIsNotNone(row["formatted"])
                    self.assertEqual(originals[row["id"]], row["formatted"])
                for case, original, generated in zip(cases, report["execution"], report["brikk_execution"], strict=True):
                    label = f"{name}/{version}/{case['id']}"
                    expected = probe.execution_expectation(case, version)
                    self.assertTrue(probe.expected_execution(expected, original), label)
                    self.assertTrue(probe.expected_execution(expected, generated), label)
                    self.assertEqual(original["stdout"], generated["stdout"], label)
                    self.assertEqual(original["returncode"], generated["returncode"], label)
                    self.assertNotIn("skipped", original)
                    self.assertNotIn("skipped", generated)


if __name__ == "__main__":
    unittest.main()
