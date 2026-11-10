import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from release_ancestry import OWNER_EXCEPTION_ENV
from test_release_ancestry import TEMP_DIR, create_repository, git, git_test_env

ROOT = Path(__file__).resolve().parents[1]


class ScopedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='brikk-intellij-release-', dir=TEMP_DIR)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'checkout'
        create_repository(self.root)
        git(self.root, 'checkout', '-b', 'release/test')
        (self.root / 'tools').mkdir()
        self.modules = [self.root / m / 'module.yaml' for m in ('brikk-sql-intellij-support', 'brikk-sql-intellij-jdbc-support', 'brikk-sql-intellij-test-support')]
        for name in ['publish_intellij_support.py', 'release_config.py', 'release_ancestry.py']:
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
                            'Path("observed-jdbc-module").write_text(Path("brikk-sql-intellij-jdbc-support/module.yaml").read_text())\n'
                            'Path("observed-test-module").write_text(Path("brikk-sql-intellij-test-support/module.yaml").read_text())\n'
                            'Path("observed-template").write_text(Path("publish.module-template.yaml").read_text())\n'
                           'sys.exit(int(os.environ.get("TEST_EXIT", "0")))\n')
        wrapper.chmod(0o755)
        git(self.root, 'add', '.')
        git(self.root, 'commit', '-m', 'tracked release fixtures')
        git(self.root, 'push', 'origin', 'HEAD:main')
        self.env = {**git_test_env(), OWNER_EXCEPTION_ENV: 'false',
                    'KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME': 'test-user',
                    'KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD': 'test-password',
                    'KOTLIN_TOOLCHAIN_SIGNING_KEY': 'test-key'}

    def run_release(self, *extra, exit_code=0, local=True):
        return subprocess.run(['python3', 'tools/publish_intellij_support.py', '0.1.0', *(['--local'] if local else []), *extra],
                              cwd=self.root, env={**self.env, 'TEST_EXIT': str(exit_code)}, capture_output=True, text=True)

    def assert_restored(self):
        self.assertEqual(self.template, (self.root / 'publish.module-template.yaml').read_text())
        for module in self.modules:
            self.assertEqual(self.module, module.read_text())
        self.assertFalse((self.root / '.intellij-publish-base.module-template.yaml').exists())

    def test_intellij_only_and_restoration(self):
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['publish', 'mavenLocal', '-m', 'brikk-sql-intellij-support', '-m', 'brikk-sql-intellij-jdbc-support', '-m', 'brikk-sql-intellij-test-support',
                          '--non-transitive'],
                         json.loads((self.root / 'calls.json').read_text()))
        for observed in ('observed-module', 'observed-jdbc-module', 'observed-test-module'):
            self.assertIn('version: 0.1.0\n', (self.root / observed).read_text())
        self.assert_restored()

    def test_explicit_core_publishes_the_verification_api_but_never_engines(self):
        result = self.run_release('--core-version', '0.19.0')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = json.loads((self.root / 'calls.json').read_text())
        self.assertEqual(['publish', 'mavenLocal', '-m', 'brikk-sql-metadata', '-m', 'brikk-sql', '-m', 'brikk-sql-verify-api',
                          '-m', 'brikk-sql-intellij-support', '-m', 'brikk-sql-intellij-jdbc-support', '-m', 'brikk-sql-intellij-test-support'], calls)
        self.assertFalse([m for m in calls if m.startswith('brikk-sql-verify-') and m != 'brikk-sql-verify-api'])
        self.assert_restored()

    def test_failed_toolchain_restores_templates(self):
        self.assertNotEqual(0, self.run_release(exit_code=23).returncode)
        self.assert_restored()

    def test_support_patch_does_not_republish_immutable_core(self):
        result = self.run_release('--core-version', '0.20.0', '--skip-core-publication')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['publish', 'mavenLocal', '-m', 'brikk-sql-intellij-support',
                          '-m', 'brikk-sql-intellij-jdbc-support', '-m', 'brikk-sql-intellij-test-support', '--non-transitive'],
                         json.loads((self.root / 'calls.json').read_text()))
        self.assertIn('version: "0.20.0"', (self.root / 'observed-template').read_text())
        self.assert_restored()

    def test_skip_core_requires_an_explicit_core_version(self):
        result = self.run_release('--skip-core-publication')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_central_release_requires_core_version(self):
        result = subprocess.run(['python3', 'tools/publish_intellij_support.py', '0.1.0'], cwd=self.root,
                                env=git_test_env(), capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_central_release_checks_ancestry_and_reaches_fake_toolchain(self):
        result = self.run_release('--core-version', '0.20.0', '--skip-core-publication', local=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Release ancestry verified', result.stdout)
        self.assertEqual(['task', ':brikk-sql-intellij-support:publishToMavenCentral',
                          ':brikk-sql-intellij-jdbc-support:publishToMavenCentral',
                          ':brikk-sql-intellij-test-support:publishToMavenCentral'],
                         json.loads((self.root / 'calls.json').read_text()))
        self.assert_restored()

    def test_workflow_guard_then_local_install_then_central_restores_tracked_descriptors(self):
        initial = subprocess.run(['python3', 'tools/release_ancestry.py'], cwd=self.root,
                                 env=self.env, capture_output=True, text=True)
        self.assertEqual(0, initial.returncode, initial.stderr)
        (self.root / '.env').write_text('brikk.mavencentral.user=unused\nbrikk.mavencentral.pass=unused\n')
        for publish_core in (False, True):
            options = ['--core-version', '0.20.0'] + ([] if publish_core else ['--skip-core-publication'])
            for local in (True, False):
                result = self.run_release(*options, local=local)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assert_restored()
                self.assertEqual('', git(self.root, 'status', '--porcelain', '--untracked-files=no'))
            self.assertIn('Release ancestry verified', result.stdout)

    def test_central_refuses_a_staged_descriptor_edit_after_local_install(self):
        options = ['--core-version', '0.20.0', '--skip-core-publication']
        self.assertEqual(0, self.run_release(*options).returncode)
        self.modules[0].write_text(self.module + '# real edit\n')
        git(self.root, 'add', str(self.modules[0]))
        result = self.run_release(*options, local=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('uncommitted changes', result.stderr)
        self.assertIn('brikk-sql-intellij-support/module.yaml', result.stderr)
        self.assertEqual(['publish', 'mavenLocal'], json.loads((self.root / 'calls.json').read_text())[:2])

    def test_central_release_refuses_unmerged_commit_before_changing_files(self):
        git(self.root, 'commit', '--allow-empty', '-m', 'not on main')
        result = self.run_release('--core-version', '0.20.0', local=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('not contained in origin/main', result.stderr)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_central_release_owner_exception_reaches_fake_toolchain(self):
        git(self.root, 'commit', '--allow-empty', '-m', 'owner-approved release')
        self.env[OWNER_EXCEPTION_ENV] = 'true'
        result = self.run_release('--core-version', '0.20.0', '--skip-core-publication', local=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("OWNER'S PER-RELEASE EXCEPTION", result.stdout)
        self.assertTrue((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_central_ancestry_refusal_precedes_missing_credentials(self):
        git(self.root, 'commit', '--allow-empty', '-m', 'not on main')
        self.env = {k: v for k, v in self.env.items() if not k.startswith('KOTLIN_TOOLCHAIN_')}
        result = self.run_release('--core-version', '0.20.0', local=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('not contained in origin/main', result.stderr)
        self.assertNotIn('Missing KOTLIN_TOOLCHAIN_', result.stderr)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_local_release_does_not_check_ancestry_or_fetch(self):
        git(self.root, 'commit', '--allow-empty', '-m', 'not on main')
        git(self.root, 'remote', 'remove', 'origin')
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('Release ancestry', result.stdout)
        self.assert_restored()

    def test_snapshot_rejected_without_invocation(self):
        result = subprocess.run(['python3', 'tools/publish_intellij_support.py', '0.1.0-SNAPSHOT', '--local'],
                                cwd=self.root, env=git_test_env(), capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'calls.json').exists())
        self.assert_restored()

    def test_named_snapshot_requires_explicit_local_only_opt_in_and_keeps_core_released(self):
        version = '0.4.1-livecatalog-g123abc-SNAPSHOT'
        result = subprocess.run(['python3', 'tools/publish_intellij_support.py', version, '--snapshot', '--local',
                                 '--core-version', '0.23.0', '--skip-core-publication'],
                                cwd=self.root, env=git_test_env(), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        for observed in ('observed-module', 'observed-jdbc-module', 'observed-test-module'):
            self.assertIn('version: ' + version, (self.root / observed).read_text())
        self.assertIn('version: "0.23.0"', (self.root / 'observed-template').read_text())
        self.assertEqual(['publish', 'mavenLocal', '-m', 'brikk-sql-intellij-support',
                          '-m', 'brikk-sql-intellij-jdbc-support', '-m', 'brikk-sql-intellij-test-support', '--non-transitive'],
                         json.loads((self.root / 'calls.json').read_text()))
        self.assert_restored()

    def test_snapshot_opt_in_cannot_publish_remotely_or_republish_core(self):
        for options in ([], ['--local'], ['--local', '--core-version', '0.23.0'],
                        ['--core-version', '0.23.0', '--skip-core-publication']):
            result = subprocess.run(['python3', 'tools/publish_intellij_support.py',
                                     '0.4.1-races-SNAPSHOT', '--snapshot', *options],
                                    cwd=self.root, env=git_test_env(), capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((self.root / 'calls.json').exists())
            self.assert_restored()


if __name__ == '__main__': unittest.main()
