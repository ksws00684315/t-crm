#!/usr/bin/env bash
# Offline test of renew-public-cert.sh: a stub "docker" replaces certbot and Compose, so nothing
# touches Docker, Let's Encrypt or Cloudflare. Run: bash deploy/scripts/test-renew-public-cert.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/renew-public-cert.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# Stub: "docker run" writes a fake certificate into the mounted letsencrypt directory and records
# the arguments; "docker compose ... ps" reports nginx as running (STUB_NGINX_RUNNING=1) or not;
# "docker compose ... exec" is recorded.
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
case "$1" in
  run)
    for arg in "$@"; do
      case "$arg" in *:/etc/letsencrypt) le="${arg%%:*}" ;; esac
    done
    mkdir -p "$le/live/tcrm-public"
    printf 'chain-%s\n' "${STUB_CERT_VERSION:-1}" > "$le/live/tcrm-public/fullchain.pem"
    printf 'key-%s\n' "${STUB_CERT_VERSION:-1}" > "$le/live/tcrm-public/privkey.pem"
    ;;
  compose)
    case " $* " in
      *" ps "*) [ "${STUB_NGINX_RUNNING:-1}" = 1 ] && echo nginx-container-id ;;
    esac
    ;;
esac
STUB
chmod +x "$WORK/bin/docker"

export PATH="$WORK/bin:$PATH" STUB_LOG="$WORK/docker.log"
export TCRM_DOMAIN=example.test TCRM_CERT_DIR="$WORK/certs" TCRM_CERTBOT_EMAIL=ops@example.test
export TCRM_CERTBOT_STATE_DIR="$WORK/state" TCRM_CERTBOT_CLOUDFLARE_INI="$WORK/cf.ini"
printf 'dns_cloudflare_api_token = SECRET-TOKEN-VALUE\n' > "$WORK/cf.ini"
chmod 600 "$WORK/cf.ini"

fail() { echo "FAIL: $*" >&2; exit 1; }
mode() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }

# 1. First issuance, nginx not running: files are placed with mode 600, no reload attempted.
STUB_NGINX_RUNNING=0 bash "$SCRIPT" > "$WORK/out1.txt"
[ "$(cat "$WORK/certs/public.pem")" = chain-1 ] || fail "public.pem content"
[ "$(cat "$WORK/certs/public.key")" = key-1 ] || fail "public.key content"
[ "$(mode "$WORK/certs/public.pem")" = 600 ] || fail "public.pem mode"
[ "$(mode "$WORK/certs/public.key")" = 600 ] || fail "public.key mode"
grep -q 'nginx is not running' "$WORK/out1.txt" || fail "should skip reload when nginx is down"
! grep -q 'exec' "$WORK/docker.log" || fail "no exec expected when nginx is down"
grep -q 'certbot/dns-cloudflare:v' "$WORK/docker.log" || fail "pinned image"
grep -q -- '-d admin.example.test -d upload.example.test' "$WORK/docker.log" || fail "domains"
! grep -rq SECRET-TOKEN-VALUE "$WORK/out1.txt" "$WORK/docker.log" || fail "token leaked"

# 2. Unchanged certificate: no copy, no reload.
: > "$WORK/docker.log"
bash "$SCRIPT" > "$WORK/out2.txt"
grep -q 'unchanged' "$WORK/out2.txt" || fail "unchanged message"
! grep -q 'exec' "$WORK/docker.log" || fail "no reload when unchanged"

# 3. Renewed certificate with nginx running: files replaced, nginx -t then reload.
: > "$WORK/docker.log"
STUB_CERT_VERSION=2 bash "$SCRIPT" > "$WORK/out3.txt"
[ "$(cat "$WORK/certs/public.pem")" = chain-2 ] || fail "renewed public.pem"
[ "$(mode "$WORK/certs/public.key")" = 600 ] || fail "renewed key mode"
t_line="$(grep -n 'exec -T nginx nginx -t' "$WORK/docker.log" | cut -d: -f1)"
r_line="$(grep -n 'exec -T nginx nginx -s reload' "$WORK/docker.log" | cut -d: -f1)"
[ -n "$t_line" ] || fail "nginx -t missing"
[ -n "$r_line" ] || fail "reload missing"
[ "$t_line" -lt "$r_line" ] || fail "nginx -t must run before reload"

# 4. Credentials file with loose permissions is refused before certbot runs.
chmod 644 "$WORK/cf.ini"
: > "$WORK/docker.log"
if bash "$SCRIPT" > "$WORK/out4.txt" 2>&1; then fail "loose credentials file accepted"; fi
grep -q 'mode 600' "$WORK/out4.txt" || fail "mode message"
[ ! -s "$WORK/docker.log" ] || fail "docker must not run with a loose credentials file"

echo "renew-public-cert: all checks passed"
