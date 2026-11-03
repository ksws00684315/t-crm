#!/usr/bin/env bash
# Release, update and roll back the production Compose stack from registry images (ADR-037 decision 7).
# Run on the production host, from any directory, as a user that may run docker:
#
#   release.sh deploy <version> [--postgres <pg-version>]
#       e.g. 1.0.1 (a leading "v" is accepted). --postgres updates TCRM_POSTGRES_VERSION and
#       pulls that crm-postgres tag; omitted = leave the database image alone (L-070/L-073).
#       A first deployment (no postgres container yet, TCRM_POSTGRES_VERSION not pinned) also
#       pre-pulls the crm-postgres tag it is about to pin, so a pull failure surfaces in the pull
#       step instead of inside "compose up" after the release has already started changing things.
#   release.sh rollback <version>   switch app/front end only — never pulls or changes postgres
#   release.sh status               current version, running images, "current" link, release history
#
# deploy: check .env -> pull images -> extract the static files to releases/<version>/ -> pg-backup.sh
# (a failed backup stops the release) -> update crm-admin and wait until healthy -> update crm-redirect
# only when its image changed -> switch the "current" link atomically -> write TCRM_VERSION into .env
# -> append to the release log. The first deployment (no postgres container yet) skips the backup and
# also starts postgres, redis and nginx. PostgreSQL, Redis and Nginx are never restarted afterwards.
# The front end is not switched while crm-admin is unhealthy. The database is never rolled back and
# TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS is never touched (deploy/README.md, "Release and rollback").
#
# Environment (all optional):
#   TCRM_ENV_FILE                  default deploy/.env
#   TCRM_COMPOSE_FILE              default deploy/docker-compose.prod.yml (also used by pg-backup.sh)
#   TCRM_RELEASE_LOG               default deploy/releases.log
#   TCRM_RELEASE_HEALTH_TIMEOUT    seconds to wait for crm-admin / crm-redirect (default 300)
#   TCRM_RELEASE_KEEP              static versions to keep (default 5)
#   TCRM_RELEASE_BACKUP_COMMAND    backup program (default deploy/backup/pg-backup.sh)
#   TCRM_RELEASE_LOCK_DIR          mkdir lock directory (default deploy/.release.lock)
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${TCRM_ENV_FILE:-$DEPLOY_DIR/.env}"
COMPOSE_FILE="${TCRM_COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.prod.yml}"
RELEASE_LOG="${TCRM_RELEASE_LOG:-$DEPLOY_DIR/releases.log}"
HEALTH_TIMEOUT="${TCRM_RELEASE_HEALTH_TIMEOUT:-300}"
KEEP_RELEASES="${TCRM_RELEASE_KEEP:-5}"
BACKUP_COMMAND="${TCRM_RELEASE_BACKUP_COMMAND:-$DEPLOY_DIR/backup/pg-backup.sh}"
LOCK_DIR="${TCRM_RELEASE_LOCK_DIR:-$DEPLOY_DIR/.release.lock}"
LOCK_HELD=0
VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'
UPDATE_POSTGRES_VERSION="" # set by deploy --postgres <ver>; empty = do not touch postgres
POSTGRES_PIN_WRITTEN=""    # set by ensure_postgres_version_pinned; first deploy pre-pulls this crm-postgres tag

# Variables without a default in docker-compose.prod.yml (and TCRM_BACKUP_PASSPHRASE_FILE for
# pg-backup.sh; TCRM_PGBACKREST_CREDS_DIR is the pgBackRest credentials mount source, task 106).
REQUIRED_ENV="TCRM_IMAGE_REGISTRY TCRM_DOMAIN TCRM_DB_USERNAME TCRM_DB_PASSWORD TCRM_DB_SUPERUSER_USERNAME \
TCRM_DB_SUPERUSER_PASSWORD TCRM_REDIS_PASSWORD TCRM_LINK_BASE_URL TCRM_KOL_PORTAL_BASE_URL TCRM_ADMIN_BASE_URL \
TCRM_REDIRECT_FALLBACK_URL TCRM_SECRET_KEY TCRM_IP_HASH_SALT PGBACKREST_REPO1_S3_BUCKET \
PGBACKREST_REPO1_CIPHER_PASS TCRM_PGBACKREST_CREDS_DIR TCRM_BACKUP_PASSPHRASE_FILE"

ACTION=""
TARGET_VERSION=""
PREVIOUS_VERSION=""
STAGE="init"
STAGE_KIND="prepare" # prepare | admin | redirect | switch | record
REPORTED=0
FIRST_DEPLOY=0
ENV_TMP=""

log() { printf '%s\n' "$*"; }
step() { STAGE="$1"; printf '\n==> %s\n' "$1"; }

usage() {
  cat >&2 << 'EOF'
用法：
  release.sh deploy <版本> [--postgres <数据库镜像版本>]
                            发布应用与前端；加 --postgres 才更新并拉取 crm-postgres
  release.sh rollback <版本>  回滚到较早的版本（只切换应用镜像和前端，不拉、不改数据库镜像）
  release.sh status           显示当前版本、运行中的镜像、current 指向和发布记录
EOF
  exit 2
}

# ---- .env access (no sourcing: the file is Compose syntax, not shell) ----------------------------------

# Prints the value of KEY in the env file (last assignment wins, surrounding quotes removed).
env_get() {
  local key="$1" line value=""
  [ -f "$ENV_FILE" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "$key="*) value="${line#"$key="}" ;;
    esac
  done < "$ENV_FILE"
  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  printf '%s' "$value"
}

# Writes KEY=VALUE into the env file atomically (same-directory mktemp + mv). The temporary file
# holds every secret of .env, so it is removed on every failure path (ENV_TMP, also cleaned by on_exit).
env_set_key() {
  local key="$1" value="$2"
  ENV_TMP="$(mktemp "$ENV_FILE.XXXXXX")" || return 1
  cp -p "$ENV_FILE" "$ENV_TMP" || { rm -f "$ENV_TMP"; return 1; }
  if grep -q "^${key}=" "$ENV_FILE"; then
    awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$ENV_FILE" > "$ENV_TMP" \
      || { rm -f "$ENV_TMP"; return 1; }
  else
    { cat "$ENV_FILE" && printf '%s=%s\n' "$key" "$value"; } > "$ENV_TMP" || { rm -f "$ENV_TMP"; return 1; }
  fi
  mv -f "$ENV_TMP" "$ENV_FILE" || { rm -f "$ENV_TMP"; return 1; }
  ENV_TMP=""
}

env_set_version() { env_set_key TCRM_VERSION "$1"; }


# ---- helpers --------------------------------------------------------------------------------------------

# compose [args...] runs Compose against the running version (.env); compose_at <version> pins it.
compose() {
  # A host that was never released has no TCRM_VERSION in .env yet: use the target so that Compose can interpolate.
  if [ -z "${TCRM_VERSION:-}" ] && [ -z "$(env_get TCRM_VERSION)" ]; then
    TCRM_VERSION="${TARGET_VERSION:-0.0.0}" docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
  else
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
  fi
}
compose_at() {
  local version="$1"
  shift
  TCRM_VERSION="$version" compose "$@"
}

registry() { env_get TCRM_IMAGE_REGISTRY; }
image_ref() { printf '%s/%s:%s' "$(registry)" "$1" "$2"; }

# Absolute path of TCRM_STATIC_DIR (a relative value is relative to deploy/, like Compose does).
static_dir() {
  local dir
  dir="$(env_get TCRM_STATIC_DIR)"
  dir="${dir:-./www}"
  case "$dir" in
    /*) printf '%s' "$dir" ;;
    *) printf '%s/%s' "$DEPLOY_DIR" "${dir#./}" ;;
  esac
}

# Directory name the "current" link points to ("" when there is no link).
current_target() {
  local link target
  link="$(static_dir)/current"
  if [ -L "$link" ]; then
    target="$(readlink "$link")"
    printf '%s' "${target##*/}"
  fi
}

container_id() { compose ps -q "$1" 2> /dev/null | head -n 1; }
container_any_id() { compose ps -a -q "$1" 2> /dev/null | head -n 1; }
image_id_of_ref() { docker image inspect --format '{{.Id}}' "$1" 2> /dev/null || true; }
image_id_of_container() { docker inspect --format '{{.Image}}' "$1" 2> /dev/null || true; }
short() { local v="${1#sha256:}"; printf '%s' "${v:0:12}"; }
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

record() { # <result> <detail>
  printf '%s %s %s prev=%s result=%s %s\n' "$(now_utc)" "$ACTION" "${TARGET_VERSION:--}" \
    "${PREVIOUS_VERSION:--}" "$1" "${2:-}" >> "$RELEASE_LOG"
}

require_tools() {
  local cmd
  for cmd in docker awk grep readlink; do
    command -v "$cmd" > /dev/null 2>&1 || die_early "缺少命令：$cmd"
  done
  docker compose version > /dev/null 2>&1 || die_early "缺少 Docker Compose 插件"
  docker info > /dev/null 2>&1 || die_early "Docker 没有在运行或当前用户无权使用"
  [ -f "$COMPOSE_FILE" ] || die_early "找不到 Compose 文件：$COMPOSE_FILE"
  [ -f "$ENV_FILE" ] || die_early "找不到 ${ENV_FILE}，请先按 .env.example 创建"
}

die_early() {
  printf '错误：%s\n' "$1" >&2
  REPORTED=1
  exit 1
}

check_env() {
  local key missing=""
  for key in $REQUIRED_ENV; do
    [ -n "$(env_get "$key")" ] || missing="$missing $key"
  done
  [ -z "$missing" ] || die_early ".env 缺少必填项：$missing"
  case "$(registry)" in
    */) die_early "TCRM_IMAGE_REGISTRY 末尾不能带斜杠" ;;
  esac
}

check_version() {
  local version="$1"
  printf '%s' "$version" | grep -Eq "$VERSION_PATTERN" \
    || die_early "版本号格式应为 主.次.修订（例如 1.0.1），收到：$version"
}

# ---- failure report -------------------------------------------------------------------------------------

print_state() {
  local static link
  static="$(static_dir)"
  log "  .env 中的版本 (TCRM_VERSION)：$(env_get TCRM_VERSION)"
  log "  current 指向：$(current_target)"
  local service id
  for service in crm-admin crm-redirect postgres redis nginx; do
    id="$(container_any_id "$service")"
    if [ -n "$id" ]; then
      log "  ${service}：$(docker inspect --format '{{.Config.Image}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$id" 2> /dev/null)"
    else
      log "  ${service}：没有容器"
    fi
  done
  link="$static/current"
  [ -L "$link" ] || log "  （尚无 current 链接）"
}

# Looks for the strict-mode Flyway refusal in the crm-admin log.
flyway_refused() {
  local logs
  # Into a variable first: "grep -q" closing the pipe early would fail the pipeline under pipefail.
  logs="$(compose logs --no-color --tail=1000 crm-admin 2> /dev/null || true)"
  printf '%s' "$logs" | grep -Eq 'applied migration not resolved locally|Detected applied migration|数据库结构比镜像新'
}

print_flyway_guidance() {
  cat >&2 << EOF

原因：数据库结构比 $TARGET_VERSION 的镜像新，Flyway 严格模式拒绝启动（crm-admin 日志出现
"applied migration not resolved locally"）。这是预期的保护，说明从较新的版本回退到不含全部迁移的镜像。
  - 恢复服务：重新发布较新的版本，release.sh deploy ${PREVIOUS_VERSION:-<较新的版本>}
  - 确需用旧镜像：按 deploy/README.md「发布与回滚」的应急方法处理（临时在 .env 设置
    TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:future' 后重建 crm-admin，事后删除）。
本脚本没有修改 TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS，也没有改动数据库。
EOF
}

report_failure() { # <message>
  REPORTED=1
  {
    printf '\n!! 发布中止（阶段：%s）：%s\n' "$STAGE" "$1"
    log "当前状态："
    print_state
    log "下一步建议："
    case "$STAGE_KIND" in
      prepare)
        log "  运行环境尚未被改动。按上面的原因修复后重新执行 release.sh $ACTION ${TARGET_VERSION:-<版本>}。"
        ;;
      admin)
        log "  crm-admin 已被替换但没有恢复健康，前端仍指向 $(current_target)，.env 版本未更新。"
        log "  1. 查看日志：docker compose -f $COMPOSE_FILE --env-file $ENV_FILE logs --tail=200 crm-admin"
        if [ -n "$PREVIOUS_VERSION" ] && [ "$PREVIOUS_VERSION" != "$TARGET_VERSION" ]; then
          log "  2. 回到上一个版本：release.sh deploy ${PREVIOUS_VERSION}（数据库不会自动回滚）"
        fi
        ;;
      redirect)
        log "  crm-admin 已是 ${TARGET_VERSION}，但 crm-redirect 没有恢复健康，前端仍指向 $(current_target)。"
        log "  查看日志：docker compose -f $COMPOSE_FILE --env-file $ENV_FILE logs --tail=200 crm-redirect，"
        log "  修复后重新执行 release.sh $ACTION ${TARGET_VERSION}。"
        ;;
      switch | record)
        log "  镜像已更新。重新执行 release.sh $ACTION $TARGET_VERSION 完成前端切换和记录（各步骤可重复执行）。"
        ;;
    esac
  } >&2
  if [ -n "${TARGET_VERSION:-}" ] && [ -n "$ACTION" ]; then
    record "failed" "stage=\"$STAGE\"" || true
  fi
}

on_exit() {
  # Capture the real exit status BEFORE releasing the lock (rmdir would overwrite $?).
  local rc=$?
  # L-071: drop the release lock only if this process created it (never steal another's).
  if [ "$LOCK_HELD" -eq 1 ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || true
    LOCK_HELD=0
  fi
  trap - EXIT
  [ -z "$ENV_TMP" ] || rm -f "$ENV_TMP"
  if [ "$rc" -ne 0 ] && [ "$REPORTED" -ne 1 ]; then
    report_failure "命令失败（退出码 ${rc}）"
  fi
  exit "$rc"
}

fail() { # <message>; reports and exits non-zero
  report_failure "$1"
  exit 1
}

# ---- waiting --------------------------------------------------------------------------------------------

# wait_healthy <service> <strict|lenient>
# strict: fail on "unhealthy" or when the container keeps restarting (a crashing new version).
# lenient (infrastructure): only the timeout fails, a restart is tolerated, because a first start on
# an empty data volume exits once and the restart policy heals it (L-061).
wait_healthy() {
  local service="$1" mode="$2" id base="" restarts status health deadline
  deadline=$((SECONDS + HEALTH_TIMEOUT))
  log "等待 $service 健康（最长 ${HEALTH_TIMEOUT} 秒）…"
  while [ "$SECONDS" -lt "$deadline" ]; do
    id="$(container_any_id "$service")"
    if [ -n "$id" ]; then
      status="$(docker inspect --format '{{.State.Status}}' "$id" 2> /dev/null || true)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2> /dev/null || true)"
      restarts="$(docker inspect --format '{{.RestartCount}}' "$id" 2> /dev/null || printf '0')"
      [ -n "$base" ] || base="$restarts"
      if [ "$status" = "running" ] && [ "$health" = "healthy" ]; then
        log "$service 已健康"
        return 0
      fi
      if [ "$mode" = "strict" ]; then
        [ "$health" != "unhealthy" ] || { log "$service 健康检查失败"; return 1; }
        if [ $((restarts - base)) -ge 2 ]; then
          log "$service 反复重启（$((restarts - base)) 次）"
          return 1
        fi
      fi
    fi
    sleep 2
  done
  # L-072: distinguish "still migrating" from "already exited".
  id="$(container_any_id "$service")"
  if [ -n "$id" ]; then
    status="$(docker inspect --format '{{.State.Status}}' "$id" 2> /dev/null || true)"
  else
    status=""
  fi
  if [ "$status" = "running" ]; then
    log "$service 在 ${HEALTH_TIMEOUT} 秒内没有变为健康，但容器仍在运行（迁移可能还没跑完）。"
    log "      可先查看日志，或把 TCRM_RELEASE_HEALTH_TIMEOUT（当前 ${HEALTH_TIMEOUT}，默认 300）调大后重跑；"
    log "      不要在容器还在跑时当成已失败去砍进程。"
  else
    log "$service 在 ${HEALTH_TIMEOUT} 秒内没有变为健康，且容器状态为 ${status:-不存在}：发布失败。"
    log "      查看日志后修复，再重跑；必要时调大 TCRM_RELEASE_HEALTH_TIMEOUT（当前 ${HEALTH_TIMEOUT}，默认 300）。"
  fi
  return 1
}

# ---- steps ----------------------------------------------------------------------------------------------

pull_images() { # <app-version>
  local name
  for name in crm-admin crm-redirect crm-static; do
    log "拉取 $(image_ref "$name" "$1")"
    docker pull --quiet "$(image_ref "$name" "$1")" > /dev/null || return 1
  done
  # L-070/L-073: pull crm-postgres only when deploy --postgres was given; rollback never pulls it.
  if [ -n "$UPDATE_POSTGRES_VERSION" ]; then
    log "拉取 $(image_ref crm-postgres "$UPDATE_POSTGRES_VERSION")（显式 --postgres）"
    docker pull --quiet "$(image_ref crm-postgres "$UPDATE_POSTGRES_VERSION")" > /dev/null || return 1
  fi
  # 首次发布：ensure_postgres_version_pinned 刚把 crm-postgres:<pin> 写进 .env，而主机上还没有
  # 任何 postgres 容器（含已停止的）。compose up 时才隐式拉取就太晚了，这里提前显式拉取同一 tag，
  # 让拉取失败发生在任何改动之前。已部署过的主机（postgres 容器存在，哪怕停止）不拉、不重建数据库。
  if [ -n "$POSTGRES_PIN_WRITTEN" ] && [ -z "$(container_any_id postgres)" ]; then
    log "拉取 $(image_ref crm-postgres "$POSTGRES_PIN_WRITTEN")（首次发布，预拉取数据库镜像）"
    docker pull --quiet "$(image_ref crm-postgres "$POSTGRES_PIN_WRITTEN")" > /dev/null || return 1
  fi
}

# Extracts the static image into releases/<version>/ (skipped when a complete copy exists).
extract_static() { # <version>
  local version="$1" root dest tmp cid
  root="$(static_dir)/releases"
  dest="$root/$version"
  mkdir -p "$root"
  if [ -f "$dest/.extracted" ]; then
    log "静态目录 releases/$version 已存在，跳过解出"
    return 0
  fi
  tmp="$(mktemp -d "$root/.incoming-$version.XXXXXX")"
  cid="$(docker create "$(image_ref crm-static "$version")")" || { rm -rf "$tmp"; return 1; }
  # Copy the two site directories only: "/." of a container would also copy its synthetic /dev, /proc, /etc.
  if ! docker cp "$cid:/admin" "$tmp/admin" || ! docker cp "$cid:/kol" "$tmp/kol"; then
    docker rm "$cid" > /dev/null 2>&1 || true
    rm -rf "$tmp"
    return 1
  fi
  docker rm "$cid" > /dev/null 2>&1 || true
  if [ ! -f "$tmp/admin/index.html" ] || [ ! -f "$tmp/kol/index.html" ]; then
    rm -rf "$tmp"
    log "静态镜像内容不完整（缺少 admin/index.html 或 kol/index.html）"
    return 1
  fi
  : > "$tmp/.extracted"
  chmod 755 "$tmp"
  rm -rf "$dest"
  mv "$tmp" "$dest"
  log "静态文件已解出到 $dest"
}

check_nginx_prerequisites() {
  local certs name
  certs="$(env_get TCRM_CERT_DIR)"
  certs="${certs:-./certs}"
  case "$certs" in
    /*) ;;
    *) certs="$DEPLOY_DIR/${certs#./}" ;;
  esac
  for name in origin.pem origin.key public.pem public.key; do
    [ -f "$certs/$name" ] || return 1
  done
}

# Runs pg-backup.sh; a failure stops the release. The first deployment has no database to back up.
backup_database() { # <env version for compose>
  if [ "$FIRST_DEPLOY" -eq 1 ]; then
    log "首次部署（没有 postgres 容器），跳过备份"
    return 0
  fi
  [ -n "$(container_id postgres)" ] || { log "postgres 容器没有运行，无法备份。先启动它再发布。"; return 1; }
  log "发布前备份：$BACKUP_COMMAND"
  TCRM_VERSION="$1" TCRM_ENV_FILE="$ENV_FILE" TCRM_COMPOSE_FILE="$COMPOSE_FILE" "$BACKUP_COMMAND"
}

# A release trace: the "current" link exists or the release log holds a successful release.
has_release_trace() {
  if [ -L "$(static_dir)/current" ]; then
    return 0
  fi
  if [ -f "$RELEASE_LOG" ] && grep -q ' result=ok' "$RELEASE_LOG"; then
    return 0
  fi
  return 1
}

# First deployment = no postgres container AND no release trace. An already released host whose
# containers are gone (docker compose down) is NOT a first deployment: it has data to back up.
is_first_deploy() {
  [ -z "$(container_any_id postgres)" ] && ! has_release_trace
}

check_backup_prerequisites() {
  local file
  if is_first_deploy; then
    return 0
  fi
  command -v gpg > /dev/null 2>&1 || die_early "缺少 gpg：pg-backup.sh 需要它加密备份（deploy/README.md「备份与恢复」）"
  file="$(env_get TCRM_BACKUP_PASSPHRASE_FILE)"
  [ -s "$file" ] || die_early "TCRM_BACKUP_PASSPHRASE_FILE 指向的文件不存在或为空：$file"
}

start_infrastructure() { # <version>
  if [ -z "$(container_id postgres)" ] || [ -z "$(container_id redis)" ]; then
    log "启动 postgres 和 redis"
    compose_at "$1" up -d --no-deps postgres redis > /dev/null || return 1
  fi
  wait_healthy postgres lenient || return 1
  wait_healthy redis lenient || return 1
}

# Starts <service> at <version> (no dependencies, nothing else is touched) and waits for it.
update_service() { # <service> <version>
  compose_at "$2" up -d --no-deps "$1" || return 1
  wait_healthy "$1" strict
}

# Updates crm-redirect only when its image differs from the running container's.
update_redirect() { # <version>
  local id wanted running
  wanted="$(image_id_of_ref "$(image_ref crm-redirect "$1")")"
  id="$(container_id crm-redirect)"
  running=""
  [ -z "$id" ] || running="$(image_id_of_container "$id")"
  if [ -n "$id" ] && [ "$wanted" = "$running" ]; then
    log "crm-redirect 镜像摘要未变化（$(short "$wanted")），不重启"
    REDIRECT_RESULT="unchanged"
    return 0
  fi
  if [ -z "$id" ]; then
    log "crm-redirect 尚未运行，启动 $(short "$wanted")"
    REDIRECT_RESULT="started"
  else
    log "crm-redirect 镜像变化：$(short "$running") -> $(short "$wanted")，更新（跳转短暂不可用）"
  fi
  STAGE_KIND="redirect"
  update_service crm-redirect "$1"
  [ "$REDIRECT_RESULT" = "started" ] || REDIRECT_RESULT="restarted"
}


# L-074: keep previous release asset files the new build does not ship, so open tabs can still
# lazy-load the old chunk. Only the immediate previous version; never overwrite same-named files.
preserve_previous_assets() { # <new_version>
  local static prev new site from to
  static="$(static_dir)"
  prev="$(current_target)"
  new="$1"
  [ -n "$prev" ] || return 0
  [ "$prev" != "$new" ] || return 0
  [ -d "$static/releases/$prev" ] || return 0
  [ -d "$static/releases/$new" ] || return 0
  for site in admin kol; do
    from="$static/releases/$prev/$site/assets"
    to="$static/releases/$new/$site/assets"
    [ -d "$from" ] || continue
    mkdir -p "$to"
    # portable: no mapfile; copy missing files only
    find "$from" -type f -print | while IFS= read -r f; do
      rel="${f#"$from"/}"
      if [ ! -e "$to/$rel" ]; then
        mkdir -p "$to/$(dirname "$rel")"
        cp -p "$f" "$to/$rel"
      fi
    done
  done
  log "已把上一版 releases/$prev 中新版本没有的 assets 补进 releases/$new"
}

# Atomic switch: write the link under a temporary name, then rename it over "current".
switch_current() { # <version>
  local static tmp
  static="$(static_dir)"
  tmp="$static/.current.new"
  ln -sfn "releases/$1" "$tmp"
  if mv --version > /dev/null 2>&1; then
    mv -T "$tmp" "$static/current" # GNU coreutils (the production host)
  else
    mv -fh "$tmp" "$static/current" # BSD mv (macOS, local verification)
  fi
  touch "$static/releases/$1"
}

prune_releases() {
  local root current name count=0 victim
  root="$(static_dir)/releases"
  current="$(current_target)"
  # Newest first by modification time; never delete the live version or the previous one.
  # shellcheck disable=SC2045 # names are restricted to the version pattern below
  for name in $(ls -1t "$root" 2> /dev/null); do
    printf '%s' "$name" | grep -Eq "$VERSION_PATTERN" || continue
    count=$((count + 1))
    [ "$count" -gt "$KEEP_RELEASES" ] || continue
    if [ "$name" = "$current" ] || [ "$name" = "$PREVIOUS_VERSION" ]; then
      continue
    fi
    victim="$root/$name"
    log "删除较旧的静态版本 releases/$name"
    rm -rf "$victim"
  done
}

notice_postgres_image() {
  local id wanted running pg_ver
  id="$(container_id postgres)"
  [ -n "$id" ] || return 0
  pg_ver="$(env_get TCRM_POSTGRES_VERSION)"
  [ -n "$pg_ver" ] || return 0
  wanted="$(image_id_of_ref "$(image_ref crm-postgres "$pg_ver")")"
  running="$(image_id_of_container "$id")"
  if [ -n "$wanted" ] && [ "$wanted" != "$running" ]; then
    log "提示：.env 中 TCRM_POSTGRES_VERSION=$pg_ver 与运行中的数据库镜像不同（$(short "$running") -> $(short "$wanted")）。"
    log "      日常发布不重启数据库；低峰期手动：docker compose -f $COMPOSE_FILE --env-file $ENV_FILE up -d --no-deps postgres"
  fi
}

# Shared by deploy and rollback once the images and static files are ready.
apply_version() { # <version>
  local version="$1" admin_id
  step "更新 crm-admin（管理端接口会中断 30～60 秒）"
  STAGE_KIND="admin"
  if ! update_service crm-admin "$version"; then
    if flyway_refused; then
      report_failure "crm-admin 没有恢复健康（Flyway 严格模式拒绝启动）"
      print_flyway_guidance
    else
      compose logs --no-color --tail=30 crm-admin >&2 || true
      report_failure "crm-admin 没有恢复健康，前端没有切换"
    fi
    exit 1
  fi
  step "更新 crm-redirect（仅镜像摘要变化时）"
  STAGE_KIND="redirect"
  update_redirect "$version" || fail "crm-redirect 没有恢复健康"
  step "切换前端 current -> releases/$version"
  STAGE_KIND="switch"
  preserve_previous_assets "$version"
  switch_current "$version"
  if [ -z "$(container_id nginx)" ]; then
    check_nginx_prerequisites || fail "缺少证书文件（origin.pem、origin.key、public.pem、public.key），Nginx 未启动。先按 README 生成证书。"
    compose_at "$version" up -d --no-deps nginx > /dev/null || fail "Nginx 启动失败"
  fi
  step "写入 .env 和发布记录"
  STAGE_KIND="record"
  env_set_version "$version"
  if [ -n "$UPDATE_POSTGRES_VERSION" ]; then
    env_set_key TCRM_POSTGRES_VERSION "$UPDATE_POSTGRES_VERSION"
    log "已写入 TCRM_POSTGRES_VERSION=${UPDATE_POSTGRES_VERSION}（数据库容器需低峰期手动 up -d --no-deps postgres）"
  fi
  admin_id="$(image_id_of_container "$(container_id crm-admin)")"
  record "ok" "admin=$(short "$admin_id") redirect=$REDIRECT_RESULT"
  prune_releases
  notice_postgres_image
}

REDIRECT_RESULT="unchanged"

prepare_images() { # <version>
  step "拉取镜像"
  pull_images "$1" || fail "镜像拉取失败（检查 TCRM_IMAGE_REGISTRY、docker login ghcr.io 和网络）"
  step "解出静态文件"
  extract_static "$1" || fail "静态文件解出失败"
}

# The version a host without TCRM_POSTGRES_VERSION gets pinned to (L-073): the current TCRM_VERSION,
# or the deploy target on a host whose .env has no version yet. pull_images pulls exactly this tag
# on a first release, so both places agree on the pinned version.
postgres_pin_version() {
  local pin
  pin="$(env_get TCRM_VERSION)"
  [ -n "$pin" ] || pin="$TARGET_VERSION"
  printf '%s' "$pin"
}

# Pin TCRM_POSTGRES_VERSION in .env once so later TCRM_VERSION bumps cannot recreate postgres (L-073).
ensure_postgres_version_pinned() {
  local pin
  if [ -n "$(env_get TCRM_POSTGRES_VERSION)" ]; then
    return 0
  fi
  pin="$(postgres_pin_version)"
  env_set_key TCRM_POSTGRES_VERSION "$pin"
  POSTGRES_PIN_WRITTEN="$pin" # 首次发布时 pull_images 据此预拉取 crm-postgres
  log "已写入 TCRM_POSTGRES_VERSION=${pin}（兼容旧主机；日常发布不再改动）"
}

# L-069: surface an emergency Flyway ignore left in .env so strict mode is not silently off.
warn_flyway_ignore_if_set() { # <when>
  local patterns
  patterns="$(env_get TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS)"
  [ -n "$patterns" ] || return 0
  log "!!!!!!!! 注意（$1）：.env 仍设置 TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS=$patterns"
  log "!!!!!!!! Flyway 严格模式处于关闭状态；应急回滚结束后应删除该变量。"
}

cmd_deploy() {
  local version="$1"
  ACTION="deploy"
  TARGET_VERSION="$version"
  check_env
  ensure_postgres_version_pinned
  warn_flyway_ignore_if_set "发布开始"
  check_backup_prerequisites
  PREVIOUS_VERSION="$(env_get TCRM_VERSION)"
  if is_first_deploy; then
    FIRST_DEPLOY=1
    PREVIOUS_VERSION="" # an unreleased host: TCRM_VERSION in .env is only the .env.example placeholder
  elif [ -z "$(container_id postgres)" ]; then
    die_early "这台主机已经发布过，但 postgres 没有运行：没有备份不能发布。先启动数据库（docker compose -f $COMPOSE_FILE --env-file $ENV_FILE up -d --no-deps postgres redis），确认健康后重新执行。环境未改动。"
  fi
  log "发布 ${PREVIOUS_VERSION:-（首次）} -> $version"
  mkdir -p "$(static_dir)/releases"
  prepare_images "$version"
  step "发布前备份"
  backup_database "${PREVIOUS_VERSION:-$version}" || fail "备份失败，发布已中止，环境未改动"
  if [ "$FIRST_DEPLOY" -eq 1 ]; then
    step "启动 postgres 和 redis"
    start_infrastructure "$version" || fail "postgres 或 redis 没有变为健康"
  fi
  apply_version "$version"
  step "完成"
  warn_flyway_ignore_if_set "发布结束"
  cmd_status
}

cmd_rollback() {
  local version="$1"
  ACTION="rollback"
  TARGET_VERSION="$version"
  UPDATE_POSTGRES_VERSION="" # L-070: rollback never pulls or rewrites the postgres image tag
  check_env
  ensure_postgres_version_pinned
  PREVIOUS_VERSION="$(env_get TCRM_VERSION)"
  [ -n "$PREVIOUS_VERSION" ] || die_early "回滚需要 .env 中已有 TCRM_VERSION（尚未发布过任何版本）"
  log "回滚 ${PREVIOUS_VERSION} -> ${version}（只切换应用镜像和前端，不拉、不改数据库镜像，不自动备份）"
  [ -n "$(container_id postgres)" ] || die_early "postgres 没有运行，无法回滚"
  mkdir -p "$(static_dir)/releases"
  prepare_images "$version"
  apply_version "$version"
  step "完成"
  cmd_status
}

cmd_status() {
  local static service id name line
  require_tools
  static="$(static_dir)"
  log ""
  log "当前版本（.env TCRM_VERSION）：$(env_get TCRM_VERSION)"
  if [ -L "$static/current" ]; then
    log "current -> $(readlink "$static/current")"
  else
    log "current：未设置"
  fi
  # shellcheck disable=SC2010 # only names matching the version pattern are shown
  log "静态版本：$(ls -1t "$static/releases" 2> /dev/null | grep -E "$VERSION_PATTERN" | tr '\n' ' ')"
  log ""
  log "服务              镜像                                            镜像摘要(前12位)  状态"
  for service in nginx crm-admin crm-redirect postgres redis; do
    id="$(container_any_id "$service")"
    if [ -z "$id" ]; then
      printf '%-17s %s\n' "$service" "（没有容器）"
      continue
    fi
    name="$(docker inspect --format '{{.Config.Image}}' "$id")"
    printf '%-17s %-47s %-17s %s\n' "$service" "$name" "$(short "$(image_id_of_container "$id")")" \
      "$(docker inspect --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$id")"
  done
  if [ -f "$RELEASE_LOG" ]; then
    log ""
    log "最近的发布记录（${RELEASE_LOG}）："
    while IFS= read -r line; do log "  $line"; done < <(tail -n 5 "$RELEASE_LOG")
  fi
}

acquire_release_lock() {
  # L-071: mkdir is atomic on macOS and Linux; on_exit removes the directory only if we created it.
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    die_early "另一个 release.sh 正在运行（锁目录 ${LOCK_DIR} 已存在）。确认无并发后再删掉该目录重试。"
  fi
  LOCK_HELD=1
}

main() {
  local command="${1:-}"
  case "$command" in
    deploy)
      [ "$#" -ge 2 ] || usage
      ;;
    rollback)
      [ "$#" -eq 2 ] || usage
      ;;
    status)
      [ "$#" -eq 1 ] || usage
      ;;
    *) usage ;;
  esac
  trap on_exit EXIT
  require_tools
  case "$command" in
    deploy | rollback)
      local version="${2#v}"
      shift 2
      UPDATE_POSTGRES_VERSION=""
      if [ "$command" = "deploy" ]; then
        while [ "$#" -gt 0 ]; do
          case "$1" in
            --postgres)
              [ "$#" -ge 2 ] || die_early "--postgres 需要一个版本号"
              UPDATE_POSTGRES_VERSION="${2#v}"
              check_version "$UPDATE_POSTGRES_VERSION"
              shift 2
              ;;
            *) usage ;;
          esac
        done
      elif [ "$#" -gt 0 ]; then
        usage
      fi
      check_version "$version"
      acquire_release_lock
      "cmd_$command" "$version"
      ;;
    status) cmd_status ;;
  esac
}

# Run only when executed, so the functions above can be sourced by tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
