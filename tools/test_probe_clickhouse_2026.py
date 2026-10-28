import subprocess
import unittest
from unittest.mock import patch

import probe_clickhouse_2026 as probe


class NativeProbeSafetyTest(unittest.TestCase):
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
