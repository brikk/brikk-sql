import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_division_types as matrix

DATA = pathlib.Path(__file__).resolve().parents[1] / "vendor/data/clickhouse-semantic"


class ClickhouseDivisionTypingEvidenceTest(unittest.TestCase):
    def test_all_1372_native_types_match_kotlin_without_copying_the_type_formula(self):
        folder = DATA / "division-types"
        raw = (folder / "input.json").read_bytes()
        source = json.loads(raw)
        self.assertEqual(matrix.matrix_input(), source)
        native = json.loads((folder / "26.10.2.103.json").read_text())
        self.assertEqual("26.10.2.103", native["engine"]["version"])
        self.assertEqual("26.10.2.103\n", native["version_query"]["stdout"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), native["input_sha256"])
        brikk = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(7, len(brikk))
        self.assertEqual(1372, sum(len(b["types"]) for b in brikk))
        for input, observed, typed in zip(source["batches"], native["batches"], brikk, strict=True):
            self.assertEqual(input["id"], typed["id"])
            self.assertEqual(observed["id"], typed["id"])
            self.assertEqual({p["id"] for p in input["pairs"]}, set(typed["types"]))
            self.assertEqual(0, observed["raw"]["returncode"])
            self.assertEqual(observed["types"], json.loads(observed["raw"]["stdout"]))
            self.assertEqual(observed["types"], typed["types"])

    def test_sources_pin_width_delegate_and_nullability_rules(self):
        captured = json.loads((DATA / "division-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(["26.10.2.103"], [s["version"] for s in captured["snapshots"]])
        snapshot = captured["snapshots"][0]
        self.assertEqual(sources.VERSIONS[snapshot["version"]], snapshot["tag"])
        self.assertEqual(len(sources.DIVISION), len(snapshot["sources"]))
        for spec, record in zip(sources.DIVISION, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], record["path"])
            self.assertIn(snapshot["tag"], record["url"])
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            excerpt = record["excerpts"][0]
            self.assertIn(spec[1], excerpt["text"])
            self.assertEqual(excerpt["end_line"] - excerpt["start_line"] + 1, len(excerpt["text"].split("\n")))
        text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
        for guard in ("ResultOfIntegerDivision", "UInt256", "isInteger(removeNullable(left))", "return legacy", "makeNullableSafe"):
            self.assertIn(guard, text)

    def test_runtime_failures_do_not_erase_conditional_types_or_create_unimplemented_diagnostics(self):
        rows = {r["id"]: r for r in json.loads((DATA / "division-brikk.json").read_text())}
        native = {r["id"]: r for r in json.loads((DATA / "division-native/26.10.2.103.json").read_text())["execution"]}
        for suffix, type in (("E13", "UInt8"), ("E14", "Int8"), ("E15", "Int8")):
            id = "CHSEM-012-" + suffix
            self.assertEqual(type, rows[id]["result_type"])
            self.assertEqual([], rows[id]["rules"])
            self.assertIn("Code: 153.", native[id]["stderr"])
        for suffix in ("E12", "E18", "E19"):
            self.assertEqual("UNKNOWN", rows["CHSEM-012-" + suffix]["result_type"])
        backlog = (DATA.parents[2] / "TODO-clickhouse-semantic.md").read_text()
        self.assertIn("CHSEM-013", backlog)
        self.assertIn("CHSEM-014", backlog)

    def test_diagnostic_refinements_preserve_the_typing_only_capture(self):
        refinements = json.loads((DATA / "division-diagnostics-rules.json").read_text())
        rows = {r["id"]: r for r in json.loads((DATA / "division-diagnostics-refinement-brikk.json").read_text())}
        for id, rules in refinements.items():
            self.assertEqual(rules, rows[id]["rules"])

    def test_every_reported_constant_division_error_has_a_native_153_witness(self):
        rows = json.loads((DATA / "division-diagnostics-brikk.json").read_text())
        native = json.loads((DATA / "division-diagnostics-native/26.10.2.103.json").read_text())["execution"]
        for row, observed in zip(rows, native, strict=True):
            self.assertEqual(row["id"], observed["id"])
            if row["rules"]:
                self.assertIn("Code: 153.", observed["stderr"])
                self.assertNotEqual(0, observed["returncode"])
            if observed["returncode"] == 0:
                self.assertEqual([], row["rules"])
