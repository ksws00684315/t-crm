#!/usr/bin/env bash
# Checks the retention rule of pg-backup.sh: keep 7 days of dumps plus Sunday dumps for 28 days.
set -euo pipefail
# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/pg-backup.sh"

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
# "Today" is Monday 2026-10-05; the Sundays are 09-27, 09-20, 09-13, 09-06 and 08-30.
for day in 20261005 20261004 20261003 20261002 20261001 20260930 20260929 20260928 \
           20260927 20260926 20260920 20260919 20260913 20260906 20260830 20260801; do
  : > "$dir/$(backup_name "${day}T030000Z")"
done

actual="$(expired_backups "$dir" 20261005 | xargs -n1 basename | sort)"
expected="$(printf 'tcrm-%sT030000Z.dump\n' 20260801 20260830 20260906 20260919 20260926 | sort)"
if [ "$actual" != "$expected" ]; then
  printf 'unexpected expiry list\n--- expected\n%s\n--- actual\n%s\n' "$expected" "$actual" >&2
  exit 1
fi
echo "retention rule OK"
