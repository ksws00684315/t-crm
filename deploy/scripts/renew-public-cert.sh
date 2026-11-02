#!/usr/bin/env bash
# Obtain and renew the publicly trusted certificate for admin.<domain> and upload.<domain>
# (ADR-037 decision 3). t. and kol.<domain> keep the Cloudflare origin certificate and are not
# handled here. The certificate is requested from Let's Encrypt with the DNS-01 challenge through
# the Cloudflare DNS API (certbot/dns-cloudflare container), then copied to
#   $TCRM_CERT_DIR/public.pem   (full chain)
#   $TCRM_CERT_DIR/public.key   (private key, mode 600)
# and Nginx is reloaded when the files changed.
#
# Required (from deploy/.env or the environment):
#   TCRM_DOMAIN                   base domain
#   TCRM_CERT_DIR                 certificate directory mounted into Nginx (relative paths are
#                                 relative to deploy/, like in the Compose file; default ./certs)
#   TCRM_CERTBOT_CLOUDFLARE_INI   path of a file, mode 600, kept OUTSIDE the repository, with one line:
#                                   dns_cloudflare_api_token = <token limited to Zone:DNS:Edit on this zone>
#   TCRM_CERTBOT_EMAIL            contact address of the Let's Encrypt account
# Optional:
#   TCRM_CERTBOT_STATE_DIR        certbot account and certificate state (default deploy/certbot, not
#                                 versioned; back it up together with the secrets)
# The token is only read by the certbot container from the mounted file: it never appears in the
# command line, in this script's output or in .env.
#
# First issuance (before the first "docker compose up", Nginx cannot start without public.pem):
#   deploy/scripts/renew-public-cert.sh          # as root, or a user that may run docker
# Renewal: host cron, once a day (certbot renews only within 30 days of expiry, so daily runs are
# cheap no-ops). Example for root's crontab:
#   17 4 * * * /opt/tcrm/deploy/scripts/renew-public-cert.sh >> /var/log/tcrm-cert.log 2>&1
# A failed run exits non-zero; the current certificate keeps serving until it expires.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERTBOT_IMAGE="certbot/dns-cloudflare:v5.8.0"
CERT_NAME="tcrm-public"

die() { echo "renew-public-cert: $*" >&2; exit 1; }

file_mode() { # octal permission bits, GNU stat first, BSD stat as the fallback
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"
}

main() {
  # shellcheck disable=SC1091
  set -a; [ -f "$DEPLOY_DIR/.env" ] && . "$DEPLOY_DIR/.env"; set +a

  local domain="${TCRM_DOMAIN:?TCRM_DOMAIN is required}"
  local cert_dir="${TCRM_CERT_DIR:-./certs}"
  local ini="${TCRM_CERTBOT_CLOUDFLARE_INI:?TCRM_CERTBOT_CLOUDFLARE_INI is required}"
  local email="${TCRM_CERTBOT_EMAIL:?TCRM_CERTBOT_EMAIL is required}"
  local state_dir="${TCRM_CERTBOT_STATE_DIR:-$DEPLOY_DIR/certbot}"
  case "$cert_dir" in /*) ;; *) cert_dir="$DEPLOY_DIR/$cert_dir" ;; esac
  case "$state_dir" in /*) ;; *) state_dir="$DEPLOY_DIR/$state_dir" ;; esac

  [ -f "$ini" ] || die "credentials file not found: $ini"
  [ "$(file_mode "$ini")" = "600" ] || die "credentials file must have mode 600: $ini"

  umask 077
  mkdir -p "$cert_dir" "$state_dir/letsencrypt" "$state_dir/log"

  docker run --rm \
    -v "$state_dir/letsencrypt:/etc/letsencrypt" \
    -v "$state_dir/log:/var/log/letsencrypt" \
    -v "$ini:/run/secrets/cloudflare.ini:ro" \
    "$CERTBOT_IMAGE" certonly \
    --dns-cloudflare --dns-cloudflare-credentials /run/secrets/cloudflare.ini \
    --dns-cloudflare-propagation-seconds 30 \
    --cert-name "$CERT_NAME" -d "admin.$domain" -d "upload.$domain" \
    --email "$email" --agree-tos --no-eff-email --non-interactive --keep-until-expiring

  local live="$state_dir/letsencrypt/live/$CERT_NAME" changed=0 pair src dst tmp
  if [ ! -f "$live/fullchain.pem" ] || [ ! -f "$live/privkey.pem" ]; then
    die "certbot did not produce $live"
  fi
  for pair in "fullchain.pem:public.pem" "privkey.pem:public.key"; do
    src="$live/${pair%%:*}"
    dst="$cert_dir/${pair##*:}"
    if [ ! -f "$dst" ] || ! cmp -s "$src" "$dst"; then
      tmp="$dst.new"
      install -m 600 "$src" "$tmp"
      mv -f "$tmp" "$dst"
      changed=1
      echo "updated $dst"
    fi
  done

  if [ "$changed" = "0" ]; then
    echo "certificate unchanged, nothing to reload"
    return 0
  fi

  local compose=(docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" --env-file "$DEPLOY_DIR/.env")
  if [ -n "$("${compose[@]}" ps --status running -q nginx 2>/dev/null)" ]; then
    "${compose[@]}" exec -T nginx nginx -t
    "${compose[@]}" exec -T nginx nginx -s reload
    echo "nginx reloaded"
  else
    echo "nginx is not running; start it with docker compose up -d"
  fi
}

# Run only when executed, so tests can source the functions.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
