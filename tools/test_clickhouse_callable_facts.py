import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_callable_sources as sources
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"
FIXTURE = DATA / "callables-cases.json"


class ClickhouseCallableEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text())
        cls.sources = json.loads((DATA / "callable-sources.json").read_text())
        cls.engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]

    def test_all_eight_functions_have_bounded_positive_and_negative_observations_on_both_pins(self):
        cases = self.fixture["execution"]
        self.assertEqual({f"CHSEM-002-E{i:02}" for i in range(1, 57)}, {c["id"] for c in cases})
        self.assertEqual(56, len(cases))
        self.assertEqual({e["version"] for e in self.engines}, set(self.fixture["proven_versions"]))
        names = {"lower", "upper", "domainWithoutWWW", "firstSignificantSubdomain", "trim", "multiply", "toString", "concat"}
        self.assertEqual(names, {c["function"] for c in cases})
        for name in names - {"concat"}:
            selected = [c for c in cases if c["function"] == name]
            self.assertTrue(any("stdout" in c for c in selected), name)
            self.assertTrue(any("error_code" in c for c in selected), name)
        # concat has no count-only negative: zero or more arguments are permitted.
        self.assertTrue(next(c for c in cases if c["id"] == "CHSEM-002-E28")["outside_type_subset"])
        self.assertEqual({"CHSEM-002-E54", "CHSEM-002-E55"}, {c["id"] for c in cases if c.get("type_only")})
        self.assertTrue(next(c for c in cases if c["id"] == "CHSEM-002-E56")["count_rule_counterexample"])
        for case in cases:
            self.assertEqual("CHSEM-002", case["task"])
            self.assertTrue(case["sql"].startswith("SELECT "))
            for forbidden in ("setup", "release", "brikk_sql"):
                self.assertNotIn(forbidden, case)
            self.assertNotIn(";", case["sql"])
            self.assertNotEqual("stdout" in case, "error_code" in case)
            if "error_code" in case:
                self.assertTrue(case["error_contains"])
            else:
                self.assertIn("\t", case["stdout"])

    def test_each_native_observation_matches_the_exact_input_pin_and_expected_result_or_error(self):
        cases = self.fixture["execution"]
        for engine in self.engines:
            version = engine["version"]
            report = json.loads((DATA / "callables-native" / f"{version}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertIsNone(report["brikk_sha256"])
            self.assertIsNone(report["brikk_parse_query"])
            self.assertEqual([], report["brikk_execution"])
            self.assertEqual(0, report["version_query"]["returncode"])
            self.assertEqual(version + "\n", report["version_query"]["stdout"])
            self.assertEqual(0, report["parse_query"]["returncode"])
            self.assertEqual([c["id"] for c in cases], [r["id"] for r in report["parsed"]])
            self.assertEqual(report["parsed"], [json.loads(line) for line in report["parse_query"]["stdout"].splitlines()])
            self.assertTrue(all(r["formatted"] is not None for r in report["parsed"]))
            self.assertEqual([c["id"] for c in cases], [r["id"] for r in report["execution"]])
            for case, row in zip(cases, report["execution"], strict=True):
                with self.subTest(version=version, id=case["id"]):
                    self.assertNotIn("skipped", row)
                    self.assertTrue(probe.expected_execution(case, row))
                    if "error_code" in case:
                        self.assertRegex(row["stderr"], rf"Code: {case['error_code']}\. ")

    def test_source_excerpts_pin_implementation_guards_instead_of_documentation_syntax(self):
        self.assertEqual("Apache-2.0", self.sources["license"])
        self.assertEqual(set(sources.VERSIONS), {s["version"] for s in self.sources["snapshots"]})
        for snapshot in self.sources["snapshots"]:
            self.assertEqual(sources.VERSIONS[snapshot["version"]], snapshot["tag"])
            records = {r["id"]: r for r in snapshot["sources"]}
            expected = {r[0] for r in sources.SELECTIONS}
            if snapshot["version"] == "26.10.2.103":
                expected.add(sources.COMPOSITION_SELECTION[0])
            self.assertEqual(expected, set(records))
            for record in records.values():
                self.assertRegex(record["sha256"], r"^[a-f0-9]{64}$")
                self.assertEqual(f"https://raw.githubusercontent.com/ClickHouse/ClickHouse/{snapshot['tag']}/{record['path']}",
                                 record["url"])
                for excerpt in record["excerpts"]:
                    self.assertGreaterEqual(excerpt["start_line"], 1)
                    self.assertEqual(excerpt["end_line"] - excerpt["start_line"] + 1, len(excerpt["text"].splitlines()))
            def text(id):
                return "\n".join(e["text"] for e in records[f"CHSEM-002-S{id:02}"]["excerpts"])
            self.assertIn("isStringOrFixedString(arguments[0])", text(1))
            self.assertIn("return arguments[0];", text(1))
            self.assertIn('"const String"', text(6))
            self.assertIn("isColumnConst", text(6))
            self.assertIn("return to_string->build(arguments)", text(7))
            self.assertIn("return array_concat->build(arguments)", text(7))
            self.assertIn("return map_concat->build(arguments)", text(7))
            self.assertIn("return tuple_concat->build(arguments)", text(7))
            self.assertIn("if (arguments.empty())", text(7))
            self.assertIn("return 2;", text(9))
            self.assertIn("nextSize(max(sizeof(A), sizeof(B)))", text(10))
            self.assertIn("isDateTime64(arguments[0].type) || isDateTime(arguments[0].type)", text(11))
            self.assertIn("if (null_presence.has_null_constant)", text(11))
            self.assertIn("FixedString is not supported", text(14))

    def test_scoped_excerpt_capture_fails_on_missing_markers(self):
        source = "class First\nvalue\nclass Second\nvalue\nend\n"
        self.assertEqual({"start_line": 4, "end_line": 5, "text": "value\nend"},
                         sources.excerpt(source, "value", "end", "class Second"))
        with self.assertRaises(ValueError):
            sources.excerpt(source, "missing", "", "")
        with self.assertRaises(ValueError):
            sources.excerpt(source, "value", "gone", "class Second")


if __name__ == "__main__":
    unittest.main()
