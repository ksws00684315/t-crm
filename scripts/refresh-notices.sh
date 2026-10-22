#!/usr/bin/env bash
# Regenerate THIRD-PARTY-NOTICES for every deliverable project.
# Required after Dependabot (or any lockfile / pom dependency) bumps — see docs/05-工程规范/07-依赖规范.md §4.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH:-}"

refresh_frontend() {
  local dir="$1"
  echo "==> $dir: pnpm notices"
  (cd "$ROOT/$dir" && pnpm notices)
}

refresh_maven() {
  local dir="$1"
  echo "==> $dir: Maven inventory + generate-notices.py"
  (
    cd "$ROOT/$dir"
    mvn -B -q generate-resources dependency:build-classpath \
      -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
    python3 "$ROOT/scripts/generate-notices.py" .
  )
}

refresh_frontend admin-ui
refresh_frontend kol-portal
refresh_maven backend
refresh_maven redirect

echo "Done. Review and commit any changes to */THIRD-PARTY-NOTICES."
