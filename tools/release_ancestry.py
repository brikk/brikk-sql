#!/usr/bin/env python3
"""Require a Central release commit to be contained in a freshly fetched origin/main."""

import os
from pathlib import Path
import subprocess


OWNER_EXCEPTION_ENV = "BRIKK_RELEASE_OWNER_EXCEPTION"


def require_main_ancestor(root: Path) -> None:
    def git(*args, check=True):
        return subprocess.run(["git", *args], cwd=root, check=check, text=True, capture_output=True)

    context = "HEAD"
    try:
        commit = git("rev-parse", "--verify", "HEAD^{commit}").stdout.strip()
        branch = git("symbolic-ref", "--quiet", "--short", "HEAD", check=False).stdout.strip()
        if branch:
            branch_label = repr(branch)
        else:
            branch_label = f"{os.environ.get('GITHUB_REF_NAME', '(unknown ref)')!r} (detached HEAD)"
        context = f"commit {commit} on branch {branch_label}"
        shallow = git("rev-parse", "--is-shallow-repository").stdout.strip() == "true"
        # Fetch only main, without tags or submodules; complete its history for shallow CI checkouts.
        git("fetch", "--no-tags", "--no-recurse-submodules", *(["--unshallow"] if shallow else []),
            "origin", "+refs/heads/main:refs/remotes/origin/main")
        git("rev-parse", "--verify", "refs/remotes/origin/main^{commit}")
        result = git("merge-base", "--is-ancestor", commit, "refs/remotes/origin/main", check=False)
        if result.returncode not in (0, 1):
            result.check_returncode()
    except (subprocess.CalledProcessError, OSError) as error:
        detail = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        detail = detail.rstrip(".")
        raise SystemExit(f"ERROR: Cannot verify {context} against origin/main: {detail}. "
                         "Fetching and resolving origin/main must succeed; refusing Central publication.") from None

    if result.returncode == 0:
        print(f"Release ancestry verified: {context} is contained in origin/main.", flush=True)
        return
    if os.environ.get(OWNER_EXCEPTION_ENV) == "true":
        warning = (f"WARNING: OWNER'S PER-RELEASE EXCEPTION: proceeding with Central publication from {context}, "
                   "which is NOT on main (not contained in origin/main).")
        print(warning, flush=True)
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print(f"::warning::{warning}", flush=True)
        return
    raise SystemExit(f"ERROR: Refusing Central publication: {context} is not contained in origin/main. "
                     "Merge to main, then cut release/<version> from that main commit. "
                     "Only the repository owner can approve an explicit per-release exception (see AGENTS.md); "
                     "only with that approval for this release, set the release workflow's owner_exception input "
                     f"to true or {OWNER_EXCEPTION_ENV}=true for a launcher.")


if __name__ == "__main__":
    require_main_ancestor(Path(__file__).resolve().parents[1])
