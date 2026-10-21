#!/usr/bin/env bash
# Build and run a local preview against the shared local-infra middleware.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="${TCRM_LOCAL_ENV_FILE:-$ROOT/deploy/.env.local}"
COMPOSE_FILE="$ROOT/deploy/docker-compose.preview.yml"
PROJECT="${TCRM_PREVIEW_PROJECT:-tcrm-preview}"

usage() {
  echo "用法：deploy/scripts/preview.sh up|down|reset" >&2
  exit 1
}

require_docker() {
  command -v docker >/dev/null 2>&1 || { echo "缺少命令：docker。" >&2; exit 1; }
  docker compose version >/dev/null 2>&1 || { echo "缺少 Docker Compose 插件。" >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker 没有在运行。" >&2; exit 1; }
}

compose() {
  [[ -f "$ENV_FILE" ]] || { echo "找不到 ${ENV_FILE}，请先执行 deploy/scripts/local-infra.sh up。" >&2; exit 1; }
  export TCRM_PREVIEW_ADMIN_PORT="${TCRM_PREVIEW_ADMIN_PORT:-8890}"
  export TCRM_PREVIEW_KOL_PORT="${TCRM_PREVIEW_KOL_PORT:-8891}"
  export TCRM_PREVIEW_REDIRECT_PORT="${TCRM_PREVIEW_REDIRECT_PORT:-8892}"
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

wait_healthy() {
  local service="$1" deadline id status
  deadline=$((SECONDS + 300))
  echo "等待 ${service} 健康检查通过…"
  while (( SECONDS < deadline )); do
    id="$(compose ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$id" ]]; then
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null || true)"
      [[ "$status" == healthy ]] && return 0
      [[ "$status" == unhealthy ]] && break
    fi
    sleep 3
  done
  echo "健康检查未通过（服务 ${service}）。" >&2
  compose logs --tail=100 "$service" >&2 || true
  exit 1
}

cmd_up() {
  require_docker
  for cmd in pnpm node openssl curl; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "缺少命令：${cmd}。" >&2; exit 1; }
  done
  TCRM_LOCAL_ENV_FILE="$ENV_FILE" "$ROOT/deploy/scripts/local-infra.sh" up
  export TCRM_STATIC_DIR="$ROOT/deploy/www-preview"
  "$ROOT/deploy/scripts/build-static.sh"
  compose up -d --build
  wait_healthy crm-admin
  wait_healthy crm-redirect
  # Compose may recreate the Java containers without recreating Nginx. Nginx resolves the static
  # proxy_pass hostnames when it loads its config, so it otherwise keeps the old container IP and
  # serves 502 even though both Java health checks pass.
  compose exec -T nginx nginx -s reload
  curl --fail --silent --show-error --max-time 5 --retry 3 --retry-delay 1 --request POST \
    --output /dev/null "http://127.0.0.1:${TCRM_PREVIEW_ADMIN_PORT}/admin-api/auth/captchas"
  compose ps
  echo "管理端    http://localhost:${TCRM_PREVIEW_ADMIN_PORT:-8890}"
  echo "达人端    http://localhost:${TCRM_PREVIEW_KOL_PORT:-8891}"
  echo "跳转服务  http://localhost:${TCRM_PREVIEW_REDIRECT_PORT:-8892} （/t/…、/i/…、/healthz）"
  echo "提示：首次使用先用平台管理员为默认公司创建超级管理员，再以超级管理员登录。"
}

cmd_down() {
  require_docker
  compose down
}

cmd_reset() {
  local reply
  require_docker
  printf '将删除本预览项目的容器和本地镜像；共享数据库和 Redis 数据不会删除。输入 yes 确认：'
  read -r reply
  [[ "$reply" == yes ]] || { echo "已取消。"; return 0; }
  compose down --rmi local
  echo "预览容器和镜像已清理；共享中间件及数据保留。"
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  reset) cmd_reset ;;
  *) usage ;;
esac
