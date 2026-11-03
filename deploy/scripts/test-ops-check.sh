#!/usr/bin/env bash
# Unit tests for the judgment logic of ops-check.sh (host cron monitor, task 96). Sources the
# script for its functions; nothing here touches Docker, the real curl or files outside a temp
# directory. Run: bash deploy/scripts/test-ops-check.sh
set -euo pipefail
# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/ops-check.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { # <expected> <actual> <what>
  [ "$2" = "$1" ] || fail "$3: expected [$1], got [$2]"
}

# --- thresholds ----------------------------------------------------------

expect ok "$(disk_level 69 70 85)" "disk below warn"
expect warn "$(disk_level 70 70 85)" "disk at warn"
expect warn "$(disk_level 84 70 85)" "disk between warn and crit"
expect crit "$(disk_level 85 70 85)" "disk at crit"
expect crit "$(disk_level 99 70 85)" "disk above crit"

expect ok "$(mem_level 10 10)" "memory at minimum is ok"
expect alert "$(mem_level 9 10)" "memory below minimum alerts"
expect alert "$(mem_level 0 10)" "memory exhausted alerts"

expect ok "$(cert_level 21 20)" "certificate above minimum"
expect alert "$(cert_level 20 20)" "certificate at minimum alerts"
expect alert "$(cert_level 3 20)" "certificate near expiry alerts"

expect ok "$(age_level 1560 1560)" "age at the maximum is ok"
expect alert "$(age_level 1561 1560)" "age past the maximum alerts"

# WAL stall: pending .ready required; idle (ready=0) never alerts.
# L-078: when last_archived is set, also require last_failed > last_archived.
expect ok "$(wal_stall_level 0 0 1000000 15)" "idle with never-archived is ok"
expect ok "$(wal_stall_level 0 1 1000000 15)" "idle with old last_archived is ok"
expect alert "$(wal_stall_level 1 0 1000000 15)" "pending and never archived alerts"
expect alert "$(wal_stall_level 2 $((1000000 - 16 * 60)) 1000000 15 $((1000000 - 1)))" "pending stalled with newer failure alerts"
expect ok "$(wal_stall_level 2 $((1000000 - 16 * 60)) 1000000 15 0)" "stale archive without failure is ok (idle first write)"
expect ok "$(wal_stall_level 2 $((1000000 - 16 * 60)) 1000000 15 $((1000000 - 20 * 60)))" "failure older than last archive is ok"
expect ok "$(wal_stall_level 2 $((1000000 - 10 * 60)) 1000000 15 $((1000000 - 1)))" "pending but recent archive is ok"

expect "disk usage % on / CRITICAL (>= %)"   "$(alert_signature "disk usage 86% on / CRITICAL (>= 85%)")" "signature strips digits"
expect "$(alert_signature "disk usage 72% on / (>= 70%)")"   "$(alert_signature "disk usage 73% on / (>= 70%)")" "numeric-only change keeps signature"
[ "$(alert_signature "disk usage 72% on / (>= 70%)")" !=   "$(alert_signature "disk usage 86% on / CRITICAL (>= 85%)")" ] ||
  fail "warn vs CRITICAL must change signature"
# L-077: traceIds must not change the signature.
expect "$(alert_signature "crm-admin: 3 ERROR (loggers: x; traceIds: abc def)")"   "$(alert_signature "crm-admin: 3 ERROR (loggers: x; traceIds: xyz new)")"   "traceIds tail ignored in signature"

# --- time parsing (GNU and BSD date formats) -----------------------------

# 2026-01-02T03:04:05Z in both input shapes must give the same epoch (1767323045).
expect 1767323045 "$(parse_epoch "Jan 02 03:04:05 2026 GMT")" "openssl notAfter epoch"
expect 1767323045 "$(parse_epoch "2026-01-02T03:04:05Z")" "docker StartedAt epoch"

expect "2026-11-02T10:00:00Z" "$(strip_fraction "2026-11-02T10:00:00.123456789Z")" "fraction stripped, zone kept"
expect "2026-11-02T10:00:00" "$(strip_fraction "2026-11-02T10:00:00")" "no fraction: unchanged"

# --- JSON ----------------------------------------------------------------

expect 'a \"quoted\" \\\\ backslash' "$(json_escape 'a "quoted" \\ backslash')" "json escape quotes"
expect 'line1\nline2' "$(json_escape 'line1
line2')" "json escape newline"
payload="$(printf '{"msg_type":"text","content":{"text":"%s"}}' "$(json_escape 'say "hi"')")"
expect '{"msg_type":"text","content":{"text":"say \"hi\""}}' "$payload" "feishu payload shape"

# --- log classification ---------------------------------------------------

# Realistic Spring Boot 4 console lines (docker compose prefixes each with "crm-admin-1  | "):
# date  LEVEL  pid --- [app] [thread] [traceId] logger : message
LOG="$(cat <<'EOF'
crm-admin-1  | 2026-11-02T09:00:00.123Z  INFO 1 --- [crm-admin] [main] [] com.tcrm.admin.TcrmAdminApplication : Started TcrmAdminApplication
crm-admin-1  | 2026-11-02T09:01:00.456Z  WARN 1 --- [crm-admin] [scheduling-1] [] c.t.admin.link.service.ClickStreamMonitor : Click stream backlog at 55% of the limit (550000 of 1000000)
crm-admin-1  | 2026-11-02T09:02:00.789Z  WARN 1 --- [crm-admin] [scheduling-1] [] c.t.admin.link.service.ClickStreamMonitor : Redis used_memory at 72% of the limit (921600000 of 1280000000)
crm-admin-1  | 2026-11-02T09:03:00.111Z  WARN 1 --- [crm-admin] [scheduling-1] [] c.t.admin.order.service.SomeChecker : Unrelated warning that must be ignored
crm-admin-1  | 2026-11-02T09:04:00.222Z ERROR 1 --- [crm-admin] [http-nio-8080-exec-4] [t96abc123] c.t.admin.commission.service.WalletReconciler : mismatched creators t96_user@example.com balance drift
crm-admin-1  |         at com.tcrm.admin.commission.service.WalletReconciler.check(WalletReconciler.java:88)
crm-admin-1  | 2026-11-02T09:05:00.333Z ERROR 1 --- [crm-admin] [scheduling-1] [] c.t.admin.commission.service.WalletReconciler : second error without trace id, mail t96_user@example.com bounced
EOF
)"
OUT="$(printf '%s\n' "$LOG" | classify_log_lines)"

expect "applog	2	c.t.admin.commission.service.WalletReconciler:2	t96abc123" \
  "$(printf '%s\n' "$OUT" | grep '^applog	')" "applog summary"
case "$OUT" in
  *'t96_user@example.com'*) fail "log message body leaked into the summary" ;;
esac
printf '%s\n' "$OUT" | grep -q '^capacity	click-backlog	1	c.t.admin.link.service.ClickStreamMonitor$' ||
  fail "click backlog capacity line wrong"
printf '%s\n' "$OUT" | grep -q '^capacity	redis-memory	1	c.t.admin.link.service.ClickStreamMonitor$' ||
  fail "redis memory capacity line wrong"
[ "$(printf '%s\n' "$OUT" | grep -c '^capacity	')" = "2" ] || fail "capacity line count"
case "$OUT" in
  *SomeChecker*) fail "unrelated WARN must be ignored" ;;
esac
# The app name bracket ([crm-admin]) must not be captured as logger or traceId.
case "$OUT" in
  *applog*crm-admin*) fail "app name leaked into the applog summary" ;;
esac

# A line without the Spring Boot shape (no date, no "---") is not a log line, however loud it is.
OUT2="$(printf 'someone wrote ERROR t96_user@example.com in a message body\n' | classify_log_lines)"
[ -z "$OUT2" ] || fail "non-log line classified: $OUT2"

# --- list helpers ----------------------------------------------------------

expect 'a:1' "$(bump_count "" "a")" "bump on empty list"
expect 'a:2 b:1' "$(bump_count "a:1 b:1" "a")" "bump increments"
expect 'x' "$(add_unique "" "x")" "add_unique on empty"
expect 'x y' "$(add_unique "x" "y")" "add_unique appends"
expect 'x' "$(add_unique "x" "x")" "add_unique dedupes"

# --- dedup, repeat and recovery --------------------------------------------

STATE_DIR="$WORK/state"
mkdir -p "$STATE_DIR"
# ALERT_REPEAT_SECONDS is read by the sourced notify(), hence "unused" in this file only.
# shellcheck disable=SC2034
ALERT_REPEAT_SECONDS=3600
SENT="$WORK/sent.log"
: > "$SENT"
dispatch() { printf '%s\n' "$1" >> "$SENT"; }

notify disk 1 "disk usage 71% on / (>= 70%)"
notify disk 1 "disk usage 72% on / (>= 70%)"  # same signature, within the hour: no repeat
[ "$(grep -c . "$SENT")" = "1" ] || fail "dedup: expected 1 send, got $(grep -c . "$SENT")"

# Signature change within the hour (warn → CRITICAL) must send immediately.
notify disk 1 "disk usage 86% on / CRITICAL (>= 85%)"
[ "$(grep -c . "$SENT")" = "2" ] || fail "signature upgrade (warn→CRITICAL) must send"
grep -q 'CRITICAL' "$SENT" || fail "CRITICAL text missing after signature change"

# Simulate an hour-old state: the next occurrence must repeat even with same signature.
write_state "$STATE_DIR/disk.state" 1 "$(( $(now_epoch) - 3601 ))"   "disk usage 86% on / CRITICAL (>= 85%)" "$(alert_signature "disk usage 86% on / CRITICAL (>= 85%)")"
notify disk 1 "disk usage 87% on / CRITICAL (>= 85%)"
[ "$(grep -c . "$SENT")" = "3" ] || fail "repeat after the hour missing"

notify disk 0 ""
[ "$(grep -c . "$SENT")" = "4" ] || fail "recovery notice missing"
grep -q '^RECOVERED disk: disk usage 87% on / CRITICAL (>= 85%) — OK now$' "$SENT" ||   fail "recovery text wrong: $(cat "$SENT")"
notify disk 0 ""
[ "$(grep -c . "$SENT")" = "4" ] || fail "duplicate recovery notice"

# Container: adding another failed service changes the signature → immediate send.
: > "$SENT"
notify container 1 "redis: health=unhealthy"
notify container 1 "redis: health=unhealthy; postgres: status=exited"
[ "$(grep -c . "$SENT")" = "2" ] || fail "new fault in same key must send (got $(grep -c . "$SENT"))"
grep -q 'postgres: status=exited' "$SENT" || fail "postgres fault missing from resend"

# State survives text with quotes, spaces and unicode.
notify memory 1 'mem 9% (< 10%) "quote" 中文'
# shellcheck disable=SC1091
. "$STATE_DIR/memory.state"
[ "$FIRING" = "1" ] || fail "memory state firing"
[ -n "${SIG:-}" ] || fail "memory state missing SIG"
notify memory 0 ""
grep -q 'quote" 中文' "$SENT" || fail "recovery lost the stored text"

# --- dispatch through a stubbed curl ---------------------------------------

# The stub above replaced the real function (same name, one table entry): source the script again
# to restore it.
# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/ops-check.sh"
# ALERT_WEBHOOK and CURL_TIMEOUT are read by the (restored) dispatch().
# shellcheck disable=SC2034
ALERT_WEBHOOK="http://hook.invalid/feishu"
# shellcheck disable=SC2034
CURL_TIMEOUT=5
CURL_LOG="$WORK/curl.log"
: > "$CURL_LOG"
curl() { printf 'url=%s payload=%s\n' "${@: -1}" "$*" >> "$CURL_LOG"; }
dispatch 'msg with "quotes"'
grep -q 'url=http://hook.invalid/feishu' "$CURL_LOG" || fail "webhook URL not used"
grep -q 'content":{"text":"msg with \\"quotes\\""}' "$CURL_LOG" ||
  fail "payload shape wrong: $(cat "$CURL_LOG")"

# --- df filter (fixed Ubuntu sample; do not rely on the host's live df) -----

DF_SAMPLE="$(cat <<'EOF'
Filesystem     1024-blocks    Used Available Capacity Mounted on
/dev/root         30315248 8123456  22176376      27% /
tmpfs              3918848       0   3918848       0% /dev/shm
/dev/loop0           55552   55552         0     100% /snap/amazon-ssm-agent/9881
/dev/loop1           74688   74688         0     100% /snap/core22/1663
/dev/nvme0n1p15     106858    6212    100646       6% /boot/efi
overlay           30315248 8123456  22176376      27% /var/lib/docker/overlay2/abc/merged
EOF
)"
DF_OUT="$(printf '%s\n' "$DF_SAMPLE" | filter_df_usage)"
printf '%s\n' "$DF_OUT" | grep -q $'^/\t27$' || fail "root mount missing from filtered df: $DF_OUT"
printf '%s\n' "$DF_OUT" | grep -q $'^/boot/efi\t6$' || fail "boot/efi missing from filtered df"
case "$DF_OUT" in
  */snap/*) fail "snap mount leaked into filtered df: $DF_OUT" ;;
  */dev/shm*) fail "tmpfs leaked into filtered df: $DF_OUT" ;;
  *overlay*) fail "overlay leaked into filtered df: $DF_OUT" ;;
esac
[ "$(printf '%s\n' "$DF_OUT" | grep -c .)" = "2" ] || fail "unexpected filtered df rows: $DF_OUT"

# --- environment sanity (host specific, must at least be numeric) ----------

case "$(memory_available_percent)" in
  ''|*[!0-9]*) fail "memory_available_percent is not numeric" ;;
esac
while IFS=$'\t' read -r mount pct; do
  case "$pct" in
    ''|*[!0-9]*) fail "df usage not numeric for $mount: $pct" ;;
  esac
  case "$mount" in
    /snap|/*/snap/*) fail "live df_usage_lines still includes snap: $mount" ;;
  esac
done < <(df_usage_lines)


# L-076: Feishu body code≠0 is not a delivery — LAST must stay so the next run retries.
# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/ops-check.sh"
ALERT_WEBHOOK="http://hook.invalid/feishu-reject"
CURL_TIMEOUT=5
curl() { printf '{"code":19021,"msg":"sign match fail"}\n'; }
rm -f "$STATE_DIR/disk.state"
SENT="$WORK/sent-reject.txt"; : > "$SENT"
# Wrap dispatch to also record attempts while keeping return code.
_orig_dispatch=$(declare -f dispatch)
eval "$_orig_dispatch"
notify disk 1 "disk usage 72% on / (>= 70%)"
# shellcheck disable=SC1091
. "$STATE_DIR/disk.state"
[ "$FIRING" = "1" ] || fail "rejected webhook should still mark firing"
[ "${LAST:-0}" = "0" ] || fail "rejected webhook must not record LAST (got $LAST)"
# A successful delivery afterwards should send and stamp LAST.
curl() { printf '{"code":0,"msg":"success"}\n'; }
notify disk 1 "disk usage 72% on / (>= 70%)"
# shellcheck disable=SC1091
. "$STATE_DIR/disk.state"
[ "${LAST:-0}" -gt 0 ] || fail "successful webhook should record LAST"

echo "ops-check: all tests passed"
