#!/usr/bin/env bash
# Run one shell command on the production host through AWS SSM Run Command and print its output.
# The command runs as root with /opt/tcrm as working directory. Callers pass read-only commands
# only, unless the owner approved a change in chat (see ../SKILL.md, section "动作边界").
#
# Usage: prod-run.sh '<command>'
# Env:   TCRM_AWS_PROFILE      (default tcrm-prod)
#        TCRM_AWS_REGION       (default us-east-1)
#        TCRM_PROD_NAME_TAG    (default SparkTrack-Prod)
#        TCRM_PROD_INSTANCE_ID (skips the lookup by Name tag)
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 '<command>'" >&2; exit 2; }
profile="${TCRM_AWS_PROFILE:-tcrm-prod}"
region="${TCRM_AWS_REGION:-us-east-1}"
aws_() { aws --profile "$profile" --region "$region" "$@"; }

instance="${TCRM_PROD_INSTANCE_ID:-}"
if [ -z "$instance" ]; then
  instance="$(aws_ ec2 describe-instances \
    --filters "Name=tag:Name,Values=${TCRM_PROD_NAME_TAG:-SparkTrack-Prod}" \
    "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].InstanceId' --output text)"
fi
if ! [[ "$instance" =~ ^i-[0-9a-f]+$ ]]; then
  echo "production instance not found or ambiguous (got: '$instance')" >&2
  exit 3
fi

params="$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]],"workingDirectory":["/opt/tcrm"],"executionTimeout":["300"]}))' "$1")"
cmd_id="$(aws_ ssm send-command --instance-ids "$instance" --document-name AWS-RunShellScript \
  --comment prod-troubleshoot --parameters "$params" --query Command.CommandId --output text)"

# Poll up to ~5 minutes (the built-in waiter gives up after 100 seconds).
status=Pending
for _ in $(seq 1 100); do
  status="$(aws_ ssm get-command-invocation --command-id "$cmd_id" --instance-id "$instance" \
    --query Status --output text 2> /dev/null || echo Pending)"
  case "$status" in
    Pending | InProgress | Delayed) sleep 3 ;;
    *) break ;;
  esac
done

aws_ ssm get-command-invocation --command-id "$cmd_id" --instance-id "$instance" \
  --query StandardOutputContent --output text
err="$(aws_ ssm get-command-invocation --command-id "$cmd_id" --instance-id "$instance" \
  --query StandardErrorContent --output text)"
if [ -n "$err" ]; then printf '%s\n%s\n' "--- stderr ---" "$err" >&2; fi
echo "--- status: $status (command $cmd_id; SSM truncates output beyond 24000 characters) ---" >&2
[ "$status" = Success ]
