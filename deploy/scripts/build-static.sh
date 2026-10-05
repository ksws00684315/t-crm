#!/usr/bin/env bash
# Builds both front ends and lays out the Nginx static directories:
#   <out>/admin   admin-ui dist      <out>/kol   kol-portal dist
# Each directory also gets licenses/<project>.txt with the third-party notices of backend, redirect,
# admin-ui and kol-portal (ADR-006). <out> is $TCRM_STATIC_DIR (default deploy/www).
# Needs Node.js (see .nvmrc of each project) and pnpm.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${TCRM_STATIC_DIR:-$ROOT/deploy/www}"

build_front_end() { # <project dir> <target dir name>
  local project="$1" target="$2"
  (cd "$ROOT/$project" && pnpm install --frozen-lockfile && pnpm build)
  rm -rf "${OUT:?}/$target"
  mkdir -p "$OUT/$target/licenses"
  cp -R "$ROOT/$project/dist/." "$OUT/$target/"
  for notice in backend redirect admin-ui kol-portal; do
    cp "$ROOT/$notice/THIRD-PARTY-NOTICES" "$OUT/$target/licenses/$notice.txt"
  done
}

build_front_end admin-ui admin
build_front_end kol-portal kol
echo "static files written to $OUT"
