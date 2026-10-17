#!/usr/bin/env bash
#
# Publish a RELEASE of the SQL modules to Maven Central (Central Portal).
#
#   ./publish-release.sh [--local] <version> [module ...]
#   ./publish-release.sh 0.19.0 brikk-sql-verify brikk-sql-oracle
# --local installs the same formal release coordinates into Maven Local instead, unsigned and without
# credentials (the selection only, its in-repo dependencies already installed at that version).
# With no module arguments, publish every aligned SQL module (core, metadata, the verify family and
# oracle). A selection publishes only the named modules; their in-repo dependencies must already be
# released at the same version.
# IntelliJ support has its own independently versioned release command.
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

SQL_MODULES=(
  brikk-sql brikk-sql-metadata
  brikk-sql-verify-api brikk-sql-verify-trino brikk-sql-verify-duckdb brikk-sql-verify-doris
  brikk-sql-verify-shardingsphere brikk-sql-verify-clickhouse brikk-sql-verify
  brikk-sql-oracle
)

LOCAL=false
if [ "${1:-}" = "--local" ]; then
  LOCAL=true
  shift
fi
VERSION="${1:-}"
if [ "$#" -lt 1 ]; then
  echo "usage: $0 [--local] <version> [module ...]; modules: ${SQL_MODULES[*]}" >&2
  exit 2
fi
python3 tools/release_config.py validate "$VERSION"
shift

MODULES=("$@")
if [ "${#MODULES[@]}" -eq 0 ]; then
  MODULES=("${SQL_MODULES[@]}")
fi
TASKS=()
SELECTED=()
declare -A SEEN=()
for module in "${MODULES[@]}"; do
  supported=false
  for known in "${SQL_MODULES[@]}"; do
    if [ "$module" = "$known" ]; then supported=true; fi
  done
  if [ "$supported" != true ]; then
    echo "ERROR: unsupported SQL release module: $module" >&2
    exit 2
  fi
  if [ -n "${SEEN[$module]:-}" ]; then
    echo "ERROR: duplicate SQL release module: $module" >&2
    exit 2
  fi
  SEEN[$module]=1
  TASKS+=(":${module}:publishToMavenCentral")
  SELECTED+=(-m "$module")
done

if [ "$LOCAL" = true ]; then
  TMPL="publish.module-template.yaml"
  BACKUP="$(mktemp "$PWD/.publish-base.XXXXXX.module-template.yaml")"
  cp "$TMPL" "$BACKUP"
  restore() { cp "$BACKUP" "$TMPL"; rm -f "$BACKUP"; }
  trap restore EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  python3 tools/release_config.py template "$VERSION" "./$(basename "$BACKUP")" --local > "$TMPL"
  echo ">> Installing formal release ${VERSION} into Maven Local: ${MODULES[*]}"
  ./kotlin publish mavenLocal "${SELECTED[@]}" --non-transitive
  exit 0
fi

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

echo ">> Publishing release ${VERSION} to Maven Central: ${MODULES[*]}"
# Invoke the registered Portal tasks directly, avoiding repository validation
# of the special Maven Central Portal target in the generic publish command.
# The allowlist above prevents build tooling or independently versioned libraries
# entering this release graph. Dependency compilation does not publish dependencies.
./kotlin task "${TASKS[@]}"

echo ""
echo ">> Bundles uploaded for automatic validation and publication. Track deployments at:"
echo "   https://central.sonatype.com/publishing/deployments"
