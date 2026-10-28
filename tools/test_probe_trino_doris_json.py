import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location("probe_json", Path(__file__).with_name("probe_trino_doris_json.py"))
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class JsonProbeTest(unittest.TestCase):
    def test_connection_errors_abort_instead_of_becoming_semantic_evidence(self):
        with patch.object(probe.subprocess, "run", return_value=SimpleNamespace(
            returncode=1, stdout="", stderr="ERROR 2002: Can't connect to MySQL server")):
            with self.assertRaises(RuntimeError):
                probe.run("doris", "missing", "SELECT 1")

    def test_transport_keeps_sql_null_separate_from_json_null(self):
        self.assertEqual({"status": "sql-null"}, probe.observation({"exitCode": 0, "stdout": "SQL_NULL\n"}))
        self.assertEqual({"status": "value", "value": "null"}, probe.observation({"exitCode": 0, "stdout": "VALUE:6E756C6C\n"}))

    def test_transport_preserves_scale_whitespace_and_duplicate_keys(self):
        for text in ('1.2300', '{"a":1,"a":2}', ' a\n\\b\t '):
            self.assertEqual(text, probe.observation({"exitCode": 0, "stdout": "VALUE:" + text.encode().hex() + "\n"})["value"])

    def test_errors_are_not_values_and_unexpected_output_is_fatal(self):
        self.assertEqual({"status": "error"}, probe.observation({"exitCode": 1, "stdout": ""}))
        with self.assertRaises(ValueError):
            probe.observation({"exitCode": 0, "stdout": "connection unavailable"})

    def test_vectors_are_unique_and_cover_the_requested_areas(self):
        cases = json.loads((probe.DATA / "vectors.json").read_text())["cases"]
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        self.assertTrue({"exponent", "decimal", "duplicates", "path"} <= {case["area"] for case in cases})
        self.assertTrue(all(case["trino"] and case["doris"] and case["concept"] for case in cases))


if __name__ == "__main__":
    unittest.main()
