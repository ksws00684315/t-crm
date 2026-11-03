#!/usr/bin/env bash
# Daily logical PostgreSQL backup TEMPLATE (ADR-037 decision 5, design document sections 9 and 10):
# pg_dump custom format, encrypted with gpg (symmetric AES256), 03:00 every day, keep 7 daily +
# 4 weekly copies locally, optional upload to AWS S3. Only the encrypted <name>.dump.gpg is kept on the
# host and in S3; the plaintext dump exists as <name>.dump.partial for a few seconds and is removed on
# every exit path. This is the second line of defence next to pgBackRest (pgbackrest-backup.sh).
#
# Needs on the host: docker compose, gpg, and (for the upload) the AWS CLI with credentials from its
# default chain (instance role or ~/.aws), never from this file.
#   TCRM_BACKUP_PASSPHRASE_FILE  file with the passphrase, mode 600, kept outside the repository (required)
#   TCRM_BACKUP_S3_BUCKET        upload target; empty = keep the encrypted dumps on the host only
#   TCRM_BACKUP_S3_PREFIX        key prefix (default "logical"); copies go to <prefix>/daily/ and <prefix>/weekly/
#   AWS_REGION                   bucket region
#
# Install on the production host, e.g. in root's crontab (the host clock is the schedule):
#   0 3 * * * /opt/tcrm/deploy/backup/pg-backup.sh >> /var/log/tcrm-backup.log 2>&1
# Restore from a logical backup (decrypt, then pg_restore as the administration superuser; the dump's
# objects belong to the application account; the plaintext stays in the pipe, never on disk):
#   gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$TCRM_BACKUP_PASSPHRASE_FILE" \
#     --decrypt tcrm-<stamp>.dump.gpg \
#   | docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T postgres \
#     sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists'
# From S3 fetch the file first: aws s3 cp s3://<bucket>/<prefix>/daily/tcrm-<stamp>.dump.gpg .
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Temporary files of the current run; global so that the EXIT trap can still see them after main returns.
PLAIN_FILE=""
CIPHER_FILE=""

backup_name() { # <UTC timestamp yyyymmddThhmmssZ>
  printf 'tcrm-%s.dump.gpg' "$1"
}


# UTC day helpers that work on GNU date (Linux) and BSD date (macOS) — L-075.
utc_ymd_epoch() { # <YYYYMMDD> -> epoch seconds at 00:00:00 UTC
  local ymd="$1"
  if date -u -d "$ymd" +%s 2>/dev/null; then
    return 0
  fi
  # BSD date keeps the current clock if the format has no time — pin midnight.
  date -u -j -f "%Y%m%d%H%M%S" "${ymd}000000" +%s
}

utc_ymd_dow() { # <YYYYMMDD> -> 1=Mon .. 7=Sun (GNU/BSD %u)
  local ymd="$1"
  if date -u -d "$ymd" +%u 2>/dev/null; then
    return 0
  fi
  date -u -j -f "%Y%m%d%H%M%S" "${ymd}000000" +%u
}

# Prints the files in $1 that retention no longer needs: every dump older than 7 days except the
# Sunday dumps, which are kept for 28 days. $2 is "today" as yyyymmdd (UTC), injectable for tests.
expired_backups() {
  local dir="$1" today="$2" file stamp day age_days dow
  for file in "$dir"/tcrm-*.dump.gpg; do
    [ -e "$file" ] || continue
    stamp="$(basename "$file" .dump.gpg)"
    stamp="${stamp#tcrm-}"
    day="${stamp%%T*}"
    age_days=$(( ( $(utc_ymd_epoch "$today") - $(utc_ymd_epoch "$day") ) / 86400 ))
    dow="$(utc_ymd_dow "$day")" # 7 = Sunday
    if [ "$age_days" -gt 28 ] || { [ "$age_days" -gt 7 ] && [ "$dow" != "7" ]; }; then
      printf '%s\n' "$file"
    fi
  done
}

# Prints the octal permission bits of a file (GNU stat first, BSD stat as fallback).
file_mode() {
  stat -c '%a' "$1" 2> /dev/null || stat -f '%Lp' "$1"
}

main() {
  local env_file="${TCRM_ENV_FILE:-$DEPLOY_DIR/.env}"
  if [ -f "$env_file" ]; then
    set -a
    # shellcheck source=/dev/null
    . "$env_file"
    set +a
  fi
  local backup_dir="${TCRM_BACKUP_DIR:-$DEPLOY_DIR/backups}"
  local passphrase_file="${TCRM_BACKUP_PASSPHRASE_FILE:?TCRM_BACKUP_PASSPHRASE_FILE is required (the dump is always encrypted)}"
  local compose=(docker compose -f "${TCRM_COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.prod.yml}" --env-file "$env_file")
  local stamp file
  if [ ! -s "$passphrase_file" ] || [ "$(file_mode "$passphrase_file")" != "600" ]; then
    echo "$passphrase_file must exist, be non-empty and have mode 600" >&2
    exit 1
  fi
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$backup_dir"
  umask 077
  file="$backup_dir/$(backup_name "$stamp")"
  PLAIN_FILE="${file%.gpg}.partial"
  CIPHER_FILE="$file.partial"

  # The plaintext dump must never outlive this script, whatever the exit path (set -e failure, signal).
  trap 'rm -f -- "$PLAIN_FILE" "$CIPHER_FILE"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # Leftovers of a run that was killed without running the trap (SIGKILL, power loss).
  rm -f -- "$backup_dir"/tcrm-*.dump.partial "$backup_dir"/tcrm-*.dump.gpg.partial

  # Dump inside the container (credentials come from its environment, nothing on the command line).
  # shellcheck disable=SC2016 # $POSTGRES_* must expand inside the container
  "${compose[@]}" exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$PLAIN_FILE"
  # A dump that cannot be listed is not a backup.
  "${compose[@]}" exec -T postgres pg_restore --list < "$PLAIN_FILE" > /dev/null
  gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$passphrase_file" \
    --symmetric --cipher-algo AES256 --output "$CIPHER_FILE" "$PLAIN_FILE"
  rm -f -- "$PLAIN_FILE"
  mv "$CIPHER_FILE" "$file"
  echo "backup written: $file ($(wc -c < "$file") bytes)"

  if [ -n "${TCRM_BACKUP_S3_BUCKET:-}" ]; then
    # Daily copies go under daily/, Sunday copies also under weekly/. Configure S3 lifecycle rules to
    # expire <prefix>/daily/ after 7 days and <prefix>/weekly/ after 28 days (the backup identity may
    # not change lifecycle rules, see iam-policy.example.json). Credentials: AWS CLI default chain.
    local region="${AWS_REGION:?AWS_REGION is required for the S3 upload}"
    local target="s3://$TCRM_BACKUP_S3_BUCKET/${TCRM_BACKUP_S3_PREFIX:-logical}"
    aws s3 cp --only-show-errors --region "$region" "$file" "$target/daily/$(basename "$file")"
    if [ "$(date -u +%u)" = "7" ]; then
      aws s3 cp --only-show-errors --region "$region" "$file" "$target/weekly/$(basename "$file")"
    fi
  fi

  expired_backups "$backup_dir" "$(date -u +%Y%m%d)" | while IFS= read -r old; do
    rm -f -- "$old"
    echo "expired backup removed: $old"
  done
}

# Run only when executed, so the functions above can be sourced by tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
