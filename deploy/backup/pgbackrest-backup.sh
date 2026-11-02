#!/usr/bin/env bash
# Scheduled pgBackRest backup TEMPLATE (ADR-037 decision 5): a full backup on Sundays, a differential
# backup on every other day, and a final "pgbackrest check" (proves that WAL archiving still reaches
# the repository). Exits non-zero on any failure. WAL is archived continuously by PostgreSQL itself
# (archive_command), this script only creates the base backups.
#
# Usage: pgbackrest-backup.sh [full|diff]   (default: full on Sunday, diff otherwise; UTC)
#
# Install in root's crontab on the production host (the host clock is the schedule), 02:00 UTC,
# before the 03:00 logical backup:
#   0 2 * * * /opt/tcrm/deploy/backup/pgbackrest-backup.sh >> /var/log/tcrm-pgbackrest.log 2>&1
#
# First deployment, once, after the postgres container is up and .env holds the PGBACKREST_* values:
#   docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -u postgres postgres \
#     pgbackrest --stanza=tcrm stanza-create
#   docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -u postgres postgres \
#     pgbackrest --stanza=tcrm check
#   deploy/backup/pgbackrest-backup.sh full
# Status at any time: ... exec -u postgres postgres pgbackrest --stanza=tcrm info
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Prints the backup type for a day of the week (1 = Monday ... 7 = Sunday).
backup_type() {
  if [ "$1" = "7" ]; then
    printf 'full\n'
  else
    printf 'diff\n'
  fi
}

main() {
  local type="${1:-$(backup_type "$(date -u +%u)")}"
  case "$type" in
    full | diff) ;;
    *)
      echo "usage: $0 [full|diff]" >&2
      exit 2
      ;;
  esac
  local compose=(docker compose -f "${TCRM_COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.prod.yml}" --env-file "${TCRM_ENV_FILE:-$DEPLOY_DIR/.env}")
  # exec runs as root by default; pgBackRest must run as the postgres OS user (peer access to the socket).
  local pgbackrest=("${compose[@]}" exec -T -u postgres postgres pgbackrest --stanza=tcrm)

  echo "pgbackrest $type backup starting: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  "${pgbackrest[@]}" --type="$type" backup
  "${pgbackrest[@]}" check
  echo "pgbackrest $type backup finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

# Run only when executed, so the function above can be sourced by tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
