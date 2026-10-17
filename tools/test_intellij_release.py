import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ScopedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools').mkdir()
        self.modules = [self.root / m / 'module.yaml' for m in ('brikk-sql-intellij-support', 'brikk-sql-intellij-test-support')]
        for name in ['publish_intellij_support.py', 'release_config.py']:
            shutil.copy2(ROOT / 'tools' / name, self.root / 'tools' / name)
        self.template = 'settings:\n  publishing:\n    version: 0.19.0-SNAPSHOT\n'
        self.module = 'product: jvm/lib\nsettings:\n  publishing:\n    version: 0.1.2-SNAPSHOT\n'
        (self.root / 'publish.module-template.yaml').write_text(self.template)
        for module in self.modules:
            module.parent.mkdir()
            module.write_text(self.module)
        wrapper = self.root / 'kotlin'
        wrapper.write_text('#!/usr/bin/env python3\nimport json,sys,os\nfrom pathlib import Path\n'
                           'Path("calls.json").write_text(json.dumps(sys.argv[1:]))\n'
                           'Path("observed-module").write_text(Path("brikk-sql-intellij-support/module.yaml").read_text())\n'
                           'Path("observed-test-module").write_text(Path("brikk-sql-intellij-test-support/module.yaml").read_text())\n'
                           'sys.exit(int(os.environ.get("TEST_EXIT", "0")))\n')
        wrapper.chmod(0o755)

    def run_release(self, *extra, exit_code=0):
        return subprocess.run(['python3', 'tools/publish_intellij_support.py', '0.1.0', '--local', *extra],
                              cwd=self.root, env={**os.environ, 'TEST_EXIT': str(exit_code)}, capture_output=True, text=True)

    def assert_restored(self):
        self.assertEqual(self.template, (self.root / 'publish.module-template.yaml').read_text())
        for module in self.modules:
            self.assertEqual(self.module, module.read_text())
        self.assertFalse((self.root / '.intellij-publish-base.module-template.yaml').exists())

    def test_intellij_only_and_restoration(self):
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['publish', 'mavenLocal', '-m', 'brikk-sql-intellij-support', '-m', 'brikk-sql-intellij-test-support',
                          '--non-transitive'],
                         json.loads((self.root / 'calls.json').read_text()))
        for observed in ('observed-module', 'observed-test-module'):
            self.assertIn('version: 0.1.0\n', (self.root / observed).read_text())
        self.assert_restored()

    def test_explicit_core_publishes_the_verification_api_but_never_engines(self):
        result = self.run_release('--core-version', '0.19.0')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = json.loads((self.root / 'calls.json').read_text())
        self.assertEqual(['publish', 'mavenLocal', '-m', 'brikk-sql-metadata', '-m', 'brikk-sql', '-m', 'brikk-sql-verify-api',
                          '-m', 'brikk-sql-intellij-support', '-m', 'brikk-sql-intellij-test-support'], calls)
        self.assertFalse([m for m in calls if m.startswith('brikk-sql-verify-') and m != 'brikk-sql-verify-api'])
        self.assert_restored()

    def test_failed_toolchain_restores_templates(self):
        self.assertNotEqual(0, self.run_release(exit_code=23).returncode)
        self.assert_restored()

    def test_central_release_requires_core_version(self):
        result = subprocess.run(['python3', 'tools/publish_intellij_support.py', '0.1.0'], cwd=self.root, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_snapshot_rejected_without_invocation(self):
        result = subprocess.run(['python3', 'tools/publish_intellij_support.py', '0.1.0-SNAPSHOT', '--local'],
                                cwd=self.root, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()


if __name__ == '__main__': unittest.main()
