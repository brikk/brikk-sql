import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
FIXTURE = DATA / "cases.json"
BASELINE = DATA / "brikk-before.json"
ENGINES = ROOT / "vendor/data/clickhouse-2026-audit/engines.json"


class ClickhouseSemanticFixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text())
        cls.engines = json.loads(ENGINES.read_text())["engines"]
        cls.versions = {e["version"] for e in cls.engines}
        cls.baseline = {r["id"]: r for r in json.loads(BASELINE.read_text())}

    def test_fixture_ids_and_semantic_categories_are_stable(self):
        cases = probe.parsing_cases(self.fixture)
        ids = [c["id"] for c in cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual({f"CHSEM-001-P{i:02}" for i in range(1, 3)},
                         {c["id"] for c in self.fixture["cases"]})
        self.assertTrue({f"CHSEM-001-E{i:02}" for i in range(1, 49)} <= set(ids))
        self.assertTrue(all(c["task"] == "CHSEM-001" for c in cases))
        categories = {c["category"] for c in self.fixture["execution"]}
        self.assertTrue({"post-chain", "post-placeholder", "post-multiple-arrays", "explicit-control",
                         "variadic-left", "variadic-right", "mixed-placeholders", "mixed-across-stages",
                         "missing-array", "extra-array", "anonymous-depth", "numbered-depth",
                         "nested-implicit", "nested-capture", "explicit-shadow", "physical-underscore",
                         "runtime-array-length", "right-placeholder-arity", "placeholder-limit"} <= categories)

    def test_execution_is_finite_select_only_and_never_skips_the_lts_pin(self):
        for case in self.fixture["execution"]:
            with self.subTest(id=case["id"]):
                self.assertTrue(case["sql"].startswith(("SELECT ", "WITH ")))
                self.assertNotIn(";", case["sql"])
                self.assertNotIn("setup", case)
                self.assertNotIn("release", case)
                self.assertNotIn("brikk_sql", case)
                self.assertEqual(self.versions, set(case["parse_expectations"]))
                self.assertEqual(self.versions, set(case["expectations"]))
                for version in self.versions:
                    expected = probe.execution_expectation(case, version)
                    self.assertIsInstance(probe.parsing_expectation(case, version), bool)
                    if "stdout" in expected:
                        self.assertNotIn("error_code", expected)
                        self.assertRegex(expected["stdout"], r"\t(?:Array\(.+\)|UInt\d+)\n$")
                    else:
                        self.assertIsInstance(expected["error_code"], int)
                        self.assertTrue(expected["error_name"])
                        if not probe.parsing_expectation(case, version):
                            self.assertEqual("SYNTAX_ERROR", expected["error_name"])

    def test_original_and_generated_evidence_matches_each_exact_engine_and_input(self):
        execution = self.fixture["execution"]
        parse_cases = probe.parsing_cases(self.fixture)
        self.assertEqual({c["id"] for c in execution}, set(self.baseline))
        for engine in self.engines:
            version = engine["version"]
            with self.subTest(version=version):
                report = json.loads((DATA / "native" / f"{version}.json").read_text())
                self.assertEqual(engine, report["engine"])
                self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), report["fixture_sha256"])
                self.assertEqual(hashlib.sha256(BASELINE.read_bytes()).hexdigest(), report["brikk_sha256"])
                self.assertEqual(0, report["version_query"]["returncode"])
                self.assertEqual(version + "\n", report["version_query"]["stdout"])
                self.assertEqual(0, report["parse_query"]["returncode"])
                self.assertEqual(report["parsed"],
                                 [json.loads(line) for line in report["parse_query"]["stdout"].splitlines()])
                self.assertEqual([c["id"] for c in parse_cases], [r["id"] for r in report["parsed"]])
                for case, row in zip(parse_cases, report["parsed"], strict=True):
                    self.assertEqual(probe.parsing_expectation(case, version), row["formatted"] is not None,
                                     f"{version}/{case['id']}")
                generated_parse = report["brikk_parse_query"]
                self.assertEqual(0, generated_parse["returncode"])
                generated_rows = [json.loads(line) for line in generated_parse["stdout"].splitlines()]
                self.assertEqual([c["id"] for c in execution if probe.parsing_expectation(c, version)],
                                 [r["id"] for r in generated_rows])
                originals = {r["id"]: r["formatted"] for r in report["parsed"]}
                explicit_formats = {c["id"]: c["native_generated_format"] for c in execution
                                    if "native_generated_format" in c}
                for row in generated_rows:
                    expected_format = explicit_formats.get(row["id"], originals[row["id"]])
                    self.assertEqual(expected_format, row["formatted"], f"{version}/{row['id']}")
                    if row["id"] in explicit_formats:
                        # Four exact spelling differences, not a case-insensitive SQL gate.
                        self.assertEqual(originals[row["id"]].replace("concat(", "CONCAT("), expected_format)
                for kind in ("execution", "brikk_execution"):
                    self.assertEqual([c["id"] for c in execution], [r["id"] for r in report[kind]])
                    for case, row in zip(execution, report[kind], strict=True):
                        expected = probe.execution_expectation(case, version)
                        label = f"{version}/{kind}/{case['id']}"
                        self.assertNotIn("skipped", row, label)
                        self.assertTrue(probe.expected_execution(expected, row), label)
                        if "error_code" in expected:
                            self.assertRegex(row["stderr"], rf"Code: {expected['error_code']}\. ", label)
                        if kind == "brikk_execution":
                            self.assertEqual(self.baseline[case["id"]]["generated"], row["sql"], label)

    def test_control_pairs_agree_without_claiming_general_equivalence(self):
        cases = {c["id"]: c for c in self.fixture["execution"]}
        for short, explicit in (("E01", "E02"), ("E05", "E06")):
            left, right = cases[f"CHSEM-001-{short}"], cases[f"CHSEM-001-{explicit}"]
            self.assertEqual(left["expectations"]["26.10.2.103"]["stdout"],
                             right["expectations"]["26.10.2.103"]["stdout"])
            self.assertEqual(right["expectations"]["26.8.34.13"]["stdout"],
                             right["expectations"]["26.10.2.103"]["stdout"])

    def test_exact_concat_formatter_spellings_have_native_case_policy_evidence(self):
        explicit = {c["id"] for c in self.fixture["execution"] if "native_generated_format" in c}
        self.assertEqual({"CHSEM-001-E05", "CHSEM-001-E06", "CHSEM-001-E34", "CHSEM-001-E48"}, explicit)
        for version in self.versions:
            capture = ROOT / "vendor/data" / f"clickhouse-functions-{version}.tsv"
            lines = capture.read_text().splitlines()
            records = [dict(zip(lines[0].split("\t"), line.split("\t"), strict=True)) for line in lines[1:]]
            self.assertEqual("1", next(row for row in records if row["name"] == "concat")["case_insensitive"])


if __name__ == "__main__":
    unittest.main()
