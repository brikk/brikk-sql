#!/usr/bin/env bash
# Install the actual Toolchain publications, including generated POM/module metadata.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./kotlin publish mavenLocal "$@"
