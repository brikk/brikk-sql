import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_multiplication as matrix
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseMultiplicationEvidenceTest(unittest.TestCase):
    def test_all_1440_primitive_types_match_both_independent_native_profiles(self):
        folder = DATA / "multiplication-types"
        raw = (folder / "input.json").read_bytes()
        self.assertEqual(matrix.matrix_input(), json.loads(raw))
        typed = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(8, len(typed))
        self.assertEqual(1440, sum(len(row["types"]) for row in typed))
        for engine in json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]:
            report = json.loads((folder / (engine["version"] + ".json")).read_text())
            self.assertEqual(engine, report["engine"])
            self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), report["input_sha256"])
            for native, row in zip(report["batches"], typed, strict=True):
                self.assertEqual(native["id"], row["id"])
                self.assertEqual(0, native["raw"]["returncode"])
                self.assertEqual(json.loads(native["raw"]["stdout"]), row["types"])
                self.assertEqual(native["types"], row["types"])

    def test_finite_and_script_original_generated_provenance_and_boundaries(self):
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for name, count in (("multiplication", 19), ("multiplication-script", 6)):
            fixture, brikk = DATA / f"{name}-cases.json", DATA / f"{name}-brikk.json"
            cases, rows = json.loads(fixture.read_text())["execution"], json.loads(brikk.read_text())
            self.assertEqual(count, len(cases))
            for engine in engines:
                report = json.loads((DATA / f"{name}-native/{engine['version']}.json").read_text())
                self.assertEqual(engine, report["engine"])
                self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
                self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
                self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), report["brikk_sha256"])
                for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                    self.assertEqual(case["id"], row["id"])
                    self.assertEqual(case["sql"], row["input_sql"])
                    self.assertEqual(case.get("types", [case.get("type")]), row["types"])
                    self.assertEqual(case["rules"], row["rules"])
                    for observed in (original, generated):
                        self.assertEqual(case["id"], observed["id"])
                        self.assertNotIn("skipped", observed)
                        self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), observed))
                    self.assertEqual(original["stdout"], generated["stdout"])
                    if "UNKNOWN" not in row["types"] and engine["version"] == "26.10.2.103":
                        self.assertEqual(0, original["returncode"])
                        self.assertEqual(row["types"], [line.split("\t")[-1] for line in original["stdout"].splitlines()])

    def test_named_refinements_close_g01_g02_without_rewriting_the_historical_capture(self):
        refinements = json.loads((DATA / "multiplication-refinements.json").read_text())
        historical = {r["id"]: r for r in json.loads((DATA / "closeout-missing-brikk.json").read_text())}
        current = {r["id"]: r for r in json.loads((DATA / "multiplication-brikk.json").read_text())}
        for id, before, after in (("G01", "Nullable(Int32)", "UInt16"), ("G02", "Nullable(Float64)", "Float64")):
            self.assertEqual([before], historical[id]["types"])
            self.assertFalse(historical[id]["qualified"])
            self.assertEqual([after], refinements["missing"][id])
            self.assertEqual([after], current[refinements["witnesses"][id]]["types"])

    def test_source_traits_prove_shared_promotion_and_default_nullable_dispatch(self):
        snapshot = json.loads((DATA / "multiplication-sources.json").read_text())
        self.assertEqual("Apache-2.0", snapshot["license"])
        source = snapshot["snapshots"][0]
        self.assertEqual("26.10.2.103", source["version"])
        for spec, record in zip(sources.MULTIPLICATION, source["sources"], strict=True):
            self.assertEqual(spec[0], record["path"])
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[source["version"]], record["url"])
        text = "\n".join(r["excerpts"][0]["text"] for r in source["sources"])
        for rule in ("struct MultiplyImpl", "ResultOfAdditionMultiplication", "static_cast<Result>(a) * static_cast<Result>(b)", "makeNullableSafe"):
            self.assertIn(rule, text)
