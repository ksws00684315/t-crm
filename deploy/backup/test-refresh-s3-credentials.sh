#!/usr/bin/env bash
# Unit tests for the judgment logic of refresh-s3-credentials.sh (task 106, L-107). Sources the
# script for its functions; nothing here touches Docker, real AWS or files outside a temp directory.
# The success path of main() is NOT exercised here (it needs root for chown); it is verified
# end-to-end in a Linux container (execution report, verification item 4). main() is called inside
# subshells because it exits on failure. Run:
#   bash deploy/backup/test-refresh-s3-credentials.sh
set -euo pipefail
# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/refresh-s3-credentials.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { # <expected> <actual> <what>
  [ "$2" = "$1" ] || fail "$3: expected [$1], got [$2]"
}
sha_of() { cksum "$1" | cut -d' ' -f1-2; }

# UTC ISO timestamp helper that works on GNU date (Linux/CI) and BSD date (macOS).
fmt_exp() { # <epoch>
  date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ
}
NOW=$(date -u +%s)

# --- expiration_epoch ------------------------------------------------------
# L-107 review finding: the BSD fallback must strip the zone suffix with an anchored pattern; the
# pre-fix "${value%%[+-]*}" truncated inside the date ("2026-11-03T..." -> "2026") and failed on
# macOS for every valid value. The forms below must all denote the SAME instant on BOTH platforms.
TS="2026-11-03T22:15:33"
EXPECT_EPOCH="$(date -u -d "$TS+00:00" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%S' "$TS" +%s)"
expect "$EXPECT_EPOCH" "$(expiration_epoch "${TS}Z")" "epoch of Z form"
expect "$EXPECT_EPOCH" "$(expiration_epoch "$TS+00:00")" "epoch of +00:00 form"
expect "$EXPECT_EPOCH" "$(expiration_epoch "$TS+0000")" "epoch of +0000 form"
expect "$(( EXPECT_EPOCH - 3600 ))" "$(expiration_epoch "$TS+01:00")" "positive offset applied"
expect "$(( EXPECT_EPOCH + 3600 ))" "$(expiration_epoch "$TS-01:00")" "negative offset applied"
[ -z "$(expiration_epoch "not-a-date")" ] || fail "garbage input must yield empty output"
[ -z "$(expiration_epoch "")" ] || fail "empty input must yield empty output"

# --- fragment ---------------------------------------------------------------

creds_json() { # <epoch_or_empty>
  local exp="$1" extra=""
  [ -n "$exp" ] && extra=",\"Expiration\":\"$(fmt_exp "$exp")\""
  printf '{"Version":1,"AccessKeyId":"T106AKIAIOSFODNN7EXAMPLE","SecretAccessKey":"T106wJalrXUtnFEMIKEY","SessionToken":"T106TOKEN-rot1"%s}' "$extra"
}

# Valid temporary credentials: exactly the five required lines, in order.
OUT="$(fragment "$(creds_json "$((NOW + 2 * 3600))")")"
expect '[global]
repo1-s3-key-type=shared
repo1-s3-key=T106AKIAIOSFODNN7EXAMPLE
repo1-s3-key-secret=T106wJalrXUtnFEMIKEY
repo1-s3-token=T106TOKEN-rot1' "$OUT" "fragment content"

# No Expiration (long-lived keys): refuse, error without any credential value.
if OUT="$(fragment "$(creds_json "")" 2> "$WORK/err.txt")"; then
  fail "credentials without Expiration must be refused"
fi
grep -q "no expiration time" "$WORK/err.txt" || fail "no-Expiration error text wrong: $(cat "$WORK/err.txt")"

# Too short validity (< 30 minutes): refuse.
if OUT="$(fragment "$(creds_json "$((NOW + 15 * 60))")" 2> "$WORK/err.txt")"; then
  fail "credentials expiring within 30 minutes must be refused"
fi
grep -q "expire in" "$WORK/err.txt" || fail "short-expiry error text wrong: $(cat "$WORK/err.txt")"

# No credential value may ever reach stderr.
if grep -q "T106AKIA\|T106wJalr\|T106TOKEN" "$WORK/err.txt"; then
  fail "credential value leaked into stderr"
fi

# --- main() failure paths (no root needed: they exit before any chown) ------

CREDS_DIR="$WORK/creds"
mkdir -p "$CREDS_DIR"
printf '[global]\nold\n' > "$CREDS_DIR/$CREDS_FILE_NAME"
OLD_SHA=$(sha_of "$CREDS_DIR/$CREDS_FILE_NAME")

# aws itself fails: non-zero exit, old file kept, message without credentials.
STUB="$WORK/bin"
mkdir -p "$STUB"
printf '#!/bin/sh\necho aws: simulated failure >&2\nexit 255\n' > "$STUB/aws"
chmod 755 "$STUB/aws"
if ( PATH="$STUB:$PATH" TCRM_PGBACKREST_CREDS_DIR="$CREDS_DIR" main ) 2> "$WORK/err.txt"; then
  fail "main must exit non-zero when aws fails"
fi
expect "$OLD_SHA" "$(sha_of "$CREDS_DIR/$CREDS_FILE_NAME")" "old file kept when aws fails"
if grep -q "T106AKIA\|T106wJalr\|T106TOKEN" "$WORK/err.txt"; then
  fail "credential leak on aws failure"
fi
[ ! -e "$CREDS_DIR/$CREDS_FILE_NAME.tmp" ] || fail "temp file left behind on failure"

# Directory mode is enforced to 755: uid 999 (postgres inside the container) must be able to
# traverse it, or every backup fails; a 700 directory from an earlier setup must heal (review of
# task 106). main() chmods before it calls aws, so the failing run above already did it — make the
# start state explicitly wrong first to prove the enforcement.
chmod 700 "$CREDS_DIR"
( PATH="$STUB:$PATH" TCRM_PGBACKREST_CREDS_DIR="$CREDS_DIR" main ) 2> /dev/null || true
expect 755 "$(stat -c '%a' "$CREDS_DIR" 2>/dev/null || stat -f '%Lp' "$CREDS_DIR")" "credentials dir mode enforced to 755"

# Missing TCRM_PGBACKREST_CREDS_DIR: refuse.
if ( PATH="$STUB:$PATH" TCRM_PGBACKREST_CREDS_DIR="" main ) 2> "$WORK/err.txt"; then
  fail "main must refuse without TCRM_PGBACKREST_CREDS_DIR"
fi
grep -q "TCRM_PGBACKREST_CREDS_DIR is required" "$WORK/err.txt" ||
  fail "missing-dir error text wrong: $(cat "$WORK/err.txt")"

# Directory inside the repository: refuse (simulated with a fake repo root; DEPLOY_DIR mirrors the
# real layout <repo>/deploy, because main() derives the repo root as DEPLOY_DIR/..). DEPLOY_DIR is
# read by main() from the sourced script.
repo_root="$WORK/repo"
mkdir -p "$repo_root/deploy" "$repo_root/creds"
# shellcheck disable=SC2034
DEPLOY_DIR="$repo_root/deploy"
if ( TCRM_PGBACKREST_CREDS_DIR="$repo_root/creds" PATH="$STUB:$PATH" main ) 2> "$WORK/err.txt"; then
  fail "main must refuse a directory inside the repository"
fi
grep -q "outside this repository" "$WORK/err.txt" ||
  fail "in-repo error text wrong: $(cat "$WORK/err.txt")"

# --- load_env_config (L-112: deploy/.env loading) ----------------------------
# main() only runs after load_env_config when the script is EXECUTED; the tests call
# load_env_config directly. TCRM_ENV_FILE always points into the temp dir, so the real
# deploy/.env of this checkout can never leak into the results.
ENV_ONLY="$WORK/env-only"
mkdir -p "$ENV_ONLY"
ENV_FILE_A="$WORK/t106.env"
printf 'TCRM_PGBACKREST_CREDS_DIR=%s\nTCRM_SECRET_KEY=t106-fake-secret\n' "$ENV_ONLY" > "$ENV_FILE_A"

# 1) env file only: TCRM_PGBACKREST_CREDS_DIR is picked up from the env file.
( TCRM_ENV_FILE="$ENV_FILE_A" load_env_config
  expect "$ENV_ONLY" "${TCRM_PGBACKREST_CREDS_DIR:-}" "creds dir from env file"
) || exit 1

# 2) env file AND caller env: the caller's value wins (the pre-L-112 inline crontab form).
# The caller values are separate statements on purpose: since bash 5.3, `VAR=x func` rolls VAR
# back when the function returns, which is not how a caller's environment reaches this script.
( TCRM_ENV_FILE="$ENV_FILE_A"
  TCRM_PGBACKREST_CREDS_DIR="$CREDS_DIR"
  load_env_config
  expect "$CREDS_DIR" "${TCRM_PGBACKREST_CREDS_DIR:-}" "caller value must win over env file"
) || exit 1

# 3) no env file at all: caller env only, no error (the behaviour before L-112).
( TCRM_ENV_FILE="$WORK/does-not-exist.env" load_env_config
  if [ -n "${TCRM_PGBACKREST_CREDS_DIR:-}" ]; then
    fail "without an env file only the caller environment decides"
  fi
) || exit 1

# 4) TCRM_ENV_FILE pointing at another file: that file is read, not deploy/.env.
ENV_FILE_B="$WORK/t106-other.env"
printf 'TCRM_PGBACKREST_CREDS_DIR=%s\n' "$CREDS_DIR" > "$ENV_FILE_B"
( TCRM_ENV_FILE="$ENV_FILE_B" load_env_config
  expect "$CREDS_DIR" "${TCRM_PGBACKREST_CREDS_DIR:-}" "TCRM_ENV_FILE override"
) || exit 1

# 5) nothing else from the env file (the fake TCRM_SECRET_KEY here) may reach the environment
# of child processes (`aws` and anything else): the env file is sourced without `set -a`.
( TCRM_ENV_FILE="$ENV_FILE_A" load_env_config
  if env | grep -q "t106-fake-secret"; then
    fail "value from the env file leaked into the environment"
  fi
) || exit 1

echo "refresh-s3-credentials: all tests passed"
