#!/usr/bin/env bash
#
# Publish a RELEASE of all publishable modules to Maven Central (Central Portal).
#
#   ./publish-release.sh <version>          e.g.  ./publish-release.sh 0.1.0
#
# Required environment variables (Central Portal user token + PGP signing key). The Central
# credential name differs by toolchain version — set EITHER spelling, this script exports both:
#   KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME   (0.11)  / KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME (newer)
#   KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD   (0.11)  / KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD (newer)
#   KOTLIN_TOOLCHAIN_SIGNING_KEY             full ASCII-armored PGP private key
#   KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE  (or KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE) if encrypted
#
# Requires Python 3 (standard library only). This temporarily wraps the shared publishing
# template with Toolchain-native release overrides and restores the original on exit,
# so the committed config keeps the keyless SNAPSHOT flow working. No YAML text edits.
#
# Publishing mode is "auto": Central validates and publishes each deployment without a
# manual Portal step. Track deployments at https://central.sonatype.com/publishing/deployments.
#
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-}"
if [ "$#" -ne 1 ]; then
  echo "usage: $0 <version>   (e.g. 0.1.0)" >&2
  exit 2
fi
python3 tools/release_config.py validate "$VERSION"

# Env-var name compatibility. The name the toolchain reads changed between versions:
#   - 0.11 reads KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME/PASSWORD   (NO underscore)
#   - newer reads KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME/PASSWORD (WITH underscore)
# Accept either and export BOTH so it works regardless of toolchain version.
: "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:=${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME:-}}"
: "${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME:=${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:-}}"
: "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:=${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD:-}}"
: "${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD:=${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:-}}"
export KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME
export KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD

# Signing passphrase: toolchain reads *_SIGNING_KEY_PASSPHRASE; accept the shorter
# *_SIGNING_PASSPHRASE too (both directions).
: "${KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE:=${KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE:-}}"
: "${KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE:=${KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE:-}}"
export KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE

missing=""
[ -n "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:-}" ] || missing="$missing MAVEN_CENTRAL_USERNAME"
[ -n "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:-}" ] || missing="$missing MAVEN_CENTRAL_PASSWORD"
[ -n "${KOTLIN_TOOLCHAIN_SIGNING_KEY:-}" ]           || missing="$missing SIGNING_KEY"
if [ -n "$missing" ]; then
  echo "ERROR: missing required environment variables:$missing" >&2
  exit 2
fi

TMPL="publish.module-template.yaml"
BACKUP="$(mktemp "$PWD/.publish-base.XXXXXX.module-template.yaml")"
cp "$TMPL" "$BACKUP"
restore() { cp "$BACKUP" "$TMPL"; rm -f "$BACKUP"; }
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Keep the base beside the original so its relative apply/repository paths retain
# their meaning. Toolchain merges these publishing overrides after the base settings.
python3 tools/release_config.py template "$VERSION" "./$(basename "$BACKUP")" > "$TMPL"

echo ">> Publishing release ${VERSION} to Maven Central (all modules)..."
# Invoke the registered Portal tasks directly, avoiding repository validation
# of the special Maven Central Portal target in the generic publish command.
# Keep this explicit public-library list in sync with publishing.enabled modules;
# Engine tooling and private consumers must never enter the release graph.
./kotlin task \
  :brikk-sql:publishToMavenCentral \
  :brikk-sql-metadata:publishToMavenCentral \
  :brikk-sql-verify:publishToMavenCentral \
  :brikk-sql-oracle:publishToMavenCentral

echo ""
echo ">> Bundles uploaded for automatic validation and publication. Track deployments at:"
echo "   https://central.sonatype.com/publishing/deployments"
