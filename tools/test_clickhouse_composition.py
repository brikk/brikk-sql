import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
FIXTURE = DATA / "composition-cases.json"
RULE_CODES = {
    "VARIADIC_LEFT_DESIGNATOR": 36, "INVALID_OPERAND": 36, "MIXED_PLACEHOLDERS": 36,
    "PLACEHOLDER_LIMIT": 36, "ANONYMOUS_MEMBER": 36, "ANONYMOUS_DEPTH": 36,
    "RIGHT_OPERAND_ARITY": 42, "CALL_ARGUMENT_COUNT": 42, "ARRAY_MAP_ARITY": 43,
    "ARRAY_INPUT_TYPE": 43, "INPUT_TYPE": 43,
}


class ClickhouseCompositionEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text())
        cls.engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        cls.observations = json.loads((DATA / "composition-brikk.json").read_text())

    def test_stable_cases_cover_each_rule_and_keep_unknowns_distinct_from_acceptance(self):
        cases = self.fixture["execution"]
        self.assertEqual(50, len(cases))
        self.assertEqual({f"CHSEM-004-E{i:02}" for i in range(1, 51)}, {c["id"] for c in cases})
        self.assertEqual(set(RULE_CODES), {rule for c in cases for rule in c["rules"]})
        self.assertEqual({e["version"] for e in self.engines}, set(self.fixture["proven_versions"]))
        for case in cases:
            self.assertEqual("CHSEM-004", case["task"])
            self.assertTrue(case["sql"].startswith(("SELECT ", "WITH ")))
            self.assertNotIn(";", case["sql"])
            for forbidden in ("setup", "release", "brikk_sql"):
                self.assertNotIn(forbidden, case)
            for engine in self.engines:
                expected = probe.execution_expectation(case, engine["version"])
                self.assertNotEqual("stdout" in expected, "error_code" in expected)
        self.assertEqual([c["id"] for c in cases], [r["id"] for r in self.observations])
        for case, row in zip(cases, self.observations, strict=True):
            self.assertEqual(case["sql"], row["input_sql"])
            self.assertEqual("26.10.2.103", row["engine_version"])
            self.assertEqual(sorted(case["rules"]), row["rules"])
            self.assertEqual(sorted({RULE_CODES[rule] for rule in row["rules"]}), row["native_error_codes"])
            self.assertTrue(set(case.get("required_unknowns", [])) <= set(row["unknown_reasons"]))
            for forbidden in ("accepted", "is_valid", "natively_accepted", "result_type"):
                self.assertNotIn(forbidden, row)

    def test_each_exact_native_pin_has_all_original_parser_and_execution_observations(self):
        for engine in self.engines:
            version = engine["version"]
            report = json.loads((DATA / "composition-native" / f"{version}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(0, report["version_query"]["returncode"])
            self.assertEqual(version + "\n", report["version_query"]["stdout"])
            self.assertIsNone(report["brikk_sha256"])
            self.assertIsNone(report["brikk_parse_query"])
            self.assertEqual([], report["brikk_execution"])
            self.assertEqual(0, report["parse_query"]["returncode"])
            self.assertEqual(report["parsed"], [json.loads(line) for line in report["parse_query"]["stdout"].splitlines()])
            cases = self.fixture["execution"]
            for phase in ("parsed", "execution"):
                self.assertEqual([c["id"] for c in cases], [r["id"] for r in report[phase]])
            for case, parsed, result in zip(cases, report["parsed"], report["execution"], strict=True):
                label = f"{version}/{case['id']}"
                self.assertEqual(probe.parsing_expectation(case, version), parsed["formatted"] is not None, label)
                self.assertNotIn("skipped", result, label)
                expected = probe.execution_expectation(case, version)
                self.assertTrue(probe.expected_execution(expected, result), label)
                if "error_code" in expected:
                    self.assertRegex(result["stderr"], rf"Code: {expected['error_code']}\. ", label)

    def test_reported_failures_match_native_errors_and_no_native_success_gets_a_false_error(self):
        cases = self.fixture["execution"]
        report = json.loads((DATA / "composition-native/26.10.2.103.json").read_text())
        for case, native, checked in zip(cases, report["execution"], self.observations, strict=True):
            with self.subTest(id=case["id"]):
                if native["returncode"] == 0:
                    self.assertEqual([], checked["rules"])
                if checked["rules"]:
                    self.assertNotEqual(0, native["returncode"])
                    self.assertIn(probe.execution_expectation(case, "26.10.2.103")["error_code"], checked["native_error_codes"])

    def test_runtime_and_scope_controls_never_become_generic_rule_acceptance(self):
        by_id = {r["id"]: r for r in self.observations}
        for id in ("E24", "E26", "E27", "E28", "E29", "E31", "E32", "E45", "E50"):
            row = by_id[f"CHSEM-004-{id}"]
            self.assertEqual([], row["rules"])
            self.assertTrue(row["unknown_reasons"])
        cases = {c["id"]: c for c in self.fixture["execution"]}
        expected = lambda id: probe.execution_expectation(cases[f"CHSEM-004-{id}"], "26.10.2.103")
        self.assertEqual(190, expected("E26")["error_code"])
        self.assertEqual(44, expected("E27")["error_code"])
        self.assertEqual("[]\n", expected("E28")["stdout"])
        self.assertEqual("1\n", expected("E31")["stdout"])
        self.assertEqual("[NULL]\n", expected("E50")["stdout"])


if __name__ == "__main__":
    unittest.main()
