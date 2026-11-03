import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_2026 as probe

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-semantic"


class ClickhouseCteContextEvidenceTest(unittest.TestCase):
    def test_original_generated_whole_session_results_have_exact_provenance(self):
        engines = json.loads((ROOT / "vendor/data/clickhouse-2026-audit/engines.json").read_text())["engines"]
        for name, count in (("cte-context", 28), ("cte-context-script", 6)):
            fixture, frontend = DATA / f"{name}-cases.json", DATA / f"{name}-brikk.json"
            cases = json.loads(fixture.read_text())["execution"]
            rows = json.loads(frontend.read_text())
            self.assertEqual(count, len(cases))
            for engine in engines:
                report = json.loads((DATA / f"{name}-native/{engine['version']}.json").read_text())
                self.assertEqual(engine, report["engine"])
                self.assertEqual(engine["version"] + "\n", report["version_query"]["stdout"])
                self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), report["fixture_sha256"])
                self.assertEqual(hashlib.sha256(frontend.read_bytes()).hexdigest(), report["brikk_sha256"])
                for case, row, original, generated in zip(cases, rows, report["execution"], report["brikk_execution"], strict=True):
                    for value in (row, original, generated):
                        self.assertEqual(case["id"], value["id"])
                    self.assertEqual(case["sql"], row["input_sql"])
                    self.assertEqual(case["types"], row["types"])
                    self.assertEqual(case["rules"], row["rules"])
                    for observed in (original, generated):
                        self.assertNotIn("skipped", observed)
                        self.assertTrue(probe.expected_execution(probe.execution_expectation(case, engine["version"]), observed))
                    self.assertEqual(original["stdout"], generated["stdout"])

    def test_qualified_types_are_observed_native_types_and_have_native_evidence(self):
        for name, qualified in (("cte-context", 20), ("cte-context-script", 4)):
            cases = json.loads((DATA / f"{name}-cases.json").read_text())["execution"]
            rows = json.loads((DATA / f"{name}-brikk.json").read_text())
            native = json.loads((DATA / f"{name}-native/26.10.2.103.json").read_text())["execution"]
            self.assertEqual(qualified, sum(case["qualified"] for case in cases))
            for case, row, observed in zip(cases, rows, native, strict=True):
                self.assertEqual(case["qualified"], row["qualified"])
                if case["qualified"]:
                    self.assertEqual(0, observed["returncode"])
                    self.assertTrue(observed["stdout"].strip(), "Empty results cannot witness result types")
                    self.assertEqual(row["types"], list(dict.fromkeys(line.split("\t")[-1] for line in observed["stdout"].splitlines())))
                    self.assertEqual(["NATIVE_CONDITIONAL"] * len(row["types"]), row["evidence"])
                else:
                    self.assertEqual(["UNKNOWN"], row["types"])
                    self.assertEqual(["UNKNOWN"], row["evidence"])

    def test_chains_stars_shadowing_and_conservative_boundaries_are_explicit(self):
        cases = json.loads((DATA / "cte-context-cases.json").read_text())["execution"]
        qualified = {case["category"] for case in cases if case["qualified"]}
        for name in ("ordinary", "qualified", "chain-star", "identity-alias", "computed-alias", "array-chain",
                     "nested-shadow", "repeated-reference", "inner-join", "join-inside-chain", "group-distinct"):
            self.assertIn(name, qualified)
        deferred = {case["category"] for case in cases if not case["qualified"]}
        self.assertEqual({"ambiguous", "outer-join-deferred", "set-operation-deferred", "recursive-deferred",
                          "aggregate-deferred", "udf-deferred", "constant-null-deferred", "forward-reference-deferred"}, deferred)
        refinement = json.loads((DATA / "cte-context-refinements.json").read_text())["refinements"][0]
        current = next(row for row in json.loads((DATA / "cte-context-brikk.json").read_text()) if row["id"] == refinement["witness"])
        self.assertEqual("Nullable(UNKNOWN)", refinement["previous_frontend_type"])
        self.assertEqual([refinement["native_type"]], current["types"])
        self.assertEqual([refinement["evidence"]], current["evidence"])

    def test_source_rules_are_tagged_and_include_cte_projection_and_ambiguity_guards(self):
        captured = json.loads((DATA / "cte-context-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(1, len(captured["snapshots"]))
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        for spec, record in zip(sources.CTE_CONTEXT, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], record["path"])
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], record["url"])
        text = "\n".join(record["excerpts"][0]["text"] for record in snapshot["sources"])
        for guard in ("cte_name_to_query_node", "ctes_in_resolve_process", "getProjectionColumns", "ColumnNode", "AMBIGUOUS_IDENTIFIER"):
            self.assertIn(guard, text)
