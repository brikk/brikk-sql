import json
from pathlib import Path
import unittest
import duckdb_semantic_audit as audit

ROOT=Path(__file__).resolve().parents[1]


class DuckdbSemanticAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data=json.loads((ROOT/"vendor/data/duckdb-2.0-bounded-semantic-refresh.json").read_text())

    def test_exact_oracle_profiles_and_every_candidate_have_complete_evidence(self):
        r=self.data
        self.assertEqual("2.0.0",r["duckdb"]["version"])
        self.assertEqual(audit.JDBC_SHA256,r["duckdb"]["jdbc_sha256"])
        self.assertEqual("484",r["profiles"]["trino"]["version"])
        self.assertIn("4.1.5",r["profiles"]["doris"]["version"])
        self.assertEqual("5.7.99",r["profiles"]["doris"]["mysql_compatibility_version"])
        self.assertEqual("26.10.2.103",r["profiles"]["clickhouse"]["version"])
        for engine,rows in r["rows"].items():
            self.assertEqual({c["id"] for c in audit.CASES},set(rows))
            self.assertTrue(any(x["status"]=="ok" for x in rows.values()),engine)
            self.assertFalse(any(x["status"]=="unavailable" for x in rows.values()),engine)
            for row in rows.values():
                if row["status"]=="ok":self.assertIsInstance(row["sql_null"],bool)

    def test_all_comparisons_and_reviewed_findings_replay_without_live_engines(self):
        self.assertEqual(audit.CASES,self.data["cases"])
        self.assertEqual(audit.comparisons(self.data["rows"]),self.data["comparisons"])
        self.assertEqual(audit.FINDINGS,self.data["findings"])
        for item in self.data["comparisons"]:
            if "doris" in item["engines"]:self.assertNotEqual("same-observed",item["verdict"])

    def test_json_null_is_not_sql_null_even_when_the_string_projection_is_null(self):
        for engine in ("duckdb","trino","doris","clickhouse"):
            row=self.data["rows"][engine]["json-null"]
            self.assertEqual("ok",row["status"]);self.assertFalse(row["sql_null"])
        self.assertIsNone(self.data["rows"]["trino"]["json-null"]["value"])
        self.assertEqual("null",self.data["rows"]["duckdb"]["json-null"]["value"])

    def test_measured_boundaries_do_not_become_unconditional_equivalences(self):
        r=self.data["rows"]
        self.assertEqual("1",r["duckdb"]["month-boundaries"]["value"])
        self.assertEqual("0",r["trino"]["month-boundaries"]["value"])
        self.assertEqual("0",r["doris"]["month-boundaries"]["value"])
        self.assertEqual("xba",r["duckdb"]["regex-replace-default"]["value"])
        self.assertEqual("xbx",r["trino"]["regex-replace-default"]["value"])
        self.assertEqual("engine_error",r["duckdb"]["regex-lookbehind"]["status"])
        self.assertEqual("true",r["trino"]["regex-lookbehind"]["value"])
        self.assertTrue(r["doris"]["integer-zero"]["sql_null"])
        self.assertEqual("engine_error",r["duckdb"]["integer-zero"]["status"])

    def test_two_errors_are_never_success_or_equivalence_and_missing_transports_stay_unqualified(self):
        rows={e:{c["id"]:{"status":"engine_error","error":"same spelling"} for c in audit.CASES}
              for e in ("duckdb","trino","doris","clickhouse")}
        self.assertTrue(all(x["verdict"]=="unqualified-error-or-unavailable" for x in audit.comparisons(rows)))
        rows["duckdb"][audit.CASES[0]["id"]]["status"]="unavailable"
        self.assertTrue(all(x["verdict"]!="same-observed" for x in audit.comparisons(rows)))


if __name__=="__main__":unittest.main()
