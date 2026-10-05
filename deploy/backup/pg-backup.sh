#!/usr/bin/env bash
# Daily PostgreSQL backup TEMPLATE (design document sections 9 and 10: pg_dump custom format,
# 03:00 every day, keep 7 daily + 4 weekly copies, optional upload to Cloudflare R2).
#
# Install on the production host, e.g. in root's crontab (the host clock is the schedule):
#   0 3 * * * /opt/tcrm/deploy/backup/pg-backup.sh >> /var/log/tcrm-backup.log 2>&1
# Restore drill (do it once before go-live):
#   docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T postgres \
#     sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < tcrm-<stamp>.dump
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

backup_name() { # <UTC timestamp yyyymmddThhmmssZ>
  printf 'tcrm-%s.dump' "$1"
}

# Prints the files in $1 that retention no longer needs: every dump older than 7 days except the
# Sunday dumps, which are kept for 28 days. $2 is "today" as yyyymmdd (UTC), injectable for tests.
expired_backups() {
  local dir="$1" today="$2" file stamp day age_days dow
  for file in "$dir"/tcrm-*.dump; do
    [ -e "$file" ] || continue
    stamp="$(basename "$file" .dump)"
    stamp="${stamp#tcrm-}"
    day="${stamp%%T*}"
    age_days=$(( ( $(date -u -d "$today" +%s) - $(date -u -d "$day" +%s) ) / 86400 ))
    dow="$(date -u -d "$day" +%u)" # 7 = Sunday
    if [ "$age_days" -gt 28 ] || { [ "$age_days" -gt 7 ] && [ "$dow" != "7" ]; }; then
      printf '%s\n' "$file"
    fi
  done
}

main() {
  # shellcheck disable=SC1091
  set -a; [ -f "$DEPLOY_DIR/.env" ] && . "$DEPLOY_DIR/.env"; set +a
  local backup_dir="${TCRM_BACKUP_DIR:-$DEPLOY_DIR/backups}"
  local compose=(docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" --env-file "$DEPLOY_DIR/.env")
  local stamp file
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$backup_dir"
  umask 077
  file="$backup_dir/$(backup_name "$stamp")"

  # Dump inside the container (credentials come from its environment, nothing on the command line).
  "${compose[@]}" exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$file.partial"
  # A dump that cannot be listed is not a backup.
  "${compose[@]}" exec -T postgres pg_restore --list < "$file.partial" > /dev/null
  mv "$file.partial" "$file"
  echo "backup written: $file ($(wc -c < "$file") bytes)"

  if [ -n "${TCRM_R2_BUCKET:-}" ]; then
    # Daily copies go under daily/, Sunday copies also under weekly/. Configure R2 lifecycle rules to
    # expire daily/ after 7 days and weekly/ after 28 days.
    export AWS_ACCESS_KEY_ID="${TCRM_R2_ACCESS_KEY_ID:?TCRM_R2_ACCESS_KEY_ID is required for the R2 upload}"
    export AWS_SECRET_ACCESS_KEY="${TCRM_R2_SECRET_ACCESS_KEY:?TCRM_R2_SECRET_ACCESS_KEY is required for the R2 upload}"
    export AWS_DEFAULT_REGION=auto
    local endpoint="${TCRM_R2_ENDPOINT:?TCRM_R2_ENDPOINT is required for the R2 upload}"
    aws s3 cp --endpoint-url "$endpoint" "$file" "s3://$TCRM_R2_BUCKET/daily/$(basename "$file")"
    if [ "$(date -u +%u)" = "7" ]; then
      aws s3 cp --endpoint-url "$endpoint" "$file" "s3://$TCRM_R2_BUCKET/weekly/$(basename "$file")"
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
