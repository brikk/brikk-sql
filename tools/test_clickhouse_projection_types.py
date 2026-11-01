import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_projection_types as matrix
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseProjectionTypesEvidenceTest(unittest.TestCase):
    def test_partial_output_before_failure_requires_an_exact_authored_expectation(self):
        expected = {"error_code": 153, "error_name": "ILLEGAL_DIVISION"}
        result = {"returncode": 153, "stdout": "", "stderr": "Code: 153. ILLEGAL_DIVISION"}
        self.assertTrue(probe.expected_execution(expected, result))
        partial = {**result, "stdout": "\\N\tNullable(Nothing)\n"}
        self.assertFalse(probe.expected_execution(expected, partial))
        explicit = {**expected, "stdout": partial["stdout"]}
        self.assertTrue(probe.expected_execution(explicit, partial))
        self.assertFalse(probe.expected_execution(explicit, {**partial, "stdout": "unexpected\n"}))

    def test_all_84_kotlin_projection_types_match_independent_native_profiles(self):
        folder = DATA / "projection-literal-types"
        raw = (folder / "input.json").read_bytes()
        self.assertEqual(matrix.matrix_input(), json.loads(raw))
        typed = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(84, sum(len(row["types"]) for row in typed))
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for engine in engines:
            report = json.loads((folder / (engine["version"] + ".json")).read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["input_sha256"])
            for source, native, actual in zip(json.loads(raw)["batches"], report["batches"], typed, strict=True):
                self.assertEqual(source["id"], actual["id"])
                self.assertEqual(native["id"], actual["id"])
                values = json.loads(native["raw"]["stdout"])
                expected = {case["id"]: values["t" + case["id"][1:]] for case in source["cases"]}
                self.assertEqual(expected, native["types"])
                self.assertEqual(expected, actual["types"])

    def test_sources_pin_null_boolean_sign_width_finite_float_and_token_limits(self):
        captured = json.loads((DATA / "projection-literal-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(1, len(captured["snapshots"]))
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.PROJECTION_LITERALS), len(snapshot["sources"]))
        for spec, row in zip(sources.PROJECTION_LITERALS, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], row["path"])
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], row["url"])
            self.assertIn(spec[1], row["excerpts"][0]["text"])
        text = "\n".join(row["excerpts"][0]["text"] for row in snapshot["sources"])
        for guard in ("DataTypeNothing", 'get("Bool")', "DataTypeUInt8", "DataTypeInt8", "DataTypeFloat64",
                      "uint_value != 0", "MAX_LENGTH_OF_NUMBER = 319", "!std::isfinite(float_value)", "return try_read_float"):
            self.assertIn(guard, text)

    def test_native_rejections_remain_unknown_types_not_fabricated_literal_success(self):
        cases = json.loads((DATA / "projection-literal-cases.json").read_text())["execution"]
        rows = json.loads((DATA / "projection-literal-brikk.json").read_text())
        self.assertEqual(44, len(rows))
        for case, row in zip(cases[-2:], rows[-2:], strict=True):
            self.assertEqual("UNKNOWN", row["result_type"])
            self.assertEqual([], row["rules"])
            for version, expected in case["expectations"].items():
                self.assertFalse(case["parse_expectations"][version])
                self.assertEqual(62, expected["error_code"])

    def test_script_modes_and_native_literal_types_match_generated_whole_sessions(self):
        fixture = DATA / "projection-literal-script-cases.json"
        brikk = DATA / "projection-literal-script-brikk.json"
        cases = json.loads(fixture.read_text())["execution"]
        rows = json.loads(brikk.read_text())
        self.assertEqual(6, len(cases))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((DATA / f"projection-literal-script-native/{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
            for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                self.assertEqual(case["types"], row["types"])
                self.assertEqual(case["rules"], row["rules"])
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                self.assertEqual(original["stdout"], generated["stdout"])
                if engine["version"] == "26.10.2.103":
                    observed = [line.split("\t")[-1] for line in original["stdout"].splitlines()]
                    if original["returncode"] == 0:
                        self.assertEqual(len(row["types"]), len(observed))
                    for typed, native in zip(row["types"], observed):
                        if typed != "UNKNOWN":
                            self.assertEqual(typed, native)
