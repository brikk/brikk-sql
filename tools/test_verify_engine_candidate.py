import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import verify_engine_candidate as gate


class EngineCandidateGateTest(unittest.TestCase):
    def test_safety_checks_remain_enabled_in_optimized_python(self):
        result = subprocess.run(
            [sys.executable, "-O", "-c", "import verify_engine_candidate as g; g.require(False, 'refused')"],
            cwd=pathlib.Path(gate.__file__).parent, capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RuntimeError: refused", result.stderr)

    def run_gate(self, root, commit):
        jar = root / "candidate.jar"
        jar.write_bytes(b"input")
        with patch.object(sys, "argv", [
            "gate", "--client", str(root / "client"), "--core-jar", str(jar),
            "--metadata-jar", str(jar), "--scratch-dir", str(root),
            "--dependency-model-dir", str(root), "--candidate-sha", commit,
        ]):
            gate.main()

    def test_invalid_candidate_commit_refuses_before_reading_peer(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, "git") as git:
            with self.assertRaisesRegex(RuntimeError, "full SHA"):
                self.run_gate(pathlib.Path(directory), "not-a-commit")
            git.assert_not_called()

    def test_dirty_peer_refuses_before_creating_a_snapshot(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, "git", return_value=b" M user.kt\n"):
            root = pathlib.Path(directory)
            with self.assertRaisesRegex(RuntimeError, "Client must be clean"):
                self.run_gate(root, "d" * 40)
            self.assertEqual(list(root.glob("engine-candidate-*")), [])


if __name__ == "__main__":
    unittest.main()
