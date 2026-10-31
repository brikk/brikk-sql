import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

DATA = pathlib.Path(__file__).resolve().parents[1] / "vendor/data/clickhouse-semantic"


class ClickhouseParenthesizedLambdaEvidenceTest(unittest.TestCase):
    def test_empty_generated_parser_batch_is_typed_not_a_fake_acceptance(self):
        empty = probe.generated_parsing_query([])
        self.assertIn("CAST([] AS Array(Tuple(String,String)))", empty)
        self.assertNotIn("arrayJoin([])", empty)
        nonempty = probe.generated_parsing_query(["('id','00')"])
        self.assertIn("arrayJoin([('id','00')])", nonempty)

    def test_parser_gap_refinements_preserve_the_historical_snapshots(self):
        refinements = json.loads((DATA / "paren-lambda-refinements.json").read_text())
        self.assertEqual("CHSEM-011", refinements["task"])
        self.assertEqual(["CHSEM-003-E26"], [r["id"] for r in refinements["cases"]])
        self.assertEqual(["UInt8", "String"], refinements["cases"][0]["formal_types"])
        old = {r["id"]: r for r in json.loads((DATA / "lambda-input-brikk.json").read_text())}
        self.assertEqual("CHSEM-011", old["CHSEM-003-E26"]["known_parser_gap"])
        self.assertEqual({"CHSEM-004-E29", "CHSEM-004-E32", "CHSEM-004-E46"}, {r["id"] for r in refinements["rules"]})
        self.assertTrue(all(r["rules"] == [] for r in refinements["rules"]))
        resolved = next(r for r in refinements["rules"] if r["id"] == "CHSEM-004-E32")
        self.assertEqual(["RESULT_FAMILY"], resolved["unknown_reasons"])
        old_results = {r["id"]: r for r in json.loads((DATA / "result-brikk.json").read_text())}
        self.assertEqual("UNKNOWN", old_results["CHSEM-005-E33"]["result_type"])
        self.assertEqual("Array(String)", refinements["results"][0]["type"])
        witness = {r["id"]: r for r in json.loads((DATA / "result-native/26.10.2.103.json").read_text())["execution"]}
        self.assertEqual("Array(String)\n", witness["CHSEM-005-E33"]["stdout"])

    def test_original_generated_native_controls_do_not_claim_every_result_is_researched(self):
        fixture = json.loads((DATA / "paren-lambda-cases.json").read_text())
        self.assertEqual(13, len(fixture["execution"]))
        self.assertEqual(2, len(fixture["cases"]))
        rows = {r["id"]: r for r in json.loads((DATA / "paren-lambda-brikk.json").read_text())}
        for version in ("26.8.34.13", "26.10.2.103"):
            report = json.loads((DATA / f"paren-lambda-native/{version}.json").read_text())
            parsed = {r["id"]: r for r in report["parsed"]}
            for case in probe.parsing_cases(fixture):
                self.assertEqual(probe.parsing_expectation(case, version), parsed[case["id"]]["formatted"] is not None)
            self.assertEqual(13, len(report["brikk_execution"]))
        for suffix in ("E10", "E11", "E13"):
            row = rows[f"CHSEM-011-{suffix}"]
            self.assertEqual("UNKNOWN", row["result_type"])
            self.assertEqual([], row["rules"])
            self.assertTrue(row["unknowns"])
            self.assertNotIn("PARSER_GAP", row["unknowns"])
        self.assertEqual(["RIGHT_OPERAND_ARITY"], rows["CHSEM-011-E08"]["rules"])
        self.assertEqual(["ARRAY_MAP_ARITY"], rows["CHSEM-011-E09"]["rules"])
