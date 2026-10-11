"""Offline release regression tests; no credentials or Maven uploads required."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from release_config import release_template, validate_version


ROOT = Path(__file__).resolve().parents[1]
TEMP_DIR = "/tmp/opencode" if Path("/tmp/opencode").is_dir() else None


class ReleaseVersionTest(unittest.TestCase):
    def test_valid_semver(self):
        for version in (
            "0.0.0", "0.16.1", "10.20.30", "1.0.0-alpha", "1.0.0-0",
            "1.0.0-rc.1", "1.0.0-01a", "1.0.0+build.001", "1.0.0-rc.1+sha.abc",
        ):
            with self.subTest(version=version):
                self.assertEqual(version, validate_version(version))

    def test_invalid_or_snapshot_versions(self):
        for version in (
            "", "v1.2.3", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-01",
            "1.2.3-rc.01", "1.2.3-", "1.2.3-rc..1", "1.2.3+", "1.2.3+a..b",
            "1.2.3-rc_1", "1.2.3-SNAPSHOT", "1.2.3-SNAPSHOT+build",
            "1.2.3-rc.SNAPSHOT", "1.2.3-snapshot", "1.2.3\n", " 1.2.3",
            "1.2.3;echo unsafe", "１.2.3",
        ):
            with self.subTest(version=version):
                with self.assertRaises(ValueError):
                    validate_version(version)

    def test_override_is_composed_without_parsing_base_yaml(self):
        template = release_template("0.16.1", './base "quoted".module-template.yaml')
        self.assertIn('  - "./base \\"quoted\\".module-template.yaml"', template)
        self.assertIn('version: "0.16.1"', template)
        self.assertIn("mavenCentral: { enabled: true, publishingMode: auto }", template)
        self.assertIn("signArtifacts: true", template)


class ReleaseLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="brikk-release-", dir=TEMP_DIR)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tools").mkdir()
        shutil.copy2(ROOT / "publish-release.sh", self.root)
        shutil.copy2(ROOT / "tools/release_config.py", self.root / "tools")
        self.original = (
            "# Deliberately different indentation/comments; preserve byte-for-byte.\n"
            "settings:\n publishing:\n  version: 1.0.0-SNAPSHOT\n"
        )
        (self.root / "publish.module-template.yaml").write_text(self.original)
        fake_kotlin = self.root / "kotlin"
        fake_kotlin.write_text(
            "#!/bin/bash\n"
            "set -eu\n"
            "cp publish.module-template.yaml observed.yaml\n"
            "cp .publish-base.*.module-template.yaml observed-base.yaml\n"
            "printf '%s\\n' \"$@\" > observed-args.txt\n"
            "if [ \"${FAKE_TERMINATE:-0}\" = 1 ]; then kill -TERM \"$PPID\"; fi\n"
            "exit \"${FAKE_EXIT_CODE:-0}\"\n"
        )
        fake_kotlin.chmod(0o755)
        self.env = {
            **os.environ,
            "KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME": "test-user",
            "KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD": "test-password",
            "KOTLIN_TOOLCHAIN_SIGNING_KEY": "test-key",
        }

    def run_release(self, version="0.16.1", exit_code=0, modules=()):
        return subprocess.run(
            ["bash", "publish-release.sh", version, *modules], cwd=self.root,
            env={**self.env, "FAKE_EXIT_CODE": str(exit_code)},
            text=True, capture_output=True,
        )

    def assert_restored(self):
        self.assertEqual(self.original, (self.root / "publish.module-template.yaml").read_text())
        self.assertEqual([], list(self.root.glob(".publish-base.*.module-template.yaml")))

    def test_success_restores_original_and_publishes_only_sql_modules(self):
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_restored()
        self.assertEqual(self.original, (self.root / "observed-base.yaml").read_text())
        self.assertIn('version: "0.16.1"', (self.root / "observed.yaml").read_text())
        self.assertEqual([
            "task", ":brikk-sql:publishToMavenCentral",
            ":brikk-sql-metadata:publishToMavenCentral",
            ":brikk-sql-verify:publishToMavenCentral",
            ":brikk-sql-oracle:publishToMavenCentral",
        ], (self.root / "observed-args.txt").read_text().splitlines())

    def test_failed_publication_restores_original(self):
        result = self.run_release(exit_code=17)
        self.assertEqual(17, result.returncode, result.stderr)
        self.assert_restored()

    def test_verifier_oracle_release_does_not_republish_core(self):
        result = self.run_release("0.19.0", modules=("brikk-sql-verify", "brikk-sql-oracle"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "task", ":brikk-sql-verify:publishToMavenCentral",
            ":brikk-sql-oracle:publishToMavenCentral",
        ], (self.root / "observed-args.txt").read_text().splitlines())
        self.assertIn('version: "0.19.0"', (self.root / "observed.yaml").read_text())
        self.assert_restored()

    def test_invalid_and_duplicate_modules_never_invoke_toolchain(self):
        for modules in (
            ("brikk-sql-intellij-support",), ("brikk-sql-intellij-tooling",),
            ("brikk-sql-verify; echo bad",), ("",),
            ("brikk-sql-verify", "brikk-sql-verify"),
        ):
            with self.subTest(modules=modules):
                result = self.run_release(modules=modules)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.root / "observed-args.txt").exists())
                self.assert_restored()

    def test_selected_release_failure_restores_original(self):
        result = self.run_release("0.19.0", exit_code=17, modules=("brikk-sql-verify", "brikk-sql-oracle"))
        self.assertEqual(17, result.returncode, result.stderr)
        self.assert_restored()

    def test_invalid_version_never_edits_template_or_invokes_toolchain(self):
        result = self.run_release("01.2.3")
        self.assertNotEqual(0, result.returncode)
        self.assert_restored()
        self.assertFalse((self.root / "observed.yaml").exists())

    def test_termination_restores_original(self):
        self.env["FAKE_TERMINATE"] = "1"
        result = self.run_release()
        self.assertEqual(143, result.returncode, result.stderr)
        self.assert_restored()

    def test_missing_credentials_never_edits_template_or_invokes_toolchain(self):
        for name in (
            "KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME", "KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD",
            "KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME", "KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD",
            "KOTLIN_TOOLCHAIN_SIGNING_KEY",
        ):
            self.env.pop(name, None)
        result = self.run_release()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assert_restored()
        self.assertFalse((self.root / "observed.yaml").exists())


@unittest.skipUnless(os.environ.get("TEST_KOTLIN_RELEASE_MODEL") == "1", "opt-in Toolchain model check")
class ReleaseModelTest(unittest.TestCase):
    def test_toolchain_merges_release_overrides_and_preserves_base_settings(self):
        with tempfile.TemporaryDirectory(prefix="brikk-release-model-", dir=TEMP_DIR) as temp:
            root = Path(temp)
            shutil.copy2(ROOT / "publish.module-template.yaml", root / ".publish-base.test.module-template.yaml")
            shutil.copy2(ROOT / "publish-targets.module-template.yaml", root)
            (root / "publish.module-template.yaml").write_text(
                release_template("0.16.1", "./.publish-base.test.module-template.yaml")
            )
            (root / ".env").write_text("brikk.mavencentral.user=test\nbrikk.mavencentral.pass=test\n")
            (root / "project.yaml").write_text("modules: [ library ]\n")
            (root / "library").mkdir()
            (root / "library/module.yaml").write_text(
                "product: jvm/lib\n"
                "description: Offline release model regression fixture.\n"
                "apply: [ ../publish.module-template.yaml ]\n"
                "settings:\n  publishing:\n    artifactId: release-model-test\n"
            )
            result = subprocess.run(
                [str(ROOT / "kotlin"), "show", "settings", "--project-dir", str(root), "--all-modules"],
                text=True, capture_output=True, timeout=120,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("0.16.1", result.stdout)
            self.assertNotIn("1.0.0-SNAPSHOT", result.stdout)
            self.assertIn("dev.brikk.house", result.stdout)
            self.assertIn("release-model-test", result.stdout)
            self.assertIn("https://github.com/brikk/brikk-sql", result.stdout)
            self.assertRegex(result.stdout, r"signArtifacts\s*[:=]\s*true")
            self.assertRegex(result.stdout, r"publishingMode\s*[:=]\s*auto")
            self.assertRegex(result.stdout, r"publishSources\s*[:=]\s*true")


if __name__ == "__main__":
    unittest.main()
