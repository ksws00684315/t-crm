#!/usr/bin/env bash
# L-071: second concurrent release.sh must fail while the mkdir lock is held.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORKDIR="${TMPDIR:-/tmp}/tcrm-t105-release-lock-test.$$"
LOCK_DIR="$WORKDIR/lock"
ENV_FILE="$WORKDIR/env"
RELEASE="$ROOT/deploy/scripts/release.sh"
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

mkdir -p "$WORKDIR"
# Minimal .env so require_tools finds the file (check_env runs after the lock).
: > "$ENV_FILE"

# Hold the lock the same way release.sh does (mkdir the lock directory).
mkdir "$LOCK_DIR"

out="$(mktemp)"
set +e
TCRM_RELEASE_LOCK_DIR="$LOCK_DIR" \
TCRM_ENV_FILE="$ENV_FILE" \
  bash "$RELEASE" deploy 1.0.0 >"$out" 2>&1
rc=$?
set -e

if [ "$rc" -eq 0 ]; then
  echo "FAIL: expected non-zero exit while lock held; got 0" >&2
  cat "$out" >&2
  exit 1
fi
if ! grep -q '另一个 release.sh 正在运行' "$out"; then
  echo "FAIL: expected lock message; got:" >&2
  cat "$out" >&2
  exit 1
fi
echo "release-lock: second deploy blocked (rc=$rc) OK"

# Release lock; next run must get past the lock (may fail later on missing env keys).
rmdir "$LOCK_DIR"
out2="$(mktemp)"
set +e
TCRM_RELEASE_LOCK_DIR="$LOCK_DIR" \
TCRM_ENV_FILE="$ENV_FILE" \
  bash "$RELEASE" deploy 1.0.0 >"$out2" 2>&1
rc2=$?
set -e
if grep -q '另一个 release.sh 正在运行' "$out2"; then
  echo "FAIL: lock still reported after release" >&2
  cat "$out2" >&2
  exit 1
fi
# Lock dir should have been created then removed by on_exit (even on later failure).
if [ -d "$LOCK_DIR" ]; then
  echo "FAIL: lock dir left behind after run" >&2
  exit 1
fi
echo "release-lock: after unlock, lock message absent (rc=$rc2) OK"
echo "release-lock: all tests passed"
rm -f "$out" "$out2"
