import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_lowering as probe

DATA = pathlib.Path(__file__).resolve().parents[1] / "vendor/data/clickhouse-semantic"


class ClickhouseLoweringAssessmentTest(unittest.TestCase):
    def test_outcome_comparison_never_certifies_two_errors_or_normalizes_strings(self):
        error = {"status": "error"}
        value = {"status": "value", "value": ["Ä!", None]}
        self.assertEqual("both-error-not-equivalence", probe.comparison(error, error))
        self.assertEqual("different-outcome", probe.comparison(error, value))
        self.assertEqual("same-finite-value", probe.comparison(value, value))
        self.assertEqual("different-value", probe.comparison(value, {"status": "value", "value": ["ä!", None]}))
        self.assertEqual("different-value", probe.comparison(value, {"status": "value", "value": ["Ä!", "null"]}))
        with self.assertRaises(RuntimeError):
            probe.observation({"returncode": None, "stdout": "", "stderr": "Host deadline exceeded"}, "clickhouse")

    def test_pinned_assessment_retains_matches_counterexamples_and_production_refusal(self):
        fixture = DATA / "lowering-cases.json"
        duckdb = DATA / "lowering-duckdb.json"
        report = json.loads((DATA / "lowering-assessment.json").read_text())
        cases = json.loads(fixture.read_text())["cases"]
        self.assertEqual(8, len(cases))
        self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
        self.assertEqual(hashlib.sha256(duckdb.read_bytes()).hexdigest(), report["duckdb_sha256"])
        self.assertFalse(report["automatic_lowering_enabled"])
        for name, image in (("trino", probe.TRINO), ("fe", probe.FE), ("be", probe.BE)):
            self.assertEqual(image, report["images"][name]["requested_image"])
            self.assertIn(image, report["images"][name]["repo_digests"])
        self.assertEqual("484", report["versions"]["trino"]["stdout"].strip().strip('"'))
        for part in ("frontend", "backend"):
            self.assertIn("doris-4.1.5-", report["versions"][part]["stdout"])
        self.assertEqual("v2.0.0", json.loads(duckdb.read_text())["version"])
        native = report["clickhouse"]["26.10.2.103"]["cases"]
        old = report["clickhouse"]["26.8.34.13"]["cases"]
        self.assertEqual([c["id"] for c in cases], [c["id"] for c in report["cases"]])
        for case, row, source, prior in zip(cases, report["cases"], native, old, strict=True):
            self.assertEqual("error", prior["source"]["status"])
            self.assertIn("Code: 62.", prior["source"]["raw"]["stderr"])
            if source["source"]["status"] == "value":
                self.assertEqual(source["source"]["value"], source["explicit"]["value"])
                self.assertEqual(source["source"]["value"], prior["explicit"]["value"])
            else:
                self.assertEqual("CHSEM-010-E05", case["id"])
                self.assertIn("Code: 190.", source["source"]["raw"]["stderr"])
                self.assertIn("Code: 190.", source["explicit"]["raw"]["stderr"])
            for target in ("duckdb", "trino", "doris"):
                observed = row["targets"][target]
                self.assertEqual(case["targets"][target], observed["candidate"])
                self.assertEqual(probe.comparison(source["source"], observed), row["comparison"][target])
        by_id = {r["id"]: r for r in report["cases"]}
        for target in ("duckdb", "trino", "doris"):
            self.assertEqual("same-finite-value", by_id["CHSEM-010-E01"]["comparison"][target])
            self.assertEqual("different-outcome", by_id["CHSEM-010-E06"]["comparison"][target])
        self.assertEqual("different-value", by_id["CHSEM-010-E02"]["comparison"]["duckdb"])
        self.assertEqual("different-outcome", by_id["CHSEM-010-E05"]["comparison"]["duckdb"])
