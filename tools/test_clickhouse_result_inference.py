import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe
import probe_clickhouse_result_matrix as matrix

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
FIXTURE = DATA / "result-cases.json"


class ClickhouseResultInferenceEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text())
        cls.brikk = json.loads((DATA / "result-brikk.json").read_text())
        cls.engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]

    def test_result_cases_keep_known_types_unknowns_and_parser_gap_explicit(self):
        cases = self.fixture["execution"]
        self.assertEqual(40, len(cases))
        self.assertEqual({f"CHSEM-005-E{i:02}" for i in range(1, 41)}, {c["id"] for c in cases})
        self.assertEqual({e["version"] for e in self.engines}, set(self.fixture["proven_versions"]))
        self.assertEqual([c["id"] for c in cases], [r["id"] for r in self.brikk])
        for case, row in zip(cases, self.brikk, strict=True):
            self.assertEqual("CHSEM-005", case["task"])
            self.assertEqual(case["sql"], row["input_sql"])
            self.assertEqual(case["expected_type"], row["result_type"], case["id"])
            self.assertTrue(case["sql"].startswith(("SELECT ", "WITH ")))
            self.assertNotIn(";", case["sql"])
            for forbidden in ("setup", "release", "brikk_sql", "accepted", "is_valid"):
                self.assertNotIn(forbidden, case)
                self.assertNotIn(forbidden, row)
            if case.get("brikk_parser_gap"):
                self.assertEqual("CHSEM-011", row["known_parser_gap"])
                self.assertNotIn("generated", row)
                self.assertEqual("UNKNOWN", row["result_type"])
            else:
                self.assertTrue(row["generated"].startswith(("SELECT ", "WITH ")))

    def test_exact_native_type_witnesses_and_errors_are_complete_on_both_pins(self):
        cases = self.fixture["execution"]
        for engine in self.engines:
            version = engine["version"]
            report = json.loads((DATA / "result-native" / f"{version}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(0, report["version_query"]["returncode"])
            self.assertEqual(version + "\n", report["version_query"]["stdout"])
            self.assertEqual(0, report["parse_query"]["returncode"])
            self.assertEqual(report["parsed"], [json.loads(line) for line in report["parse_query"]["stdout"].splitlines()])
            for phase in ("parsed", "execution"):
                self.assertEqual([c["id"] for c in cases], [r["id"] for r in report[phase]])
            self.assertIsNone(report["brikk_sha256"])
            self.assertIsNone(report["brikk_parse_query"])
            self.assertEqual([], report["brikk_execution"])
            for case, parsed, result in zip(cases, report["parsed"], report["execution"], strict=True):
                label = f"{version}/{case['id']}"
                self.assertEqual(probe.parsing_expectation(case, version), parsed["formatted"] is not None, label)
                self.assertNotIn("skipped", result, label)
                expected = probe.execution_expectation(case, version)
                self.assertTrue(probe.expected_execution(expected, result), label)
                if "error_code" in expected:
                    self.assertRegex(result["stderr"], rf"Code: {expected['error_code']}\. ", label)
                if version == "26.10.2.103" and case["expected_type"] != "UNKNOWN":
                    self.assertEqual(case["expected_type"] + "\n", result["stdout"], label)
                    self.assertEqual(0, result["returncode"], label)

    def test_every_primitive_pair_uses_the_engine_observation_not_a_local_formula(self):
        folder = DATA / "result-matrix"
        raw = (folder / "input.json").read_bytes()
        source = json.loads(raw)
        self.assertEqual(matrix.matrix_input(), source)
        self.assertEqual(10, len(source["types"]))
        self.assertEqual(100, len(source["pairs"]))
        self.assertEqual({(a, b) for a in source["types"] for b in source["types"]},
                         {(p["left"], p["right"]) for p in source["pairs"]})
        brikk = json.loads((folder / "brikk.json").read_text())
        self.assertEqual({p["id"] for p in source["pairs"]}, set(brikk))
        for engine in self.engines:
            report = json.loads((folder / f"{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["input_sha256"])
            self.assertEqual(0, report["version_query"]["returncode"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(0, report["query"]["returncode"])
            self.assertEqual(report["types"], json.loads(report["query"]["stdout"]))
            self.assertEqual(report["types"], brikk)
            self.assertEqual(100, len(report["types"]))

    def test_type_knowledge_does_not_erase_runtime_or_unresearched_unknowns(self):
        rows = {r["id"]: r for r in self.brikk}
        for suffix, reason in (("E29", "ARRAY_LENGTHS"), ("E30", "RUNTIME_RESTRICTION"), ("E39", "TIMEZONE_VALUE")):
            row = rows[f"CHSEM-005-{suffix}"]
            self.assertNotEqual("UNKNOWN", row["result_type"])
            self.assertIn(reason, row["unknown_reasons"])
        for suffix in ("E15", "E16", "E21", "E22", "E25", "E26", "E33", "E35", "E40"):
            self.assertEqual("UNKNOWN", rows[f"CHSEM-005-{suffix}"]["result_type"])

    def test_later_stage_refinements_preserve_the_historical_input_fixture(self):
        refinements = json.loads((DATA / "lambda-input-results-refinements.json").read_text())
        self.assertEqual("CHSEM-005", refinements["task"])
        current = {r["id"]: r for r in refinements["cases"]}
        self.assertEqual({"CHSEM-003-E18", "CHSEM-003-E27"}, set(current))
        historical = {r["id"]: r for r in json.loads((DATA / "lambda-input-brikk.json").read_text())}
        self.assertEqual(["UInt8", "UNKNOWN"], historical["CHSEM-003-E18"]["implicit_types"])
        self.assertEqual(["UInt8", "String"], current["CHSEM-003-E18"]["implicit_types"])
        self.assertEqual(["UInt8", "UNKNOWN"], historical["CHSEM-003-E27"]["formal_types"])
        self.assertEqual(["UInt8", "UInt8"], current["CHSEM-003-E27"]["formal_types"])
        native = json.loads((DATA / refinements["native_evidence"]).read_text())
        observations = {r["id"]: r for r in native["execution"]}
        self.assertEqual("['String']\tArray(String)\n", observations["CHSEM-003-E18"]["stdout"])
        self.assertEqual("['UInt8']\tArray(String)\n", observations["CHSEM-003-E27"]["stdout"])


if __name__ == "__main__":
    unittest.main()
