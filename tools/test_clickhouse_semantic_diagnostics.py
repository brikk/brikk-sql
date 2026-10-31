import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe
from test_clickhouse_composition import RULE_CODES

DATA = pathlib.Path(__file__).resolve().parents[1] / "vendor/data/clickhouse-semantic"


class ClickhouseSemanticDiagnosticsEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = json.loads((DATA / "diagnostics-brikk.json").read_text())
        cls.cases = json.loads((DATA / "composition-cases.json").read_text())["execution"]
        cls.historical = json.loads((DATA / "composition-brikk.json").read_text())
        cls.native = json.loads((DATA / "composition-native/26.10.2.103.json").read_text())["execution"]

    def test_public_diagnostics_preserve_all_existing_rule_and_unknown_evidence(self):
        self.assertEqual(50, len(self.rows))
        self.assertEqual([c["id"] for c in self.cases], [r["id"] for r in self.rows])
        for case, row, old in zip(self.cases, self.rows, self.historical, strict=True):
            self.assertEqual(case["sql"], row["input_sql"])
            self.assertEqual("26.10.2.103", row["engine_version"])
            self.assertEqual(old["rules"], sorted({e["rule"] for e in row["errors"]}))
            self.assertEqual(old["unknown_reasons"], sorted({u["reason"] for u in row["unknowns"]}))
            for forbidden in ("accepted", "is_valid", "natively_accepted", "result_type"):
                self.assertNotIn(forbidden, row)
            for error in row["errors"]:
                self.assertEqual("OFFLINE_SEMANTIC", error["source"])
                self.assertEqual("ERROR", error["severity"])
                self.assertEqual(RULE_CODES[error["rule"]], error["native_error_code"])
                self.assertTrue(error["message"].strip())
            for unknown in row["unknowns"]:
                self.assertNotIn("severity", unknown)
                self.assertNotIn("diagnostic", unknown)

    def test_every_finding_has_a_nonempty_authored_utf16_span(self):
        for row in self.rows:
            source = row["input_sql"].encode("utf-16-le")
            for finding in row["errors"] + row["unknowns"]:
                self.assertGreaterEqual(finding["start"], 0)
                self.assertGreater(finding["end"], finding["start"])
                self.assertLessEqual(finding["end"] * 2, len(source))
                text = source[finding["start"] * 2:finding["end"] * 2].decode("utf-16-le")
                self.assertEqual(finding["text"], text, row["id"])
                self.assertTrue(text.strip(), row["id"])
        rows = {r["id"]: r for r in self.rows}
        for suffix, text in (("E01", "trim"), ("E04", "concat"), ("E06", "toString"),
                             ("E09", "multiply"), ("E14", "_"), ("E16", "_129"),
                             ("E19", "lower"), ("E22", "toString"), ("E38", "1"), ("E39", "_")):
            self.assertEqual(text, rows[f"CHSEM-004-{suffix}"]["errors"][0]["text"])

    def test_exposed_errors_retain_native_backing_without_relabelling_parser_acceptance(self):
        for case, row, native in zip(self.cases, self.rows, self.native, strict=True):
            if native["returncode"] == 0:
                self.assertEqual([], row["errors"], row["id"])
            if row["errors"]:
                expected = probe.execution_expectation(case, "26.10.2.103")
                self.assertNotEqual(0, native["returncode"], row["id"])
                self.assertIn(expected["error_code"], {e["native_error_code"] for e in row["errors"]})
                self.assertRegex(native["stderr"], rf"Code: {expected['error_code']}\. ")
        rows = {r["id"]: r for r in self.rows}
        for suffix in ("E24", "E26", "E27", "E28", "E29", "E31", "E32", "E45", "E50"):
            self.assertEqual([], rows[f"CHSEM-004-{suffix}"]["errors"])
            self.assertTrue(rows[f"CHSEM-004-{suffix}"]["unknowns"])


if __name__ == "__main__":
    unittest.main()
