#!/usr/bin/env bash
# Host-side monitoring for the production stack (ADR-037 decision 8, design document section 10
# "availability" and "observability"). Install in the host cron, every 5 minutes:
#   */5 * * * * /opt/tcrm/deploy/scripts/ops-check.sh >> /var/log/tcrm-ops-check.log 2>&1
#
# Checked every run (thresholds overridable, see below): disk usage (>= 70% warn, >= 85% critical),
# available memory (< 10% of total), the five Compose services (not running, unhealthy, or restarted
# within the last 5 minutes; a fresh data volume may make postgres exit once and restart — that is
# self-healing and shows up as one "restarted" alert plus a recovery, not a permanent failure,
# legacy L-061), the public certificate ($TCRM_CERT_DIR/public.pem, <= 20 days left), the newest
# pgBackRest backup (older than 26 hours) and WAL archiving stall (pending .ready segments with no
# archive progress for 15 minutes — idle databases with no pending WAL do not alert), the newest
# logical backup (.dump.gpg older than 26 hours), ERROR lines in the
# crm-admin / crm-redirect logs since the previous run, and the capacity WARNs of the backend
# (Redis memory, click stream backlog — logger ClickStreamMonitor).
#
# Privacy: log CONTENT never leaves the host. Alerts carry the level, logger names, counts and
# traceIds only, never message bodies (personal data must not reach third parties).
#
#   TCRM_ENV_FILE / TCRM_COMPOSE_FILE   locate the stack (defaults: deploy/.env, prod compose file)
#   TCRM_ALERT_WEBHOOK_URL              Feishu custom bot webhook; empty = alerts to stdout only
#   TCRM_HEARTBEAT_URL                  pinged once per run (Healthchecks.io style); empty = skip;
#                                       the external service alerts when the pings stop
#   TCRM_OPS_STATE_DIR                  alert state (dedup + recovery), default deploy/ops-state
#   TCRM_CERT_DIR / TCRM_BACKUP_DIR     same values as in deploy/.env (certificate / backup dir)
#   TCRM_OPS_SERVICES                   services to watch (default "nginx crm-admin crm-redirect
#                                       postgres redis")
# Threshold overrides (mainly for testing; defaults are the production values):
#   TCRM_OPS_DISK_WARN_PERCENT=70       TCRM_OPS_DISK_CRIT_PERCENT=85
#   TCRM_OPS_MEM_MIN_AVAIL_PERCENT=10   TCRM_OPS_CERT_MIN_DAYS=20
#   TCRM_OPS_BACKUP_MAX_AGE_HOURS=26    TCRM_OPS_WAL_MAX_AGE_MINUTES=15
#   TCRM_OPS_RESTART_WINDOW_MINUTES=5   TCRM_OPS_ALERT_REPEAT_HOURS=1
#
# The script always exits 0 (alerting is the channel, cron must not mail); a run that dies anyway
# is detected by the heartbeat service. The judgment logic lives in the functions below so tests
# can source this file (deploy/scripts/test-ops-check.sh).
set -uo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CURL_TIMEOUT=10
# Keys of every possible alert, in report order; recovery notices key off this list.
ALL_KEYS="disk memory container cert pgbackrest backup applog capacity"

# ---------------------------------------------------------------- helpers

now_epoch() { date +%s; }

# Seconds since the epoch for the date strings this script meets: openssl's notAfter
# ("Nov 30 12:00:00 2026 GMT") and Docker's StartedAt ("2026-11-02T10:00:00Z", fractions removed).
# GNU date first (Linux production host), BSD formats as the fallback (macOS verification).
parse_epoch() {
  local value="$1" out=""
  out=$(date -u -d "$value" +%s 2>/dev/null) && [ -n "$out" ] && printf '%s' "$out" && return 0
  out=$(date -u -j -f '%b %d %H:%M:%S %Y %Z' "$value" +%s 2>/dev/null) &&
    [ -n "$out" ] && printf '%s' "$out" && return 0
  out=$(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$value" +%s 2>/dev/null) &&
    [ -n "$out" ] && printf '%s' "$out" && return 0
  out=$(date -u -j -f '%Y-%m-%dT%H:%M:%S' "$value" +%s 2>/dev/null) &&
    [ -n "$out" ] && printf '%s' "$out" && return 0
  return 1
}

# "2026-11-02T10:00:00.123456789Z" -> "2026-11-02T10:00:00Z" (fraction dropped, zone kept).
strip_fraction() {
  local value="$1"
  if [[ "$value" =~ ^([^.]*)\.[0-9]+(Z|[+-][0-9]{2}:?[0-9]{2})?$ ]]; then
    printf '%s%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
  else
    printf '%s' "$value"
  fi
}

json_escape() { # <text> escaped for use inside a JSON string literal
  local value="$1"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  value=${value//$'\r'/\\r}
  value=${value//$'\t'/\\t}
  printf '%s' "$value"
}

# --------------------------------------------------- pure judgment functions

# Disk: warn at the warn threshold or above, critical at the critical threshold or above.
disk_level() { # <use_percent> <warn_percent> <crit_percent> -> ok|warn|crit
  if [ "$1" -ge "$3" ]; then printf 'crit'
  elif [ "$1" -ge "$2" ]; then printf 'warn'
  else printf 'ok'
  fi
}

# Memory: alert when available percent is STRICTLY below the minimum.
mem_level() { # <available_percent> <min_percent> -> ok|alert
  if [ "$1" -lt "$2" ]; then printf 'alert'; else printf 'ok'; fi
}

# Certificate: alert when the remaining days are at or below the minimum.
cert_level() { # <days_left> <min_days> -> ok|alert
  if [ "$1" -le "$2" ]; then printf 'alert'; else printf 'ok'; fi
}

# Freshness: alert when the age strictly exceeds the maximum.
age_level() { # <age_minutes> <max_minutes> -> ok|alert
  if [ "$1" -gt "$2" ]; then printf 'alert'; else printf 'ok'; fi
}

# WAL stall: alert only when there is at least one pending .ready segment AND archiving has made
# no progress (never archived, or last_archived older than max_minutes). An idle database produces
# no WAL, so last_archived_time alone would false-alarm every quiet night.
# L-078: also require last_failed_time > last_archived_time so a first write after a long idle
# (ready>0, stale last_archived, no new failure) does not false-alarm. An EMPTY last_failed_epoch
# means the last_failed_time query itself failed and must not enjoy that exemption (failed==0 would
# mask a stalled archiver forever): it falls back to the pre-L-078 judgment, pending segments and
# last_archived age only.
wal_stall_level() { # <ready_count> <last_archived_epoch> <now_epoch> <max_minutes> [<last_failed_epoch>] -> ok|alert
  local ready="$1" last="$2" now="$3" max_min="$4" failed="${5-}"
  case "$ready" in ''|*[!0-9]*) printf 'ok'; return ;; esac
  [ "$ready" -gt 0 ] || { printf 'ok'; return; }
  if [ -z "$last" ] || [ "$last" = "0" ]; then
    printf 'alert'
    return
  fi
  if [ -z "$failed" ]; then
    age_level $(( (now - last) / 60 )) "$max_min" # 查询失败：回退到 L-078 之前的判断
    return
  fi
  case "$failed" in *[!0-9]*) failed=0 ;; esac
  if [ "$failed" -eq 0 ] || [ "$failed" -le "$last" ]; then
    printf 'ok'
    return
  fi
  age_level $(( (now - last) / 60 )) "$max_min"
}

# Signature for dedup: strip digits so 72%→73% stays the same signature, but warn→CRITICAL or an
# extra service name changes it and forces an immediate resend.
alert_signature() {
  # L-077: drop the traceIds: tail first so each new id does not force a resend every cycle.
  printf '%s' "$1" | sed 's/traceIds:.*//' | tr -d '0-9'
}

# Logger token of one Spring Boot console line, "unknown" when the shape does not match. Real
# shape (Spring Boot 4, correlation renders empty brackets on non-request threads):
#   date  LEVEL 1 --- [app] [thread] [traceId] logger : message
line_logger() {
  local logger
  logger=$(printf '%s\n' "$1" | sed -n '
    s/.* --- \[[^]]*\][[:space:]]*\[[^]]*\][[:space:]]*\[[^]]*\][[:space:]][[:space:]]*\([^[:space:]][^[:space:]]*\)[[:space:]][[:space:]]*:.*/\1/p')
  if [ -z "$logger" ]; then
    # Fallback for a line without the [app] [thread] group (older correlation position).
    logger=$(printf '%s\n' "$1" | sed -n \
      's/.* --- \[[^]]*\][[:space:]][[:space:]]*\([^[:space:]][^[:space:]]*\)[[:space:]][[:space:]]*:.*/\1/p')
  fi
  printf '%s' "${logger:-unknown}"
}

# traceId from the correlation brackets (the third [...] after "---"; single spaces between the
# groups in the real format), empty when absent.
line_trace_id() {
  printf '%s' "$1" | sed -n 's/^.* --- \[[^]]*\] \[[^]]*\] \[\([^]]*\)\].*/\1/p'
}

# Increments "name:count name:count" lists (bash 3.2 compatible: no associative arrays).
bump_count() { # <list> <item> -> updated list on stdout
  local list="$1" item="$2" entry name count out="" found=0
  for entry in $list; do
    name=${entry%:*}
    count=${entry##*:}
    if [ "$name" = "$item" ]; then
      count=$((count + 1))
      found=1
    fi
    out="$out $name:$count"
  done
  [ "$found" -eq 0 ] && out="$out $item:1"
  printf '%s' "${out# }"
}

add_unique() { # <space-separated list> <item> -> list with the item added once
  case " $1 " in
    *" $2 "*) printf '%s' "$1" ;;
    *) printf '%s %s' "$1" "$2" | sed 's/^ //' ;;
  esac
}

# Reads log lines on stdin, prints one summary line per kind found (tab separated):
#   applog<TAB>count<TAB>logger:count logger:count<TAB>trace1 trace2
#   capacity<TAB>click-backlog<TAB>count<TAB>logger
#   capacity<TAB>redis-memory<TAB>count<TAB>logger
# An app log line is a Spring Boot console line (date, level, [traceId], pid --- [thread] logger
# : message). Message bodies are dropped here — only these tokens ever leave the host.
classify_log_lines() {
  local line logger trace
  local err_count=0 err_loggers="" err_traces=""
  local backlog_count=0 backlog_logger="" memory_count=0 memory_logger=""
  while IFS= read -r line; do
    case "$line" in
      *[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T*' ERROR '*' --- '*)
        err_count=$((err_count + 1))
        logger=$(line_logger "$line")
        err_loggers=$(bump_count "$err_loggers" "$logger")
        trace=$(line_trace_id "$line")
        case "$trace" in '') ;; *) err_traces=$(add_unique "$err_traces" "$trace") ;; esac
        ;;
      *' WARN '*)
        case "$line" in
          *'Click stream backlog at'*)
            backlog_count=$((backlog_count + 1))
            backlog_logger=$(line_logger "$line")
            ;;
          *'Redis used_memory at'*)
            memory_count=$((memory_count + 1))
            memory_logger=$(line_logger "$line")
            ;;
        esac
        ;;
    esac
  done
  [ "$err_count" -gt 0 ] && printf 'applog\t%s\t%s\t%s\n' "$err_count" "$err_loggers" "$err_traces"
  [ "$backlog_count" -gt 0 ] &&
    printf 'capacity\tclick-backlog\t%s\t%s\n' "$backlog_count" "$backlog_logger"
  [ "$memory_count" -gt 0 ] &&
    printf 'capacity\tredis-memory\t%s\t%s\n' "$memory_count" "$memory_logger"
  return 0
}

# ------------------------------------------------------------- environment

load_config() {
  local env_file="${TCRM_ENV_FILE:-$DEPLOY_DIR/.env}"
  # Values already set by the caller win over the env file (the verification triggers per-run
  # overrides this way; in production nothing is preset and the env file decides).
  local saved
  saved=$(preserve_caller_env TCRM_ENV_FILE TCRM_COMPOSE_FILE TCRM_ALERT_WEBHOOK_URL \
    TCRM_HEARTBEAT_URL TCRM_OPS_STATE_DIR TCRM_CERT_DIR TCRM_BACKUP_DIR TCRM_OPS_SERVICES \
    TCRM_OPS_DISK_WARN_PERCENT TCRM_OPS_DISK_CRIT_PERCENT TCRM_OPS_MEM_MIN_AVAIL_PERCENT \
    TCRM_OPS_CERT_MIN_DAYS TCRM_OPS_BACKUP_MAX_AGE_HOURS TCRM_OPS_WAL_MAX_AGE_MINUTES \
    TCRM_OPS_RESTART_WINDOW_MINUTES TCRM_OPS_ALERT_REPEAT_HOURS)
  if [ -f "$env_file" ]; then
    set -a
    # shellcheck source=/dev/null
    . "$env_file"
    set +a
  fi
  eval "$saved"
  ENV_FILE="$env_file"
  COMPOSE_FILE="${TCRM_COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.prod.yml}"
  ALERT_WEBHOOK="${TCRM_ALERT_WEBHOOK_URL:-}"
  HEARTBEAT_URL="${TCRM_HEARTBEAT_URL:-}"
  STATE_DIR="${TCRM_OPS_STATE_DIR:-$DEPLOY_DIR/ops-state}"
  CERT_DIR="${TCRM_CERT_DIR:-./certs}"
  case "$CERT_DIR" in /*) ;; *) CERT_DIR="$DEPLOY_DIR/$CERT_DIR" ;; esac
  BACKUP_DIR="${TCRM_BACKUP_DIR:-$DEPLOY_DIR/backups}"
  SERVICES="${TCRM_OPS_SERVICES:-nginx crm-admin crm-redirect postgres redis}"
  DISK_WARN_PERCENT="${TCRM_OPS_DISK_WARN_PERCENT:-70}"
  DISK_CRIT_PERCENT="${TCRM_OPS_DISK_CRIT_PERCENT:-85}"
  MEM_MIN_AVAIL_PERCENT="${TCRM_OPS_MEM_MIN_AVAIL_PERCENT:-10}"
  CERT_MIN_DAYS="${TCRM_OPS_CERT_MIN_DAYS:-20}"
  BACKUP_MAX_AGE_HOURS="${TCRM_OPS_BACKUP_MAX_AGE_HOURS:-26}"
  WAL_MAX_AGE_MINUTES="${TCRM_OPS_WAL_MAX_AGE_MINUTES:-15}"
  RESTART_WINDOW_MINUTES="${TCRM_OPS_RESTART_WINDOW_MINUTES:-5}"
  ALERT_REPEAT_HOURS="${TCRM_OPS_ALERT_REPEAT_HOURS:-1}"
  ALERT_REPEAT_SECONDS=$((ALERT_REPEAT_HOURS * 3600))
  NOW=$(now_epoch)
}

compose_cmd() {
  COMPOSE=(docker compose -f "$COMPOSE_FILE")
  [ -f "$ENV_FILE" ] && COMPOSE+=(--env-file "$ENV_FILE")
  return 0
}

# export statements re-applying the caller's values of the given names, after the env file was
# sourced (bash 3.2 compatible: no nameref).
preserve_caller_env() {
  local name
  for name in "$@"; do
    if [ -n "${!name+x}" ]; then
      printf 'export %s=%q\n' "$name" "${!name}"
    fi
  done
}

# Modification time as seconds since the epoch (GNU stat first, BSD stat as the fallback).
file_mtime() {
  stat -c '%Y' "$1" 2>/dev/null || stat -f '%m' "$1"
}

# ------------------------------------------------------- alerting and state

dispatch() { # <message>: webhook (Feishu custom bot) or stdout when no webhook is configured
  # Returns 0 only when delivered (or printed to stdout). Feishu code≠0 is not a delivery (L-076).
  if [ -n "$ALERT_WEBHOOK" ]; then
    local payload response
    payload="$(printf '{"msg_type":"text","content":{"text":"%s"}}' "$(json_escape "$1")")"
    # Feishu returns HTTP 200 even when keyword/sign checks fail; inspect body "code":0.
    if ! response=$(curl -fsS -m "$CURL_TIMEOUT" -H 'Content-Type: application/json' \
      -d "$payload" "$ALERT_WEBHOOK" 2>/dev/null); then
      echo "ops-check: alert webhook send failed" >&2
      return 1
    fi
    if [ -n "$response" ] && [[ "$response" == *'"code":'* ]] && [[ "$response" != *'"code":0'* ]]; then
      echo "ops-check: alert webhook rejected: $response" >&2
      return 1
    fi
    return 0
  fi
  printf '%s\n' "$1"
  return 0
}

write_state() { # <file> <firing> <last_sent_epoch> <last_text> <signature>
  local tmp
  tmp="$1.tmp"
  {
    printf 'FIRING=%q\n' "$2"
    printf 'LAST=%q\n' "$3"
    printf 'TEXT=%q\n' "$4"
    printf 'SIG=%q\n' "$5"
  } > "$tmp" && mv -f "$tmp" "$1"
}

# One alert key per call. Firing=true sends on the first occurrence, when the signature of the
# alert text changes (upgrade / new fault in the same key), or at most once per hour while the
# signature stays the same; firing=false sends exactly one recovery notice.
notify() { # <key> <0|1> <text>
  local key="$1" firing="$2" text="$3"
  local state_file="$STATE_DIR/$key.state"
  local now_ FIRING=0 LAST=0 TEXT="" SIG="" sig
  if [ -f "$state_file" ]; then
    # shellcheck source=/dev/null
    . "$state_file"
  fi
  now_=$(now_epoch)
  sig=$(alert_signature "$text")
  if [ "$firing" = "1" ]; then
    if [ "$FIRING" != "1" ] || [ "$sig" != "${SIG:-}" ] || \
      [ $((now_ - LAST)) -ge "$ALERT_REPEAT_SECONDS" ]; then
      if dispatch "ALERT $key: $text"; then
        write_state "$state_file" 1 "$now_" "$text" "$sig"
      else
        # Not delivered: remember firing text/signature but keep LAST so the next run retries (L-076).
        write_state "$state_file" 1 "${LAST:-0}" "$text" "$sig"
      fi
    else
      # Still firing, same signature, sent recently: keep the send time, remember latest text.
      write_state "$state_file" 1 "$LAST" "$text" "$sig"
    fi
  elif [ "$FIRING" = "1" ]; then
    if dispatch "RECOVERED $key: ${TEXT:-was alerting} — OK now"; then
      write_state "$state_file" 0 "$now_" "" ""
    else
      write_state "$state_file" 1 "$LAST" "$TEXT" "$SIG"
    fi
  fi
}

send_heartbeat() {
  [ -n "$HEARTBEAT_URL" ] || return 0
  curl -fsS -m "$CURL_TIMEOUT" "$HEARTBEAT_URL" > /dev/null 2>&1 ||
    echo "ops-check: heartbeat send failed" >&2
}

# ------------------------------------------------------------------ checks

alert() { # <key> <text> — one alert record appended to the run's alert file, KEY<TAB>TEXT
  printf '%s\t%s\n' "$1" "$2" >> "$ALERTS_FILE"
}

# Filter df -kP output (with header) to one "mount<TAB>percent" line per real data mount.
# Drops pseudo FS, macOS runtime volumes, Ubuntu snap loop/squashfs mounts (/dev/loop*, /snap/...).
# Exposed for tests that feed a fixed sample (do not rely on the host's live df alone).
filter_df_usage() {
  awk '
    NR > 1 && $1 !~ /^(tmpfs|devtmpfs|udev|shm|none|overlay|devfs|map[- ])/ &&
    $1 !~ /^\/dev\/loop/ &&
    $6 !~ /^\/(System|private\/var\/vm|dev|Library\/Developer)/ &&
    $6 !~ /^\/snap(\/|$)/ {
      pct = $5; sub(/%/, "", pct); print $6 "\t" pct
    }'
}

# Local filesystems from df -kP. On Linux prefer excluding squashfs (Ubuntu snap); macOS has no
# -x and falls back to the same awk filters.
df_usage_lines() {
  local df_out
  if df_out=$(df -kP -x squashfs 2>/dev/null); then
    printf '%s\n' "$df_out" | filter_df_usage
  else
    df -kP 2>/dev/null | filter_df_usage
  fi
}

memory_available_percent() {
  if [ -r /proc/meminfo ]; then
    awk '/^MemTotal:/ { total = $2 } /^MemAvailable:/ { avail = $2 }
         END { if (total > 0) printf "%d", avail * 100 / total }' /proc/meminfo
  else
    # macOS fallback (best effort for local verification): free + inactive + speculative pages.
    local page total free inactive speculative
    page=$(pagesize)
    total=$(sysctl -n hw.memsize)
    free=$(vm_stat | awk '/Pages free/ { gsub(/\./, "", $3); print $3 }')
    inactive=$(vm_stat | awk '/Pages inactive/ { gsub(/\./, "", $3); print $3 }')
    speculative=$(vm_stat | awk '/Pages speculative/ { gsub(/\./, "", $3); print $3 }')
    printf '%s' $(( (free + inactive + speculative) * page * 100 / total ))
  fi
}

check_disk() {
  local mount pct level
  while IFS=$'\t' read -r mount pct; do
    [ -n "$mount" ] || continue
    level=$(disk_level "$pct" "$DISK_WARN_PERCENT" "$DISK_CRIT_PERCENT")
    case "$level" in
      warn) alert disk "disk usage ${pct}% on $mount (>= ${DISK_WARN_PERCENT}%)" ;;
      crit) alert disk "disk usage ${pct}% on $mount CRITICAL (>= ${DISK_CRIT_PERCENT}%)" ;;
    esac
  done < <(df_usage_lines)
}

check_memory() {
  local pct
  pct=$(memory_available_percent)
  if [ -z "$pct" ]; then
    alert memory "available memory: could not read memory info"
    return
  fi
  [ "$(mem_level "$pct" "$MEM_MIN_AVAIL_PERCENT")" = "alert" ] &&
    alert memory "available memory ${pct}% (< ${MEM_MIN_AVAIL_PERCENT}%)"
  return 0
}

check_containers() {
  local svc ids id info status rest health started started_epoch problems
  compose_cmd
  for svc in $SERVICES; do
    ids="$("${COMPOSE[@]}" ps --all -q "$svc" 2>/dev/null)"
    if [ -z "$ids" ]; then
      alert container "$svc: no container (missing or stopped)"
      continue
    fi
    problems=""
    for id in $ids; do
      info="$(docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.StartedAt}}' "$id" 2>/dev/null)"
      if [ -z "$info" ]; then
        problems="$problems inspect-failed"
        continue
      fi
      status=${info%%|*}
      rest=${info#*|}
      health=${rest%%|*}
      started=${rest#*|}
      [ "$status" != "running" ] && problems="$problems status=$status"
      [ "$health" = "unhealthy" ] && problems="$problems health=unhealthy"
      started_epoch=$(parse_epoch "$(strip_fraction "$started")") || started_epoch=""
      if [ -n "$started_epoch" ] &&
        [ $((NOW - started_epoch)) -lt $((RESTART_WINDOW_MINUTES * 60)) ]; then
        problems="$problems restarted-within-${RESTART_WINDOW_MINUTES}m"
      fi
    done
    [ -n "$problems" ] && alert container "$svc:$problems"
  done
  return 0
}

check_cert() {
  local cert="$CERT_DIR/public.pem" end epoch days
  if [ ! -f "$cert" ]; then
    alert cert "public certificate missing: $cert"
    return
  fi
  end="$(openssl x509 -noout -enddate -in "$cert" 2>/dev/null)"
  end=${end#notAfter=}
  epoch=$(parse_epoch "$end") || epoch=""
  if [ -z "$epoch" ]; then
    alert cert "public certificate: could not read the expiry date of $cert"
    return
  fi
  days=$(( (epoch - NOW) / 86400 ))
  [ "$(cert_level "$days" "$CERT_MIN_DAYS")" = "alert" ] &&
    alert cert "public certificate expires in $days days (<= $CERT_MIN_DAYS)"
  return 0
}

check_pgbackrest() {
  compose_cmd
  local info stop last age_min
  info="$("${COMPOSE[@]}" exec -T -u postgres postgres pgbackrest --stanza=tcrm info --output=json 2>/dev/null)"
  stop="$(printf '%s' "$info" | grep -o '"stop":[0-9]\{1,\}' | cut -d: -f2 | sort -n | tail -1)"
  if [ -z "$stop" ]; then
    alert pgbackrest "pgBackRest: no backup found (stanza missing, repository empty, or postgres unreachable)"
  else
    age_min=$(( (NOW - stop) / 60 ))
    [ "$(age_level "$age_min" $((BACKUP_MAX_AGE_HOURS * 60)))" = "alert" ] &&
      alert pgbackrest "latest pgBackRest backup is $((age_min / 60))h old (> $BACKUP_MAX_AGE_HOURS h)"
  fi
  # Stall = pending .ready segments AND no archive progress. Idle DB (ready=0) must not alert.
  local ready_count failed
  ready_count="$("${COMPOSE[@]}" exec -T postgres psql -U "${TCRM_DB_SUPERUSER_USERNAME:-postgres}" \
    -d "${TCRM_DB_NAME:-tcrm}" -Atc \
    "select count(*) from pg_ls_archive_statusdir() where name like '%.ready'" 2>/dev/null | tail -1)"
  last="$("${COMPOSE[@]}" exec -T postgres psql -U "${TCRM_DB_SUPERUSER_USERNAME:-postgres}" \
    -d "${TCRM_DB_NAME:-tcrm}" -Atc \
    "select coalesce(extract(epoch from last_archived_time)::bigint, 0) from pg_stat_archiver" 2>/dev/null | tail -1)"
  failed="$("${COMPOSE[@]}" exec -T postgres psql -U "${TCRM_DB_SUPERUSER_USERNAME:-postgres}" \
    -d "${TCRM_DB_NAME:-tcrm}" -Atc \
    "select coalesce(extract(epoch from last_failed_time)::bigint, 0) from pg_stat_archiver" 2>/dev/null | tail -1)"
  case "$ready_count" in ''|*[!0-9]*) ready_count=0 ;; esac
  # $failed 传原值：查询失败时输出为空，wal_stall_level 据此回退到 L-078 之前的判断，
  # 不能用 ${failed:-0} 把「查询失败」吞成「没有失败」。
  if [ "$(wal_stall_level "$ready_count" "${last:-0}" "$NOW" "$WAL_MAX_AGE_MINUTES" "$failed")" = "alert" ]; then
    if [ -z "$last" ] || [ "$last" = "0" ]; then
      alert pgbackrest "WAL archiving: $ready_count pending segment(s), nothing archived yet (or the archive query failed)"
    else
      age_min=$(( (NOW - last) / 60 ))
      alert pgbackrest "WAL archive stalled: $ready_count pending, idle for $age_min min (> $WAL_MAX_AGE_MINUTES min)"
    fi
  fi
  return 0
}

check_logical_backup() {
  local file newest=0 mtime age_min
  for file in "$BACKUP_DIR"/tcrm-*.dump.gpg; do
    [ -e "$file" ] || continue
    mtime=$(file_mtime "$file") || mtime=0
    [ "$mtime" -gt "$newest" ] && newest=$mtime
  done
  if [ "$newest" -eq 0 ]; then
    alert backup "no .dump.gpg logical backup found in $BACKUP_DIR"
    return
  fi
  age_min=$(( (NOW - newest) / 60 ))
  [ "$(age_level "$age_min" $((BACKUP_MAX_AGE_HOURS * 60)))" = "alert" ] &&
    alert backup "latest logical backup is $((age_min / 60))h old (> $BACKUP_MAX_AGE_HOURS h)"
  return 0
}

check_app_logs() {
  compose_cmd
  local svc logs f1 f2 f3 f4 message
  for svc in crm-admin crm-redirect; do
    logs="$("${COMPOSE[@]}" logs --since "$SINCE" "$svc" 2>/dev/null)"
    [ -n "$logs" ] || continue
    while IFS=$'\t' read -r f1 f2 f3 f4; do
      case "$f1" in
        applog)
          message="$svc: $f2 ERROR log line(s) since the last check (loggers: $f3"
          [ -n "$f4" ] && message="$message; traceIds: $f4"
          alert applog "$message)"
          ;;
        capacity)
          alert capacity "$svc: $f2 capacity WARN x$f3 (logger $f4)"
          ;;
      esac
    done < <(printf '%s\n' "$logs" | classify_log_lines)
  done
  return 0
}

# -------------------------------------------------------------------- main

main() {
  load_config
  mkdir -p "$STATE_DIR"
  ALERTS_FILE="$STATE_DIR/run.alerts"
  : > "$ALERTS_FILE"
  # Overlap beats gaps: the next scan starts at this run's begin, so anything logged while this
  # run is scanning is seen again (dedup makes that harmless).
  SINCE=$(cat "$STATE_DIR/lastrun" 2>/dev/null || printf '%s' $((NOW - 300)))

  check_disk
  check_memory
  check_containers
  check_cert
  check_pgbackrest
  check_logical_backup
  check_app_logs
  printf '%s' "$NOW" > "$STATE_DIR/lastrun"

  local key text fired=0
  for key in $ALL_KEYS; do
    text="$(awk -F'\t' -v k="$key" '$1 == k { if (s != "") s = s "; "; s = s $2 } END { print s }' "$ALERTS_FILE")"
    if [ -n "$text" ]; then
      notify "$key" 1 "$text"
      fired=$((fired + 1))
      # notify→dispatch already prints when webhook is empty; mirror to cron log only when set.
      if [ -n "$ALERT_WEBHOOK" ]; then
        printf 'ALERT %s: %s\n' "$key" "$text"
      fi
    else
      notify "$key" 0 ""
    fi
  done
  rm -f "$ALERTS_FILE"
  send_heartbeat
  [ "$fired" -eq 0 ] && echo "ops-check: all checks passed"
  return 0
}

# Run only when executed, so the functions above can be sourced by tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
