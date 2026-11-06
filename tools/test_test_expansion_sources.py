"""Offline integrity checks for the regression-source lock and restore tool."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import test_expansion_sources as sources


class TestExpansionSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="test-expansion-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "sources"
        shutil.copytree(sources.ROOT, self.root)
        self.patch_root = patch.object(sources, "ROOT", self.root)
        self.patch_root.start()
        self.addCleanup(self.patch_root.stop)

    def run_tool(self, *arguments):
        with patch("sys.argv", ["test_expansion_sources.py", *arguments]), contextlib.redirect_stdout(io.StringIO()):
            sources.main()

    def test_committed_sources_verify_without_network_access(self):
        with patch.object(sources, "urlopen", side_effect=AssertionError("verification must stay offline")):
            self.run_tool()

    def test_changed_fixture_bytes_are_rejected(self):
        path = self.root / "polyglot/LICENSE"
        path.write_bytes(path.read_bytes() + b"\nmodified\n")
        with self.assertRaisesRegex(SystemExit, "Source identity/hash mismatch"):
            self.run_tool()

    def test_modified_source_identity_is_rejected_even_when_bytes_match(self):
        path = self.root / "manifest.json"
        manifest = json.loads(path.read_text())
        manifest["files"][0]["revision"] = "0" * 40
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(SystemExit, "Source identity/hash mismatch"):
            self.run_tool()

    def test_fetch_does_not_replace_a_file_with_unexpected_bytes(self):
        path = self.root / "polyglot/LICENSE"
        original = path.read_bytes()
        with patch.object(sources, "urlopen", return_value=io.BytesIO(b"wrong upstream payload")):
            with self.assertRaisesRegex(SystemExit, "Source identity/hash mismatch"):
                self.run_tool("--fetch")
        self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
