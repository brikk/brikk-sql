import hashlib
import json
import pathlib
import re
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_floating_add_sub as matrix
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseFloatingAddSubEvidenceTest(unittest.TestCase):
    def test_all_576_types_and_256_wide_integer_boundaries_have_independent_native_results(self):
        folder = DATA / "floating-add-sub-types"
        raw = (folder / "input.json").read_bytes()
        source = json.loads(raw)
        self.assertEqual(matrix.matrix_input(), source)
        typed = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(576, sum(len(row["types"]) for row in typed["batches"]))
        self.assertEqual(256, len(typed["rejected"]))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((folder / (engine["version"] + ".json")).read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["input_sha256"])
            for input, native, row in zip(source["batches"], report["batches"], typed["batches"], strict=True):
                self.assertEqual(input["id"], row["id"])
                self.assertEqual(native["id"], row["id"])
                self.assertEqual(native["types"], row["types"])
                self.assertEqual(json.loads(native["raw"]["stdout"]), row["types"])
            for input, native, row in zip(source["rejected"], report["rejected"], typed["rejected"], strict=True):
                self.assertEqual(input["id"], row["id"])
                self.assertEqual(native["id"], row["id"])
                self.assertEqual("UNKNOWN", row["type"])
                self.assertEqual(43, native["raw"]["returncode"])
                self.assertEqual("", native["raw"]["stdout"])
                self.assertIn("ILLEGAL_TYPE_OF_ARGUMENT", native["raw"]["stderr"])

    def test_tagged_guards_prove_promotion_and_do_not_define_float128_or_float256(self):
        captured = json.loads((DATA / "floating-add-sub-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.FLOATING_ADD_SUB), len(snapshot["sources"]))
        for spec, row in zip(sources.FLOATING_ADD_SUB, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], row["path"])
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], row["url"])
            self.assertIn(spec[1], row["excerpts"][0]["text"])
        text = "\n".join(row["excerpts"][0]["text"] for row in snapshot["sources"])
        for guard in ("using Type = Error", "Construct<false, true, 4>", "Construct<true, true, 8>",
                      "is_floating_point<A> || is_floating_point<B>", "ILLEGAL_TYPE_OF_ARGUMENT", "makeNullableSafe"):
            self.assertIn(guard, text)
        self.assertNotIn("Construct<false, true, 16>", text)
        self.assertNotIn("Construct<true, true, 32>", text)

    def test_nonfinite_and_precision_outcomes_are_values_not_fabricated_errors(self):
        rows = {row["id"]: row for row in json.loads((DATA / "floating-add-sub-brikk.json").read_text())}
        native = {row["id"]: row for row in json.loads((DATA / "floating-add-sub-native/26.10.2.103.json").read_text())["execution"]}
        for suffix, value in (("E14", "16777216"), ("E15", "9007199254740992"), ("E16", "inf"), ("E17", "nan"), ("E18", "nan")):
            id = "CHSEM-021-" + suffix
            self.assertEqual("Float64", rows[id]["result_type"])
            self.assertEqual([], rows[id]["rules"])
            self.assertEqual(value + "\tFloat64\n", native[id]["stdout"])

    def test_whole_script_settings_and_generated_sql_match(self):
        fixture, brikk = DATA / "floating-add-sub-script-cases.json", DATA / "floating-add-sub-script-brikk.json"
        cases, rows = json.loads(fixture.read_text())["execution"], json.loads(brikk.read_text())
        self.assertEqual(8, len(cases))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((DATA / f"floating-add-sub-script-native/{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
            for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                self.assertEqual(case["id"], row["id"])
                self.assertEqual(case["types"], row["types"])
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), original))
                self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), generated))
                self.assertEqual(original["stdout"], generated["stdout"])

    def test_named_refinement_and_parser_gap_retain_history_and_next_work(self):
        old = {r["id"]: r for r in json.loads((DATA / "integer-add-sub-brikk.json").read_text())}
        current = {r["id"]: r for r in json.loads((DATA / "floating-add-sub-refinement-brikk.json").read_text())}
        refinements = json.loads((DATA / "floating-add-sub-refinements.json").read_text())
        self.assertEqual({"CHSEM-020-E24": "Float64"}, refinements)
        for id, row in old.items():
            self.assertEqual(refinements.get(id, row["result_type"]), current[id]["result_type"])
        gap = json.loads((DATA / "floating-add-sub-parser-gap-brikk.json").read_text())
        native = json.loads((DATA / "floating-add-sub-parser-gap-native.json").read_text())
        self.assertEqual("CHSEM-022", gap["task"])
        self.assertEqual("CHSEM-022", native["task"])
        for report in native["reports"]:
            for frontend, observed, root in zip(gap["cases"], report["execution"], ("Literal", "Dot"), strict=True):
                self.assertEqual(frontend["sql"], observed["sql"])
                self.assertEqual("UNKNOWN", frontend["type"])
                self.assertEqual(root, frontend["root_node"])
                self.assertEqual(0, observed["returncode"])
                self.assertTrue(observed["stdout"].endswith("\tFloat64\n"))

    def test_tracker_retains_stable_ids_and_the_completed_parser_followup(self):
        tracker = (ROOT / "dev-docs/TODO-clickhouse-semantic.md").read_text()
        rows = re.findall(r"^\| (CHSEM-\d{3}(?:-A)?) \| (DONE|AUDIT|ADD|MONITOR|DEFER) \|", tracker, re.M)
        states = dict(rows)
        self.assertEqual({f"CHSEM-{i:03}" for i in range(1, 35)} | {"CHSEM-030-A"}, set(states))
        self.assertEqual(len(states), len(rows), "Each stable ID must have exactly one tracker row")
        self.assertEqual("DONE", states["CHSEM-022"])
