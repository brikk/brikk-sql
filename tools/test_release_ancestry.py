"""Offline ancestry checks against disposable repositories, never the developer's checkout."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from release_ancestry import OWNER_EXCEPTION_ENV, require_main_ancestor


ROOT = Path(__file__).resolve().parents[1]
TEMP_DIR = ROOT / "build/release-tests"
TEMP_DIR.mkdir(parents=True, exist_ok=True)


def git_test_env():
    return {key: value for key, value in os.environ.items()
            if key not in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE")}


def git(root, *args):
    return subprocess.run(
        ["git", "-c", "user.name=Release test", "-c", "user.email=release-test@example.invalid",
         "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args],
        cwd=root, env=git_test_env(), check=True, text=True, capture_output=True,
    ).stdout.strip()


def create_repository(root):
    origin = root.parent / "origin.git"
    git(root.parent, "init", "--bare", "--initial-branch=main", str(origin))
    git(root.parent, "clone", str(origin), str(root))
    git(root, "commit", "--allow-empty", "-m", "main")
    git(root, "push", "origin", "HEAD:main")
    return origin


class ReleaseAncestryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="brikk-ancestry-", dir=TEMP_DIR)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "checkout"
        self.origin = create_repository(self.root)
        self.main = git(self.root, "rev-parse", "HEAD")
        self.env = {**git_test_env(), OWNER_EXCEPTION_ENV: "false", "GITHUB_ACTIONS": "false"}

    def check_release(self, root=None, **env):
        root = root or self.root
        (root / "tools").mkdir(exist_ok=True)
        shutil.copy2(ROOT / "tools/release_ancestry.py", root / "tools")
        return subprocess.run(["python3", "tools/release_ancestry.py"], cwd=root,
                              env={**self.env, **env}, text=True, capture_output=True)

    def extra_commit(self):
        git(self.root, "commit", "--allow-empty", "-m", "unmerged release change")
        return git(self.root, "rev-parse", "HEAD")

    def assert_refused(self, result, branch, commit):
        self.assertNotEqual(0, result.returncode, result.stdout)
        for text in (commit, f"on branch {branch!r}", "not contained in origin/main", "Merge to main",
                     "cut release/<version>", "Only the repository owner", "AGENTS.md", "owner_exception input",
                     f"{OWNER_EXCEPTION_ENV}=true for a launcher"):
            self.assertIn(text, result.stderr)

    def test_git_helpers_ignore_inherited_repository_environment(self):
        redirected = {name: str(self.root.parent / "not-the-test-repository")
                      for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE")}
        with patch.dict(os.environ, redirected):
            for name in redirected:
                self.assertNotIn(name, git_test_env())
            self.assertEqual(str(self.root), git(self.root, "rev-parse", "--show-toplevel"))

    def test_head_on_main_passes(self):
        result = self.check_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(self.main, result.stdout)
        self.assertIn("contained in origin/main", result.stdout)

    def tracked_file(self, name="tracked file.txt"):
        path = self.root / name
        path.write_text("original\n")
        git(self.root, "add", name)
        git(self.root, "commit", "-m", "tracked fixture")
        git(self.root, "push", "origin", "HEAD:main")
        return path

    def test_staged_and_unstaged_tracked_edits_refuse_even_with_owner_exception(self):
        path = self.tracked_file()
        path.write_text("edited\n")
        for staged in (False, True):
            if staged:
                git(self.root, "add", path.name)
            for exception in ("false", "true"):
                with self.subTest(staged=staged, exception=exception):
                    result = self.check_release(**{OWNER_EXCEPTION_ENV: exception})
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(repr(path.name), result.stderr)
                    self.assertIn("Commit or discard", result.stderr)
                    self.assertIn("does not bypass", result.stderr)

    def test_staged_edit_is_not_hidden_by_worktree_contents_matching_head(self):
        path = self.tracked_file()
        path.write_text("staged edit\n")
        git(self.root, "add", path.name)
        path.write_text("original\n")
        self.assertIn("uncommitted changes", self.check_release().stderr)

    def test_tracked_deletion_rename_and_mode_changes_refuse(self):
        path = self.tracked_file()
        path.chmod(0o755)
        git(self.root, "add", "--chmod=+x", path.name)
        self.assertIn(repr(path.name), self.check_release().stderr)
        git(self.root, "mv", path.name, "renamed\nfile.txt")
        result = self.check_release()
        self.assertIn(repr(path.name), result.stderr)
        self.assertIn(repr("renamed\nfile.txt"), result.stderr)
        (self.root / "renamed\nfile.txt").unlink()
        self.assertIn("uncommitted changes", self.check_release().stderr)

    def test_untracked_and_ignored_files_do_not_block_release(self):
        (self.root / ".git/info/exclude").write_text("ignored.env\n")
        for name in (".env", "ignored.env", ".publish-base.backup.module-template.yaml"):
            (self.root / name).write_text("untracked\n")
        result = self.check_release()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_changed_path_list_is_capped(self):
        for i in range(25):
            (self.root / f"file-{i:02}.txt").write_text("staged new file\n")
        git(self.root, "add", "*.txt")
        result = self.check_release()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("'file-00.txt'", result.stderr)
        self.assertIn("'file-19.txt'", result.stderr)
        self.assertNotIn("'file-20.txt'", result.stderr)
        self.assertIn("and 5 more", result.stderr)

    def test_git_status_failure_refuses_before_fetch_even_with_exception(self):
        error = subprocess.CalledProcessError(128, ["git", "status"], stderr="cannot read index")
        with patch.dict(os.environ, {OWNER_EXCEPTION_ENV: "true"}), patch("release_ancestry.subprocess.run", side_effect=error) as run:
            with self.assertRaisesRegex(SystemExit, "refusing Central publication"):
                require_main_ancestor(self.root)
        self.assertEqual(1, run.call_count)
        self.assertEqual("status", run.call_args.args[0][1])

    def test_release_branch_at_main_commit_passes(self):
        git(self.root, "checkout", "-b", "release/1.2.3")
        result = self.check_release()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_release_from_older_main_commit_passes(self):
        self.extra_commit()
        git(self.root, "push", "origin", "main")
        git(self.root, "checkout", "-b", "release/1.2.3", self.main)
        result = self.check_release()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_release_branch_with_extra_commit_fails(self):
        git(self.root, "checkout", "-b", "release/1.2.3")
        commit = self.extra_commit()
        self.assert_refused(self.check_release(), "release/1.2.3", commit)

    def test_branch_cut_from_feature_branch_fails(self):
        git(self.root, "checkout", "-b", "feature")
        commit = self.extra_commit()
        git(self.root, "checkout", "-b", "release/1.2.3")
        self.assert_refused(self.check_release(), "release/1.2.3", commit)

    def test_owner_exception_passes_with_loud_commit_and_branch_warning(self):
        git(self.root, "checkout", "-b", "release/owner-approved")
        commit = self.extra_commit()
        result = self.check_release(**{OWNER_EXCEPTION_ENV: "true"})
        self.assertEqual(0, result.returncode, result.stderr)
        for text in ("WARNING", "OWNER'S PER-RELEASE EXCEPTION", "NOT on main", commit, "release/owner-approved"):
            self.assertIn(text, result.stdout)
        self.assertNotIn("::warning::", result.stdout)

    def test_owner_exception_adds_github_annotation_with_the_same_warning(self):
        git(self.root, "checkout", "-b", "release/owner-approved")
        self.extra_commit()
        result = self.check_release(**{OWNER_EXCEPTION_ENV: "true", "GITHUB_ACTIONS": "true"})
        self.assertEqual(0, result.returncode, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(2, len(lines), result.stdout)
        self.assertTrue(lines[0].startswith("WARNING: OWNER'S PER-RELEASE EXCEPTION:"), result.stdout)
        self.assertEqual(f"::warning::{lines[0]}", lines[1])

    def test_only_explicit_true_enables_the_override(self):
        git(self.root, "checkout", "-b", "release/1.2.3")
        commit = self.extra_commit()
        for value in ("false", "0", "1", "yes", ""):
            with self.subTest(value=value):
                self.assert_refused(self.check_release(**{OWNER_EXCEPTION_ENV: value}), "release/1.2.3", commit)

    def test_missing_origin_main_fails_even_with_override(self):
        git(self.origin, "update-ref", "-d", "refs/heads/main")
        git(self.root, "update-ref", "-d", "refs/remotes/origin/main")
        result = self.check_release(**{OWNER_EXCEPTION_ENV: "true"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot verify", result.stderr)
        self.assertIn("origin/main", result.stderr)
        self.assertIn("refusing Central publication", result.stderr)

    def test_unfetchable_origin_fails_despite_cached_main_and_override(self):
        git(self.root, "remote", "set-url", "origin", str(self.root.parent / "missing.git"))
        result = self.check_release(**{OWNER_EXCEPTION_ENV: "true"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Fetching and resolving origin/main must succeed", result.stderr)
        self.assertNotIn(".. Fetching", result.stderr)

    def test_missing_tracking_ref_is_fetched(self):
        git(self.root, "update-ref", "-d", "refs/remotes/origin/main")
        result = self.check_release()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_stale_main_is_refreshed(self):
        self.extra_commit()
        git(self.root, "push", "origin", "main")
        git(self.root, "update-ref", "refs/remotes/origin/main", self.main)
        result = self.check_release()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rewound_origin_main_does_not_pass_using_stale_tracking_ref(self):
        commit = self.extra_commit()
        git(self.root, "push", "origin", "main")
        git(self.origin, "update-ref", "refs/heads/main", self.main)
        self.assert_refused(self.check_release(), "main", commit)

    def test_detached_checkout_passes_at_main_commit(self):
        git(self.root, "checkout", "--detach")
        result = self.check_release(GITHUB_REF_NAME="release/1.2.3")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("on branch 'release/1.2.3' (detached HEAD)", result.stdout)

    def test_detached_feature_checkout_fails(self):
        commit = self.extra_commit()
        git(self.root, "checkout", "--detach")
        result = self.check_release(GITHUB_REF_NAME="123/merge")
        self.assert_refused(result, "123/merge", commit)
        self.assertIn("on branch '123/merge' (detached HEAD)", result.stderr)

    def shallow_release(self):
        git(self.root, "branch", "release/1.2.3")
        git(self.root, "push", "origin", "release/1.2.3")
        shallow = self.root.parent / "shallow"
        git(self.root.parent, "clone", "--depth=1", "--single-branch", "--branch", "release/1.2.3",
            self.origin.as_uri(), str(shallow))
        self.assertEqual("true", git(shallow, "rev-parse", "--is-shallow-repository"))
        return shallow

    def test_shallow_release_at_older_main_commit_passes(self):
        self.extra_commit()
        git(self.root, "push", "origin", "main")
        git(self.root, "checkout", "--detach", self.main)
        shallow = self.shallow_release()
        result = self.check_release(shallow)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", git(shallow, "rev-parse", "--is-shallow-repository"))
        self.assertEqual(["origin/main", "origin/release/1.2.3"],
                         git(shallow, "for-each-ref", "--format=%(refname:short)", "refs/remotes").splitlines())

    def test_shallow_release_with_unmerged_commit_fails(self):
        commit = self.extra_commit()
        self.assert_refused(self.check_release(self.shallow_release()), "release/1.2.3", commit)


if __name__ == "__main__":
    unittest.main()
