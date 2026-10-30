import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
FIXTURE = DATA / "lambda-input-cases.json"


class ClickhouseLambdaInputEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text())
        cls.engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        cls.brikk = json.loads((DATA / "lambda-input-brikk.json").read_text())

    def test_stable_bounded_inputs_have_explicit_unknowns_and_one_named_parser_gap(self):
        cases = self.fixture["execution"]
        self.assertEqual(28, len(cases))
        self.assertEqual({f"CHSEM-003-E{i:02}" for i in range(1, 29)}, {c["id"] for c in cases})
        self.assertEqual({e["version"] for e in self.engines}, set(self.fixture["proven_versions"]))
        gaps = {c["id"]: c["brikk_parser_gap"] for c in cases if "brikk_parser_gap" in c}
        self.assertEqual({"CHSEM-003-E26": "CHSEM-011"}, gaps)
        for case in cases:
            self.assertEqual("CHSEM-003", case["task"])
            self.assertTrue(case["sql"].startswith("SELECT "))
            self.assertNotIn(";", case["sql"])
            self.assertNotIn("setup", case)
            self.assertNotIn("release", case)  # Both pins execute every vector.
            if "brikk_parser_gap" not in case:
                for field in ("formal_types", "implicit_types"):
                    self.assertIsInstance(case[field], list)
                    self.assertTrue(all(isinstance(value, str) and value for value in case[field]))
            for engine in self.engines:
                expected = probe.execution_expectation(case, engine["version"])
                self.assertNotEqual("stdout" in expected, "error_code" in expected)

    def test_recorded_brikk_bindings_match_each_exact_fixture_without_claiming_native_validation(self):
        self.assertEqual([c["id"] for c in self.fixture["execution"]], [r["id"] for r in self.brikk])
        for case, row in zip(self.fixture["execution"], self.brikk, strict=True):
            with self.subTest(id=case["id"]):
                self.assertEqual(case["sql"], row["input_sql"])
                if "brikk_parser_gap" in case:
                    self.assertEqual(case["brikk_parser_gap"], row["known_parser_gap"])
                    for unsupported in ("generated", "formal_types", "implicit_types"):
                        self.assertNotIn(unsupported, row)
                else:
                    self.assertEqual(case["formal_types"], row["formal_types"])
                    self.assertEqual(case["implicit_types"], row["implicit_types"])
                    self.assertTrue(row["generated"].startswith("SELECT "))
        by_id = {r["id"]: r for r in self.brikk}
        self.assertEqual(["UInt8", "UNKNOWN"], by_id["CHSEM-003-E18"]["implicit_types"])
        self.assertEqual(["UInt8", "UNKNOWN"], by_id["CHSEM-003-E23"]["formal_types"])
        self.assertEqual(["UInt8", "UNKNOWN"], by_id["CHSEM-003-E27"]["formal_types"])
        # A known parameter type is not a verdict: the nested anonymous placeholder
        # still has a native BAD_ARGUMENTS failure, deferred to composition validation.
        self.assertEqual(["String"], by_id["CHSEM-003-E28"]["implicit_types"])
        case = next(c for c in self.fixture["execution"] if c["id"] == "CHSEM-003-E28")
        self.assertEqual("BAD_ARGUMENTS", probe.execution_expectation(case, "26.10.2.103")["error_name"])

    def test_original_native_type_and_error_evidence_is_complete_on_both_exact_pins(self):
        cases = self.fixture["execution"]
        for engine in self.engines:
            version = engine["version"]
            report = json.loads((DATA / "lambda-input-native" / f"{version}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(0, report["version_query"]["returncode"])
            self.assertEqual(version + "\n", report["version_query"]["stdout"])
            self.assertIsNone(report["brikk_sha256"])
            self.assertIsNone(report["brikk_parse_query"])
            self.assertEqual([], report["brikk_execution"])
            self.assertEqual(0, report["parse_query"]["returncode"])
            self.assertEqual(report["parsed"], [json.loads(line) for line in report["parse_query"]["stdout"].splitlines()])
            self.assertEqual([c["id"] for c in cases], [r["id"] for r in report["parsed"]])
            self.assertEqual([c["id"] for c in cases], [r["id"] for r in report["execution"]])
            for case, parsed, result in zip(cases, report["parsed"], report["execution"], strict=True):
                label = f"{version}/{case['id']}"
                self.assertEqual(probe.parsing_expectation(case, version), parsed["formatted"] is not None, label)
                self.assertNotIn("skipped", result, label)
                expected = probe.execution_expectation(case, version)
                self.assertTrue(probe.expected_execution(expected, result), label)
                if "error_code" in expected:
                    self.assertRegex(result["stderr"], rf"Code: {expected['error_code']}\. ", label)


if __name__ == "__main__":
    unittest.main()
