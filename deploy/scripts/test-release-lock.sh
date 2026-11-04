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

# Regression (rework E2E 2026-11-03): an unbraced "$var" directly followed by a multibyte
# character crashes macOS bash 3.2 under UTF-8 locales (the byte is parsed as part of the
# variable name -> "unbound variable"), and release.sh declares bash 3.2 compatibility. CI's
# Linux bash 5 cannot reproduce it (and BSD grep lacks -P), so assert the pattern with perl.
hits="$(perl -ne 'print "$ARGV:$.: $_" if /\$[A-Za-z_][A-Za-z_0-9]*[^\x00-\x7F]/' "$RELEASE" || true)"
if [ -n "$hits" ]; then
  echo "FAIL: unbraced \$var directly followed by a multibyte char (bash 3.2 + UTF-8 crash):" >&2
  printf '%s\n' "$hits" >&2
  exit 1
fi
echo "release-lock: no unbraced var before multibyte char in release.sh OK"

# L-109: an unbound variable under set -u must not exit 0. On bash 3.2 the EXIT trap sees $? as 0;
# release.sh treats a missing RELEASE_COMPLETED flag as failure and reports it.
unbound_out="$(mktemp)"
set +e
bash -c '
  set -euo pipefail
  # shellcheck disable=SC1090
  source "'"$RELEASE"'"
  print_state() { :; }
  record() { return 0; }
  RELEASE_COMPLETED=0
  REPORTED=0
  LOCK_HELD=0
  ENV_TMP=""
  STAGE=init
  STAGE_KIND=prepare
  ACTION=""
  TARGET_VERSION=""
  trap on_exit EXIT
  printf "%s\n" "$T111_UNBOUND_FOR_L109"
' >"$unbound_out" 2>&1
unbound_rc=$?
set -e
if [ "$unbound_rc" -eq 0 ]; then
  echo "FAIL: unbound variable exited 0 (L-109)" >&2
  cat "$unbound_out" >&2
  exit 1
fi
if ! grep -q '发布中止' "$unbound_out"; then
  echo "FAIL: unbound variable did not report failure (rc=$unbound_rc):" >&2
  cat "$unbound_out" >&2
  exit 1
fi
echo "release-lock: unbound variable exits non-zero and reports failure (rc=$unbound_rc) OK"
echo "release-lock: all tests passed"
rm -f "$out" "$out2"
