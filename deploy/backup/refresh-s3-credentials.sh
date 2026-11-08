#!/usr/bin/env bash
# Refresh the pgBackRest S3 credentials fragment on the host (task 106, L-107, ADR-037 decision 1).
# The EC2 host uses the instance role; with IMDSv2 and hop limit 1 the postgres container cannot read
# the instance metadata, so THIS script runs on the host instead: it fetches temporary credentials
# through the AWS CLI default chain (aws configure export-credentials) and writes them as a
# pgBackRest configuration fragment. The whole directory is mounted read-only into the postgres
# container at /etc/pgbackrest/conf.d (pgBackRest's default config-include-path); every archive-push,
# backup and restore command re-reads it, so a rotation is picked up without restarting postgres.
# Install in root's crontab on the production host:
#   */15 * * * * /opt/tcrm/deploy/backup/refresh-s3-credentials.sh >> /var/log/tcrm-pgbackrest-creds.log 2>&1
#
# Like the other host scripts (L-112), the script loads deploy/.env (path derived from its own
# location; TCRM_ENV_FILE overrides the file). Values the CALLER already set — the old inline
# `TCRM_PGBACKREST_CREDS_DIR=... refresh-s3-credentials.sh` form, in cron or by hand — win over
# the env file, so existing crontabs keep working unchanged. The env file is sourced WITHOUT
# `set -a`: nothing else from it (secrets in particular) is exported to the aws subprocess. A
# missing env file is not an error: the caller's environment alone decides, as before.
#
# First enablement, before postgres starts: create the directory (OUTSIDE this repository), run this
# script once, then start the stack (deploy/README.md, "Backup and restore").
#
# Failure behaviour: the previous fragment is kept as is, the exit code is non-zero, and no
# credential value ever appears in the output or in an error message. Debug tracing (set -x) is
# deliberately not used: it would print the credentials.
#   TCRM_PGBACKREST_CREDS_DIR  target directory (required), e.g. /var/lib/tcrm/pgbackrest-creds;
#                              must live OUTSIDE this repository; it is created when missing;
#                              read from deploy/.env unless the caller sets it (caller wins, L-112)
#   TCRM_ENV_FILE              alternative env file (default: deploy/.env next to the script)
# Requirements on the host: AWS CLI v2 with access to the instance role (default credential chain).
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# export statements re-applying the caller's values of the given names, after the env file was
# sourced (same approach as deploy/scripts/ops-check.sh; bash 3.2 compatible: no nameref).
preserve_caller_env() {
  local name
  for name in "$@"; do
    if [ -n "${!name+x}" ]; then
      printf 'export %s=%q\n' "$name" "${!name}"
    fi
  done
}

# L-112: load deploy/.env like the other host scripts so cron can call this script directly.
# Caller-set values win (preserve_caller_env). The env file is sourced WITHOUT `set -a`: only
# TCRM_PGBACKREST_CREDS_DIR is consumed below, and nothing else from the file (secrets in
# particular) becomes an environment variable for the aws subprocess. A missing env file is not
# an error: the caller's environment alone decides (the behaviour before L-112).
load_env_config() {
  local env_file="${TCRM_ENV_FILE:-$DEPLOY_DIR/.env}"
  [ -f "$env_file" ] || return 0
  local saved
  saved="$(preserve_caller_env TCRM_PGBACKREST_CREDS_DIR)"
  # shellcheck source=/dev/null
  . "$env_file"
  [ -z "$saved" ] || eval "$saved"
}

# Name of the fragment inside TCRM_PGBACKREST_CREDS_DIR (must end in .conf: pgBackRest only reads
# *.conf from config-include-path). deploy/scripts/ops-check.sh watches this same file.
CREDS_FILE_NAME="s3-credentials.conf"
# The postgres OS user inside the crm-postgres image (postgres:17.11; confirmed with `id -u postgres`
# in the image). The fragment must be readable by pgBackRest, which runs as that user in the container.
POSTGRES_UID=999
# Refuse to write credentials that expire within 30 minutes: the cron interval is 15 minutes, so the
# file would almost immediately go stale.
MIN_VALIDITY_SECONDS=1800

TMP_FILE=""

# Seconds since the epoch for the Expiration value of `aws configure export-credentials`
# (ISO 8601, e.g. "2026-11-03T22:15:33Z" or "...+00:00"). GNU date parses either form directly
# (Linux production host); the BSD fallback (macOS verification) peels the zone suffix off with an
# ANCHORED pattern — never "up to the first +/-", which would truncate inside the date — parses the
# wall clock as UTC and applies the offset by hand. Empty output = unparseable.
expiration_epoch() { # <ISO8601> -> seconds, empty on failure
  local value="$1" out sign="" hh=0 mm=0
  # An empty value is a FAILURE, never "today": GNU date parses an empty string as midnight
  # (CI run 41664066100), which would fabricate a plausible expiry and let stale values through.
  if [ -z "$value" ]; then
    return 1
  fi
  out=$(date -u -d "$value" +%s 2>/dev/null) && [ -n "$out" ] && printf '%s' "$out" && return 0
  if [[ "$value" =~ ([+-])([0-9][0-9]):?([0-9][0-9])$ ]]; then
    sign=${BASH_REMATCH[1]}
    hh=${BASH_REMATCH[2]}
    mm=${BASH_REMATCH[3]}
    value=${value%"${BASH_REMATCH[0]}"}
  elif [[ "$value" == *Z ]]; then
    value=${value%Z}
  fi
  out=$(date -u -j -f '%Y-%m-%dT%H:%M:%S' "$value" +%s 2>/dev/null) || return 1
  case "$sign" in
    +) out=$((out - hh * 3600 - mm * 60)) ;;
    -) out=$((out + hh * 3600 + mm * 60)) ;;
  esac
  printf '%s' "$out"
}

# Prints one JSON string value without needing jq on the host (aws CLI v2 output only: no escaped
# quotes inside these values — they are base64-ish tokens and ISO timestamps).
json_value() { # <json> <key>
  printf '%s' "$1" | sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

# Prints the fragment for the given credentials; fails when they are not temporary (no Expiration)
# or expire within MIN_VALIDITY_SECONDS. Long-lived access keys must never reach the file.
fragment() { # <json>
  local json="$1" expiration remaining key secret token epoch_now
  key=$(json_value "$json" AccessKeyId)
  secret=$(json_value "$json" SecretAccessKey)
  token=$(json_value "$json" SessionToken)
  expiration=$(json_value "$json" Expiration)
  if [ -z "$key" ] || [ -z "$secret" ] || [ -z "$token" ]; then
    echo "refresh-s3-credentials: could not read the temporary credentials from aws output" >&2
    return 1
  fi
  if [ -z "$expiration" ]; then
    echo "refresh-s3-credentials: credentials have no expiration time (not from the instance role); refusing to write them" >&2
    return 1
  fi
  epoch_now=$(date -u +%s)
  remaining=$(expiration_epoch "$expiration")
  if [ -z "$remaining" ]; then
    echo "refresh-s3-credentials: could not parse the expiration time" >&2
    return 1
  fi
  remaining=$((remaining - epoch_now))
  if [ "$remaining" -lt "$MIN_VALIDITY_SECONDS" ]; then
    echo "refresh-s3-credentials: credentials expire in ${remaining}s (< ${MIN_VALIDITY_SECONDS}s); not writing the file" >&2
    return 1
  fi
  printf '[global]\nrepo1-s3-key-type=shared\nrepo1-s3-key=%s\nrepo1-s3-key-secret=%s\nrepo1-s3-token=%s\n' \
    "$key" "$secret" "$token"
}

main() {
  local creds_dir="${TCRM_PGBACKREST_CREDS_DIR:-}"
  if [ -z "$creds_dir" ]; then
    echo "TCRM_PGBACKREST_CREDS_DIR is required (deploy/.env; keep it OUTSIDE this repository)" >&2
    exit 1
  fi
  mkdir -p "$creds_dir"
  local resolved repo_root
  resolved="$(cd "$creds_dir" && pwd -P)"
  repo_root="$(cd "$DEPLOY_DIR/.." && pwd -P)"
  case "$resolved" in
    "$repo_root" | "$repo_root"/*)
      echo "TCRM_PGBACKREST_CREDS_DIR must be outside this repository: $resolved" >&2
      exit 1
      ;;
  esac
  # The directory must be traversable by the postgres user inside the container (uid 999), or
  # pgBackRest cannot open the fragment and every backup fails; the file itself carries the
  # secrecy (600, owned by uid 999). Enforce the documented 755 so a too-strict directory
  # (e.g. 700 from an earlier setup) heals itself instead of failing silently at archive time.
  chmod 755 "$resolved"

  # Temporary files of the current run; global so that the EXIT trap can still see them.
  TMP_FILE="$resolved/$CREDS_FILE_NAME.tmp"
  trap 'rm -f -- "$TMP_FILE"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  # aws configure export-credentials resolves the default credential chain (the instance role on
  # EC2) and prints the credentials as JSON; the aws CLI is invoked via PATH so tests can replace it.
  local json fragment_text
  if ! json="$(aws configure export-credentials --format process)"; then
    echo "refresh-s3-credentials: aws configure export-credentials failed" >&2
    exit 1
  fi
  if ! fragment_text="$(fragment "$json")"; then
    exit 1 # the fragment() call already printed the reason (without any credential content)
  fi

  # Atomic replacement: write the fragment next to the target, set owner and permissions, then mv
  # (rename within the same directory). A concurrent reader in the container always sees either the
  # complete old file or the complete new one (L-107 verification 3).
  umask 077
  printf '%s' "$fragment_text" > "$TMP_FILE"
  chown "$POSTGRES_UID" "$TMP_FILE"
  chmod 600 "$TMP_FILE"
  mv -f "$TMP_FILE" "$resolved/$CREDS_FILE_NAME"
  TMP_FILE=""
  echo "refresh-s3-credentials: credentials written to $resolved/$CREDS_FILE_NAME"
}

# Run only when executed, so the functions above can be sourced by tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  load_env_config
  main "$@"
fi
