import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_integer_add_sub as matrix
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseIntegerAddSubEvidenceTest(unittest.TestCase):
    def test_all_2304_ordered_pair_types_match_independent_native_profiles(self):
        folder = DATA / "integer-add-sub-types"
        raw = (folder / "input.json").read_bytes()
        source = json.loads(raw)
        self.assertEqual(matrix.matrix_input(), source)
        typed = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(16, len(typed))
        self.assertEqual(2304, sum(len(row["types"]) for row in typed))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((folder / (engine["version"] + ".json")).read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["input_sha256"])
            for input, native, row in zip(source["batches"], report["batches"], typed, strict=True):
                self.assertEqual(input["id"], row["id"])
                self.assertEqual(native["id"], row["id"])
                self.assertEqual(0, native["raw"]["returncode"])
                self.assertEqual({p["id"] for p in input["pairs"]}, set(row["types"]))
                self.assertEqual(json.loads(native["raw"]["stdout"]), row["types"])
                self.assertEqual(native["types"], row["types"])

    def test_tagged_source_guards_include_saturation_sign_and_nullable_rules(self):
        captured = json.loads((DATA / "integer-add-sub-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(1, len(captured["snapshots"]))
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.INTEGER_ADD_SUB), len(snapshot["sources"]))
        for spec, row in zip(sources.INTEGER_ADD_SUB, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], row["path"])
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], row["url"])
            self.assertIn(spec[1], row["excerpts"][0]["text"])
        text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
        for guard in ("if (size < 8)", "ResultOfAdditionMultiplication", "ResultOfSubtraction",
                      "struct Construct<false, false, 32>", "static_cast<Result>(a)", "makeNullableSafe"):
            self.assertIn(guard, text)

    def test_wraparound_values_keep_types_separate_from_runtime_rejection(self):
        typed = {r["id"]: r for r in json.loads((DATA / "integer-add-sub-brikk.json").read_text())}
        native = {r["id"]: r for r in json.loads((DATA / "integer-add-sub-native/26.10.2.103.json").read_text())["execution"]}
        for suffix, type, value in (("E17", "UInt64", "0"), ("E18", "Int64", "-1"), ("E19", "Int64", "-2"), ("E20", "UInt256", "0")):
            id = "CHSEM-020-" + suffix
            self.assertEqual(type, typed[id]["result_type"])
            self.assertEqual([], typed[id]["rules"])
            self.assertEqual(0, native[id]["returncode"])
            self.assertEqual(value + "\t" + type + "\n", native[id]["stdout"])
        for suffix in ("E23", "E24", "E25", "E26", "E27", "E28", "E29", "E30"):
            self.assertEqual("UNKNOWN", typed["CHSEM-020-" + suffix]["result_type"])

    def test_script_types_and_generated_execution_match_the_versioned_controls(self):
        fixture = DATA / "integer-add-sub-script-cases.json"
        brikk = DATA / "integer-add-sub-script-brikk.json"
        cases, rows = json.loads(fixture.read_text())["execution"], json.loads(brikk.read_text())
        self.assertEqual(8, len(cases))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((DATA / f"integer-add-sub-script-native/{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
            for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                self.assertEqual(case["id"], row["id"])
                self.assertEqual(case["types"], row["types"])
                self.assertEqual(case["rules"], row["rules"])
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                self.assertEqual(original["stdout"], generated["stdout"])
                if engine["version"] == "26.10.2.103":
                    self.assertEqual(row["types"], [line.split("\t")[-1] for line in original["stdout"].splitlines()])

    def test_explicit_refinements_have_native_witnesses_without_replacing_old_captures(self):
        refinements = json.loads((DATA / "integer-add-sub-refinements.json").read_text())["refinements"]
        finite = {r["id"]: r for r in json.loads((DATA / "integer-add-sub-brikk.json").read_text())}
        scripts = {r["id"]: r for r in json.loads((DATA / "integer-add-sub-script-brikk.json").read_text())}
        self.assertEqual(2, len(refinements))
        for row in refinements:
            observed = finite.get(row["witness"])
            actual = observed["result_type"] if observed else scripts[row["witness"]]["types"][0]
            self.assertEqual(row["native_type"], actual)
            self.assertEqual(row["previous_native_type"], row["generic_type"])
