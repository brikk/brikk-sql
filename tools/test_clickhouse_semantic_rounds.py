import hashlib
import json
import pathlib
import unittest

import probe_clickhouse_2026 as probe
import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_literal_types as literal_types

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseSemanticRoundsEvidenceTest(unittest.TestCase):
    rounds = ("qualification", "higher-order", "overload")

    def test_overload_source_and_refinement_proofs_keep_historical_captures(self):
        captured = json.loads((DATA / "overload-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(set(sources.VERSIONS), {s["version"] for s in captured["snapshots"]})
        for snapshot in captured["snapshots"]:
            self.assertEqual(sources.VERSIONS[snapshot["version"]], snapshot["tag"])
            self.assertEqual(len(sources.OVERLOAD), len(snapshot["sources"]))
            for spec, record in zip(sources.OVERLOAD, snapshot["sources"], strict=True):
                self.assertEqual(spec[0], record["path"])
                self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
                self.assertIn(snapshot["tag"], record["url"])
                excerpt = record["excerpts"][0]
                self.assertIn(spec[1], excerpt["text"])
                self.assertEqual(excerpt["end_line"] - excerpt["start_line"] + 1, len(excerpt["text"].split("\n")))
            text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
            for guard in ("numeric_limits<UInt8>", "max_bits_of_signed_integer", "tx.getScale() + ty.getScale()", "makeNullableSafe", "recursiveRemoveLowCardinality"):
                self.assertIn(guard, text)
        refinements = json.loads((DATA / "overload-refinements.json").read_text())
        self.assertEqual(["CHSEM-004-E41"], [r["id"] for r in refinements["rules"]])
        old = {r["id"]: r for r in json.loads((DATA / "composition-brikk.json").read_text())}
        self.assertEqual([], old["CHSEM-004-E41"]["rules"])
        self.assertEqual(["INPUT_TYPE"], refinements["rules"][0]["rules"])
        native = {r["id"]: r for r in json.loads((DATA / "composition-native/26.10.2.103.json").read_text())["execution"]}
        self.assertRegex(native["CHSEM-004-E41"]["stderr"], r"Code: 43\. ")
        old_types = {r["id"]: r for r in json.loads((DATA / "result-brikk.json").read_text())}
        witnesses = {r["id"]: r for r in json.loads((DATA / "result-native/26.10.2.103.json").read_text())["execution"]}
        for row in refinements["results"]:
            self.assertEqual("UNKNOWN", old_types[row["id"]]["result_type"])
            self.assertEqual(row["type"] + "\n", witnesses[row["id"]]["stdout"])

    def test_all_literal_boundaries_match_independent_engine_observations(self):
        folder = DATA / "literal-types"
        raw = (folder / "input.json").read_bytes()
        self.assertEqual(literal_types.matrix_input(), json.loads(raw))
        expected = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(16, len(expected))
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for engine in engines:
            report = json.loads((folder / f"{engine['version']}.json").read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["input_sha256"])
            self.assertEqual(0, report["query"]["returncode"])
            self.assertEqual(report["types"], json.loads(report["query"]["stdout"]))
            self.assertEqual(expected, report["types"])

    def test_settings_runtime_and_unresearched_overloads_remain_unknown(self):
        rows = {r["id"]: r for r in json.loads((DATA / "overload-brikk.json").read_text())}
        for number in (21, 22, 23, 24, 25, 26, 28, 29, 31, 32, 33):
            row = rows[f"CHSEM-009-E{number:02}"]
            self.assertEqual("UNKNOWN", row["result_type"])
            self.assertEqual([], row["rules"])
            self.assertTrue(row["unknowns"])
        for number in (18, 19, 20):
            self.assertIn("RUNTIME_RESTRICTION", rows[f"CHSEM-009-E{number:02}"]["unknowns"])

    def test_higher_order_source_guards_are_pinned_and_distinct(self):
        captured = json.loads((DATA / "higher-order-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(set(sources.VERSIONS), {s["version"] for s in captured["snapshots"]})
        for snapshot in captured["snapshots"]:
            self.assertEqual(sources.VERSIONS[snapshot["version"]], snapshot["tag"])
            self.assertEqual(len(sources.HIGHER_ORDER), len(snapshot["sources"]))
            for spec, record in zip(sources.HIGHER_ORDER, snapshot["sources"], strict=True):
                path, begin, end = spec
                self.assertEqual(path, record["path"])
                self.assertEqual(f"https://raw.githubusercontent.com/ClickHouse/ClickHouse/{snapshot['tag']}/{path}", record["url"])
                self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
                excerpt = record["excerpts"][0]
                self.assertEqual(excerpt["end_line"] - excerpt["start_line"] + 1, len(excerpt["text"].split("\n")))
                if begin:
                    self.assertIn(begin, excerpt["text"])
            text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
            for guard in ("needBoolean()", "DataTypeUInt8", "Nullable(UInt8)", "tuple_argument_size", "type_tuple->getElements().size() != 2"):
                self.assertIn(guard, text)
        refined = json.loads((DATA / "higher-order-refinements.json").read_text())
        self.assertEqual(["CHSEM-003-E23"], [r["id"] for r in refined["cases"]])
        self.assertEqual(["UInt8", "String"], refined["cases"][0]["formal_types"])

    def test_rounds_have_complete_hash_pinned_original_and_generated_evidence(self):
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for name in self.rounds:
            fixture = DATA / f"{name}-cases.json"
            brikk = DATA / f"{name}-brikk.json"
            cases = json.loads(fixture.read_text())["execution"]
            rows = json.loads(brikk.read_text())
            self.assertEqual([c["id"] for c in cases], [r["id"] for r in rows])
            for case, row in zip(cases, rows, strict=True):
                self.assertEqual(case["sql"], row["input_sql"])
                self.assertEqual(case["type"], row["result_type"])
                self.assertEqual(sorted(case["rules"]), row["rules"])
                self.assertTrue(row["generated"])
            for engine in engines:
                version = engine["version"]
                report = json.loads((DATA / f"{name}-native/{version}.json").read_text())
                self.assertEqual(engine, report["engine"])
                self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
                self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
                self.assertEqual(version + "\n", report["version_query"]["stdout"])
                self.assertEqual(0, report["version_query"]["returncode"])
                for phase in ("execution", "brikk_execution", "parsed"):
                    self.assertEqual([c["id"] for c in cases], [r["id"] for r in report[phase]])
                originals = {r["id"]: r["formatted"] for r in report["parsed"]}
                formatted = [json.loads(line) for line in report["brikk_parse_query"]["stdout"].splitlines()]
                self.assertEqual(0, report["brikk_parse_query"]["returncode"])
                self.assertEqual([c["id"] for c in cases if probe.parsing_expectation(c, version)], [r["id"] for r in formatted])
                for row in formatted:
                    self.assertIsNotNone(row["formatted"])
                    self.assertEqual(originals[row["id"]], row["formatted"])
                for case, original, generated in zip(cases, report["execution"], report["brikk_execution"], strict=True):
                    label = f"{name}/{version}/{case['id']}"
                    expected = probe.execution_expectation(case, version)
                    self.assertTrue(probe.expected_execution(expected, original), label)
                    self.assertTrue(probe.expected_execution(expected, generated), label)
                    self.assertEqual(original["stdout"], generated["stdout"], label)
                    self.assertEqual(original["returncode"], generated["returncode"], label)
                    self.assertNotIn("skipped", original)
                    self.assertNotIn("skipped", generated)


if __name__ == "__main__":
    unittest.main()
