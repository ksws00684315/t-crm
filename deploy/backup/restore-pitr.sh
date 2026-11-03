#!/usr/bin/env bash
# Point-in-time restore of the production PostgreSQL from the pgBackRest repository (ADR-037
# decision 5). Destructive: it replaces the whole database cluster, so it asks for "yes", refuses a
# non-empty data directory unless --overwrite is given, and stops crm-admin, crm-redirect and
# postgres for the duration (the site is down; Redis and Nginx are not touched).
#
# Usage: restore-pitr.sh [--overwrite] <latest | "YYYY-MM-DD HH:MM:SS[.ffff][+TZ]">
#   latest   replay all archived WAL (data loss is bounded by archive_timeout, about 5 minutes)
#   a time   restore to that moment; without a UTC offset the time is taken as UTC
# Run from any directory on the production host. Environment (optional, for non-standard layouts):
#   TCRM_COMPOSE_FILE (default deploy/docker-compose.prod.yml), TCRM_ENV_FILE (default deploy/.env)
# Restoring from a logical backup (pg_dump) is described in pg-backup.sh.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# How long to wait for PostgreSQL to finish WAL replay and accept writes.
RECOVERY_WAIT_SECONDS=3600

usage() {
  echo "用法：deploy/backup/restore-pitr.sh [--overwrite] <latest | \"YYYY-MM-DD HH:MM:SS[+TZ]\">" >&2
  exit 2
}

# Prints the target in the form pgBackRest expects; fails for anything that is not a timestamp.
# A timestamp without a UTC offset is UTC.
normalize_target() {
  local target="$1"
  if [[ ! "$target" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?([+-][0-9]{2}(:?[0-9]{2})?)?$ ]]; then
    return 1
  fi
  # Offset present only when the sign follows the seconds field.
  if [[ "$target" =~ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?[+-] ]]; then
    printf '%s\n' "$target"
  else
    printf '%s+00\n' "$target"
  fi
}

main() {
  local overwrite=0 target=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --overwrite) overwrite=1 ;;
      -h | --help) usage ;;
      *)
        [ -z "$target" ] || usage
        target="$1"
        ;;
    esac
    shift
  done
  [ -n "$target" ] || usage

  local restore_args=()
  if [ "$target" = "latest" ]; then
    :
  elif target="$(normalize_target "$target")"; then
    restore_args=(--type=time "--target=$target" --target-action=promote)
  else
    echo "时间点格式不对，应为 latest 或 \"YYYY-MM-DD HH:MM:SS\"（可带 +00 之类的时区）。" >&2
    exit 2
  fi

  local compose=(docker compose -f "${TCRM_COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.prod.yml}" --env-file "${TCRM_ENV_FILE:-$DEPLOY_DIR/.env}")
  local project
  project="$("${compose[@]}" config | sed -n 's/^name: //p')"
  [ -n "$project" ] || { echo "无法确定 Compose 项目名。" >&2; exit 1; }

  # Throw-away container on the same data volume: is the data directory empty?
  local existing
  existing="$("${compose[@]}" run --rm --no-deps -T --entrypoint sh postgres -c 'ls -A /var/lib/postgresql/data | head -n 1' < /dev/null)"
  if [ -n "$existing" ] && [ "$overwrite" -ne 1 ]; then
    echo "项目 $project 的 PostgreSQL 数据目录不是空的，默认拒绝恢复。确认要覆盖现有数据时加 --overwrite。" >&2
    exit 1
  fi

  echo "即将恢复："
  echo "  目标项目：$project"
  echo "  恢复到：  $([ "$target" = "latest" ] && echo "仓库中最新的已归档数据（最多丢失约 5 分钟）" || echo "$target")"
  if [ -n "$existing" ]; then
    echo "  将被覆盖：项目 $project 现有的整个 PostgreSQL 数据目录（所有库、表和日志），目标时间之后写入的数据永久丢失"
  else
    echo "  将被覆盖：无（数据目录为空）"
  fi
  echo "  停机：    crm-admin、crm-redirect、postgres 将停止，恢复期间站点不可用（Redis、Nginx 不动）"
  printf '输入 yes 继续：'
  local reply
  read -r reply || reply=""
  [ "$reply" = "yes" ] || { echo "已取消。"; exit 0; }

  # docker compose must not read the answer line from stdin: all calls below get /dev/null.
  "${compose[@]}" stop crm-admin crm-redirect postgres < /dev/null
  # Runs as the postgres OS user; --delta (only for a non-empty directory) reuses files that already
  # match and replaces the rest. A stale postmaster.pid left by a crash makes pgBackRest refuse:
  # with the container stopped, delete that file by hand (deploy/README.md) and run the script again.
  "${compose[@]}" run --rm --no-deps -T -u postgres --entrypoint pgbackrest postgres \
    --stanza=tcrm ${existing:+--delta} --log-level-console=info ${restore_args[@]+"${restore_args[@]}"} restore < /dev/null
  "${compose[@]}" up -d postgres < /dev/null

  echo "等待 PostgreSQL 回放 WAL 并结束恢复……"
  local waited=0 state=""
  while [ "$waited" -lt "$RECOVERY_WAIT_SECONDS" ]; do
    # shellcheck disable=SC2016 # $POSTGRES_* must expand inside the container
    state="$("${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select pg_is_in_recovery()"' < /dev/null 2> /dev/null || true)"
    [ "$state" = "f" ] && break
    sleep 5
    waited=$((waited + 5))
  done
  if [ "$state" != "f" ]; then
    echo "等待 ${RECOVERY_WAIT_SECONDS} 秒后 PostgreSQL 仍未完成恢复，请查看：docker compose logs postgres" >&2
    exit 1
  fi
  "${compose[@]}" up -d crm-admin crm-redirect < /dev/null

  cat << 'MSG'

数据库已恢复，应用已启动。本脚本没有动 Redis：会话可能还在，链接索引也会比刚恢复的数据库新。
以下事项必须人工完成：
  1. 登录超级管理员，在「对应关系」页点击「重建链接索引」（索引仍是恢复前的，不做则中转可能指到库里已不存在的对应关系，或跳到回退地址）。
  2. 核对钱包核对日志：docker compose logs crm-admin | grep "wallet reconciliation"（任务每日 02:30 UTC 运行，
     也可等下一次运行后再看），出现 mismatched creators 时逐个达人核对余额与台账。
  3. 核对恢复点之后发生的人工操作（人工归因、提现付款、预付）：它们无法从微店补拉，需要按业务记录补录。
  4. 立即做一次全量备份，恢复产生了新的时间线：deploy/backup/pgbackrest-backup.sh full
  5. 按需要决定是否清会话：Redis 里的登录态可能仍有效；若恢复点之后有权限或密码变更，请在管理端强制下线或等会话自然过期，达人端同理。
MSG
}

main "$@"
