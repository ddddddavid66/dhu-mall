#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/yueji-ops-common.sh"
[[ "${1:-}" == --confirm-app-only-rollback ]] || { echo 'requires --confirm-app-only-rollback' >&2; exit 2; }
[[ "${DEPLOY_ENV:-}" == test ]] || yueji_die 'application rollback is restricted to DEPLOY_ENV=test'
yueji_init
[[ "$(yueji_env DEPLOY_ENV || true)" == test ]] || yueji_die 'protected runtime env must also declare DEPLOY_ENV=test'
[[ "$(yueji_env YUEJI_ALLOW_APP_ROLLBACK || true)" == true ]] || yueji_die 'operator must set YUEJI_ALLOW_APP_ROLLBACK=true after reviewing forward-schema compatibility'
previous="$YUEJI_PROJECT_ROOT/releases/previous.env"
[[ -f "$previous" ]] || yueji_die 'previous immutable image refs are unavailable'
backend=$(awk -F= '$1=="BACKEND_IMAGE" {sub(/^[^=]*=/, ""); print; exit}' "$previous")
frontend=$(awk -F= '$1=="FRONTEND_IMAGE" {sub(/^[^=]*=/, ""); print; exit}' "$previous")
repo=$(yueji_env IMAGE_REPOSITORY) || yueji_die 'IMAGE_REPOSITORY is required'
[[ "$backend" == "$repo/backend@sha256:"* && "${backend##*@sha256:}" =~ ^[a-f0-9]{64}$ ]] || yueji_die 'previous backend digest is invalid'
[[ "$frontend" == "$repo/frontend@sha256:"* && "${frontend##*@sha256:}" =~ ^[a-f0-9]{64}$ ]] || yueji_die 'previous frontend digest is invalid'
APP_HOSTNAME=$(yueji_env APP_HOSTNAME) || yueji_die 'APP_HOSTNAME is required'
APP_BASE_URL=$(yueji_env APP_BASE_URL) || yueji_die 'APP_BASE_URL is required'
[[ "$APP_BASE_URL" == "https://$APP_HOSTNAME" ]] || yueji_die 'APP_BASE_URL does not match APP_HOSTNAME'

exec 9>/run/lock/yueji/test-deploy.lock
flock -n 9 || yueji_die 'another deployment/recovery operation is in progress'
record_dir="$YUEJI_PROJECT_ROOT/records"
install -d -m 0700 "$record_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
rollback_env=$(mktemp "$(dirname -- "$YUEJI_RUNTIME_ENV")/.yueji-test-rollback.XXXXXX")
awk -v backend="$backend" -v frontend="$frontend" '
  /^BACKEND_IMAGE=/ { print "BACKEND_IMAGE=" backend; next }
  /^FRONTEND_IMAGE=/ { print "FRONTEND_IMAGE=" frontend; next }
  { print }
' "$YUEJI_RUNTIME_ENV" > "$rollback_env"
chmod 0600 "$rollback_env"
docker compose --project-directory "$YUEJI_PROJECT_ROOT" --env-file "$rollback_env" -f "$YUEJI_COMPOSE_FILE" pull backend web
yueji_dc stop web backend || true
mv "$rollback_env" "$YUEJI_RUNTIME_ENV"
yueji_dc up -d backend web

backend_id=$(yueji_dc ps -q backend)
web_id=$(yueji_dc ps -q web)
ready=false
for _ in $(seq 1 120); do
  b=$(docker inspect --format '{{.State.Health.Status}}' "$backend_id" 2>/dev/null || true)
  w=$(docker inspect --format '{{.State.Health.Status}}' "$web_id" 2>/dev/null || true)
  if [[ "$b" == healthy && "$w" == healthy ]]; then ready=true; break; fi
  sleep 1
done
smoke=false
if [[ "$ready" == true ]] && APP_HOSTNAME="$APP_HOSTNAME" APP_BASE_URL="$APP_BASE_URL" bash "$SCRIPT_DIR/smoke-stage-i-linux.sh" > "$record_dir/test-app-rollback-smoke-$stamp.log" 2>&1; then
  smoke=true
  chmod 0600 "$record_dir/test-app-rollback-smoke-$stamp.log"
fi
python3 - "$record_dir/test-app-rollback-$stamp.json" "$backend" "$frontend" "$ready" "$smoke" <<'PY'
import json, pathlib, sys, time
path, backend, frontend, ready, smoke = sys.argv[1:]
record = {"environment":"test", "result":"APP_ROLLBACK_ATTEMPTED",
          "backendImage":backend, "frontendImage":frontend,
          "readinessPassed":ready == "true", "smokePassed":smoke == "true",
          "database":"left at its forward migration; no destructive rollback",
          "recordedAtEpoch":int(time.time())}
pathlib.Path(path).write_text(json.dumps(record, indent=2)+"\n", encoding="utf-8")
PY
chmod 0600 "$record_dir/test-app-rollback-$stamp.json"
[[ "$ready" == true && "$smoke" == true ]] || yueji_die "application rollback did not pass readiness/smoke; inspect $record_dir/test-app-rollback-$stamp.json"
printf 'APP_ONLY_ROLLBACK_COMPLETE %s\n' "$record_dir/test-app-rollback-$stamp.json"
