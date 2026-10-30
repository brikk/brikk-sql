import subprocess
import unittest
from unittest.mock import patch

import probe_clickhouse_2026 as probe


class NativeProbeSafetyTest(unittest.TestCase):
    def test_semantic_execution_vectors_only_enter_parser_capture_when_requested(self):
        fixture = {"cases": [{"id": "syntax"}], "execution": [{"id": "semantic-error"}]}
        self.assertEqual([{"id": "syntax"}], probe.parsing_cases(fixture))
        fixture["parse_execution"] = True
        self.assertEqual([{"id": "syntax"}, {"id": "semantic-error"}], probe.parsing_cases(fixture))

    def test_expected_error_can_require_the_specific_semantic_rule(self):
        case = {"error_code": 36, "error_name": "BAD_ARGUMENTS", "error_contains": ["Mixing", "ambiguous"]}
        self.assertTrue(probe.expected_execution(case,
            {"returncode": 36, "stdout": "", "stderr": "Mixing placeholders is ambiguous (BAD_ARGUMENTS)"}))
        self.assertFalse(probe.expected_execution(case,
            {"returncode": 36, "stdout": "", "stderr": "Wrong composition operand (BAD_ARGUMENTS)"}))

    def test_parser_expectations_are_independent_of_semantic_expectations(self):
        case = {"id": "composition", "parse_expectations": {"26.8.34.13": False, "26.10.2.103": True},
                "expectations": {"26.10.2.103": {"error_code": 36, "error_name": "BAD_ARGUMENTS"}}}
        self.assertFalse(probe.parsing_expectation(case, "26.8.34.13"))
        self.assertTrue(probe.parsing_expectation(case, "26.10.2.103"))
        with self.assertRaises(ValueError):
            probe.parsing_expectation(case, "26.9.1")
        self.assertFalse(probe.parsing_expectation({"accept": False}, "26.10.2.103"))
        self.assertTrue(probe.parsing_expectation({}, "26.10.2.103"))

    def test_versioned_outcomes_require_an_explicit_expectation_for_each_pin(self):
        case = {"id": "changed", "expectations": {
            "26.8.34.13": {"error_code": 43, "error_name": "ILLEGAL_TYPE_OF_ARGUMENT"},
            "26.10.2.103": {"stdout": "ok\n"},
        }}
        self.assertTrue(probe.expected_execution(probe.execution_expectation(case, "26.8.34.13"),
            {"returncode": 43, "stdout": "", "stderr": "(ILLEGAL_TYPE_OF_ARGUMENT)"}))
        self.assertTrue(probe.expected_execution(probe.execution_expectation(case, "26.10.2.103"),
            {"returncode": 0, "stdout": "ok\n", "stderr": ""}))
        with self.assertRaises(ValueError):
            probe.execution_expectation(case, "26.9.1")

    def test_expected_native_errors_are_not_successes_or_arbitrary_failures(self):
        case = {"error_code": 48, "error_name": "NOT_IMPLEMENTED"}
        self.assertTrue(probe.expected_execution(case, {"returncode": 48, "stdout": "", "stderr": "(NOT_IMPLEMENTED)"}))
        self.assertFalse(probe.expected_execution(case, {"returncode": 0, "stdout": "", "stderr": ""}))
        self.assertFalse(probe.expected_execution(case, {"returncode": None, "stdout": "", "stderr": "deadline"}))
        self.assertFalse(probe.expected_execution(case, {"returncode": 48, "stdout": "", "stderr": "unrelated error"}))

    @patch.object(probe.subprocess, "run")
    def test_queries_are_isolated_and_cleanup_only_their_own_container(self, run):
        run.side_effect = [subprocess.CompletedProcess([], 0, "26.10.2.103\n", ""),
                           subprocess.CompletedProcess([], 0, "", "")]
        result = probe.query({"image": "test@sha256:abc"}, "SELECT version()")
        self.assertEqual(0, result["returncode"])
        command = run.call_args_list[0].args[0]
        self.assertEqual("none", command[command.index("--network") + 1])
        self.assertEqual("never", command[command.index("--pull") + 1])
        self.assertNotIn("--volume", command)
        self.assertNotIn("--enable_streaming_queries", command)
        name = command[command.index("--name") + 1]
        self.assertTrue(name.startswith("brikk-ch26-audit-"))
        self.assertEqual(["docker", "rm", "-f", name], run.call_args_list[1].args[0])
        self.assertEqual(30, run.call_args_list[0].kwargs["timeout"])

    @patch.object(probe.subprocess, "run")
    def test_host_deadline_still_removes_only_the_owned_container(self, run):
        run.side_effect = [subprocess.TimeoutExpired([], 30), subprocess.CompletedProcess([], 0, "", "")]
        result = probe.query({"image": "test@sha256:abc"}, "SELECT x FROM t STREAM BOUNDED", streaming=True)
        self.assertIsNone(result["returncode"])
        self.assertIn("Host deadline", result["stderr"])
        command = run.call_args_list[0].args[0]
        self.assertIn("--enable_streaming_queries", command)
        name = command[command.index("--name") + 1]
        self.assertEqual(["docker", "rm", "-f", name], run.call_args_list[1].args[0])


if __name__ == "__main__":
    unittest.main()
