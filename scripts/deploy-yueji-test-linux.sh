#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/yueji-ops-common.sh"
[[ "${DEPLOY_ENV:-}" == test ]] || yueji_die 'this deploy command is restricted to DEPLOY_ENV=test'
yueji_init
[[ "$(yueji_env DEPLOY_ENV || true)" == test ]] || yueji_die 'protected runtime env must also declare DEPLOY_ENV=test'
command -v flock >/dev/null || yueji_die 'flock is required'
command -v curl >/dev/null || yueji_die 'curl is required'
command -v python3 >/dev/null || yueji_die 'python3 is required'

backend_image=''
frontend_image=''
maintenance_window_id=''
release_run_id=''
release_head_sha=''
IFS= read -r backend_image || yueji_die 'missing backend digest on stdin'
IFS= read -r frontend_image || yueji_die 'missing frontend digest on stdin'
IFS= read -r maintenance_window_id || yueji_die 'operator-approved maintenance window ID required on stdin'
IFS= read -r release_run_id || yueji_die 'quality-release run ID required on stdin'
IFS= read -r release_head_sha || yueji_die 'quality-release commit SHA required on stdin'
[[ -z "$(cat)" ]] || yueji_die 'unexpected extra deployment input'
[[ "$maintenance_window_id" =~ ^[A-Za-z0-9._-]{4,80}$ ]] || yueji_die 'invalid maintenance window/change record ID'
[[ "$release_run_id" =~ ^[0-9]+$ && "$release_head_sha" =~ ^[a-f0-9]{40}$ ]] || yueji_die 'invalid release run provenance'
repo=$(yueji_env IMAGE_REPOSITORY) || yueji_die 'IMAGE_REPOSITORY is required'
[[ "$repo" =~ ^ghcr\.io/[a-z0-9._-]+/[a-z0-9._-]+$ ]] || yueji_die 'invalid IMAGE_REPOSITORY'
valid_image() { [[ "$1" =~ ^${repo//./\.}/(backend|frontend)@sha256:[a-f0-9]{64}$ ]]; }
valid_image "$backend_image" && [[ "$backend_image" == "$repo/backend@sha256:"* ]] || yueji_die 'backend image must be the expected immutable GHCR digest'
valid_image "$frontend_image" && [[ "$frontend_image" == "$repo/frontend@sha256:"* ]] || yueji_die 'frontend image must be the expected immutable GHCR digest'
host=$(yueji_env APP_HOSTNAME) || yueji_die 'APP_HOSTNAME is required'
base=$(yueji_env APP_BASE_URL) || yueji_die 'APP_BASE_URL is required'
[[ "$host" =~ ^[A-Za-z0-9.-]+$ && "$base" == "https://$host" ]] || yueji_die 'APP_BASE_URL must match the exact HTTPS hostname'

lock_dir=/run/lock/yueji
mkdir -p -m 0755 "$lock_dir"
exec 9>"$lock_dir/test-deploy.lock"
flock -n 9 || yueji_die 'another test deployment is in progress'
release_dir="$YUEJI_PROJECT_ROOT/releases"
record_dir="$YUEJI_PROJECT_ROOT/records"
install -d -m 0700 "$release_dir" "$record_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
record="$record_dir/test-deploy-$stamp.json"
previous="$release_dir/previous.env"

current_backend=$(yueji_env BACKEND_IMAGE || true)
current_frontend=$(yueji_env FRONTEND_IMAGE || true)
previous_available=false
if [[ "$current_backend" == "$repo/backend@sha256:"* && "$current_frontend" == "$repo/frontend@sha256:"* ]] \
  && [[ "${current_backend##*@sha256:}" =~ ^[a-f0-9]{64}$ && "${current_frontend##*@sha256:}" =~ ^[a-f0-9]{64}$ ]]; then
  printf 'BACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\nDEPLOYED_AT_UTC=%s\n' \
    "$current_backend" "$current_frontend" "$stamp" > "$previous"
  chmod 0600 "$previous"
  previous_available=true
fi

candidate_env=$(mktemp "$(dirname -- "$YUEJI_RUNTIME_ENV")/.yueji-test-next.XXXXXX")
trap 'rm -f -- "$candidate_env"' EXIT
chmod 0600 "$candidate_env"
awk -v backend="$backend_image" -v frontend="$frontend_image" '
  BEGIN { seen_b = 0; seen_f = 0 }
  /^BACKEND_IMAGE=/ { print "BACKEND_IMAGE=" backend; seen_b = 1; next }
  /^FRONTEND_IMAGE=/ { print "FRONTEND_IMAGE=" frontend; seen_f = 1; next }
  { print }
  END { if (!seen_b || !seen_f) exit 9 }
' "$YUEJI_RUNTIME_ENV" > "$candidate_env" || yueji_die 'runtime env must define both image keys'

dc_with_env() {
  docker compose --project-directory "$YUEJI_PROJECT_ROOT" --env-file "$1" -f "$YUEJI_COMPOSE_FILE" "${@:2}"
}
dc_with_env "$candidate_env" config --quiet || yueji_die 'candidate Compose configuration is invalid'
dc_with_env "$candidate_env" pull backend web
yueji_dc up -d mysql
mysql_id=$(yueji_dc ps -q mysql)
[[ -n "$mysql_id" ]] || yueji_die 'MySQL container failed to start'
for _ in $(seq 1 120); do
  [[ "$(docker inspect --format '{{.State.Health.Status}}' "$mysql_id" 2>/dev/null || true)" == healthy ]] && break
  sleep 1
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$mysql_id" 2>/dev/null || true)" == healthy ]] || yueji_die 'MySQL readiness timed out before maintenance'

# The maintenance window starts before the backup and forward-only Flyway migration.
yueji_dc stop web backend || true
backup_error_log="$record_dir/test-deploy-$stamp-backup.log"
if backup_result=$(DEPLOY_ENV=test YUEJI_PROJECT_ROOT="$YUEJI_PROJECT_ROOT" YUEJI_RUNTIME_ENV="$YUEJI_RUNTIME_ENV" \
  YUEJI_COMPOSE_FILE="$YUEJI_COMPOSE_FILE" bash "$SCRIPT_DIR/backup-yueji-linux.sh" --confirm-maintenance 2>"$backup_error_log"); then
  chmod 0600 "$backup_error_log"
else
  chmod 0600 "$backup_error_log"
  python3 - "$record" "$backend_image" "$frontend_image" "$maintenance_window_id" "$release_run_id" "$release_head_sha" "$backup_error_log" <<'PY'
import json, pathlib, sys, time
path, backend, frontend, maintenance, run_id, head_sha, error_log = sys.argv[1:]
record = {"environment":"test", "result":"FAILED", "phase":"backup",
          "backendImage":backend, "frontendImage":frontend,
          "qualityReleaseRunId":run_id, "gitSha":head_sha,
          "maintenanceWindowId":maintenance, "failureLog":error_log,
          "databaseAction":"migration not started; no database cleanup or reverse DDL",
          "recordedAtEpoch":int(time.time())}
pathlib.Path(path).write_text(json.dumps(record, indent=2)+"\n", encoding="utf-8")
PY
  chmod 0600 "$record"
  yueji_die "backup failed; web/backend remain stopped, migration did not start; inspect $record and $backup_error_log"
fi
rm -f -- "$backup_error_log"
runtime_next="$(dirname -- "$YUEJI_RUNTIME_ENV")/.yueji-test-active.$stamp"
mv "$candidate_env" "$runtime_next"
chmod 0600 "$runtime_next"
mv "$runtime_next" "$YUEJI_RUNTIME_ENV"

save_failure() {
  local phase="$1" logs="$record_dir/test-deploy-$stamp-$phase.log"
  yueji_dc logs --no-color --tail=500 backend web > "$logs" 2>&1 || true
  chmod 0600 "$logs"
  python3 - "$record" "$phase" "$backend_image" "$frontend_image" "$previous_available" "$backup_result" "$logs" "$maintenance_window_id" "$release_run_id" "$release_head_sha" <<'PY'
import json, pathlib, sys, time
path, phase, backend, frontend, previous, backup, logs, maintenance, run_id, head_sha = sys.argv[1:]
record = {"environment":"test", "result":"FAILED", "phase":phase,
          "backendImage":backend, "frontendImage":frontend,
          "qualityReleaseRunId":run_id, "gitSha":head_sha,
          "previousReleaseAvailable":previous == "true", "backupResult":backup,
          "failureLogs":logs, "databaseAction":"no rollback or destructive database action",
          "maintenanceWindowId":maintenance,
          "recordedAtEpoch":int(time.time())}
pathlib.Path(path).write_text(json.dumps(record, indent=2)+"\n", encoding="utf-8")
PY
}

if ! yueji_dc up -d backend; then
  save_failure backend-start
  yueji_die "backend startup failed; migration state is unknown; web remains stopped; inspect $record"
fi
backend_id=$(yueji_dc ps -q backend)
backend_ready=false
for _ in $(seq 1 120); do
  health=$(docker inspect --format '{{.State.Health.Status}}' "$backend_id" 2>/dev/null || true)
  [[ "$health" == healthy ]] && { backend_ready=true; break; }
  [[ "$health" == unhealthy ]] && break
  sleep 1
done
if [[ "$backend_ready" != true ]]; then
  save_failure backend-readiness
  yueji_dc stop backend || true
  yueji_die "backend readiness failed; migration state is unknown; web remains stopped; inspect $record"
fi
if ! yueji_dc up -d web; then
  save_failure web-start
  yueji_dc stop web backend || true
  yueji_die "web startup failed; services remain in maintenance; inspect $record"
fi
web_id=$(yueji_dc ps -q web)
web_ready=false
for _ in $(seq 1 120); do
  health=$(docker inspect --format '{{.State.Health.Status}}' "$web_id" 2>/dev/null || true)
  [[ "$health" == healthy ]] && { web_ready=true; break; }
  [[ "$health" == unhealthy ]] && break
  sleep 1
done
if [[ "$web_ready" != true ]]; then
  save_failure web-readiness
  yueji_dc stop web backend || true
  yueji_die "web readiness failed; services remain in maintenance; inspect $record"
fi

smoke_file="$record_dir/test-smoke-$stamp.log"
if ! APP_HOSTNAME="$host" APP_BASE_URL="$base" bash "$SCRIPT_DIR/smoke-stage-i-linux.sh" > "$smoke_file" 2>&1; then
  chmod 0600 "$smoke_file"
  save_failure smoke
  rollback_allowed=$(yueji_env YUEJI_ALLOW_APP_ROLLBACK || true)
  if [[ "$previous_available" == true && "$rollback_allowed" == true ]]; then
    rollback_env=$(mktemp "$(dirname -- "$YUEJI_RUNTIME_ENV")/.yueji-test-rollback.XXXXXX")
    awk -v backend="$current_backend" -v frontend="$current_frontend" '
      /^BACKEND_IMAGE=/ { print "BACKEND_IMAGE=" backend; next }
      /^FRONTEND_IMAGE=/ { print "FRONTEND_IMAGE=" frontend; next }
      { print }
    ' "$YUEJI_RUNTIME_ENV" > "$rollback_env"
    chmod 0600 "$rollback_env"
    dc_with_env "$rollback_env" pull backend web
    mv "$rollback_env" "$YUEJI_RUNTIME_ENV"
    yueji_dc up -d backend web
    rollback_backend_id=$(yueji_dc ps -q backend)
    rollback_web_id=$(yueji_dc ps -q web)
    rollback_ready=false
    for _ in $(seq 1 120); do
      backend_state=$(docker inspect --format '{{.State.Health.Status}}' "$rollback_backend_id" 2>/dev/null || true)
      web_state=$(docker inspect --format '{{.State.Health.Status}}' "$rollback_web_id" 2>/dev/null || true)
      if [[ "$backend_state" == healthy && "$web_state" == healthy ]]; then rollback_ready=true; break; fi
      sleep 1
    done
    rollback_smoke=false
    if [[ "$rollback_ready" == true ]] && APP_HOSTNAME="$host" APP_BASE_URL="$base" bash "$SCRIPT_DIR/smoke-stage-i-linux.sh" > "$record_dir/test-rollback-smoke-$stamp.log" 2>&1; then
      rollback_smoke=true
      chmod 0600 "$record_dir/test-rollback-smoke-$stamp.log"
    fi
    python3 - "$record" "$current_backend" "$current_frontend" "$rollback_ready" "$rollback_smoke" <<'PY'
import json, pathlib, sys
path, backend, frontend, ready, smoke = sys.argv[1:]
record = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
record.update({"applicationRollback":"ATTEMPTED", "rollbackBackendImage":backend,
               "rollbackFrontendImage":frontend, "rollbackReadinessPassed":ready == "true",
               "rollbackSmokePassed":smoke == "true",
               "databaseAction":"database left at forward migration; no reverse DDL or cleanup"})
pathlib.Path(path).write_text(json.dumps(record, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
PY
  else
    yueji_dc stop web backend || true
    yueji_die "smoke failed; services left in maintenance; app rollback not authorized by compatibility flag; inspect $record and $smoke_file"
  fi
  yueji_die "smoke failed; application digests restored if configured; database remains forward-only; inspect $record"
fi
chmod 0600 "$smoke_file"
python3 - "$record" "$stamp" "$backend_image" "$frontend_image" "$previous_available" "$backup_result" "$smoke_file" "$maintenance_window_id" "$release_run_id" "$release_head_sha" <<'PY'
import json, pathlib, sys
path, stamp, backend, frontend, previous, backup, smoke, maintenance, run_id, head_sha = sys.argv[1:]
record = {
    "environment": "test", "result": "DEPLOYED_AND_SMOKED",
    "deployedAtUtc": stamp, "backendImage": backend, "frontendImage": frontend,
    "qualityReleaseRunId": run_id, "gitSha": head_sha,
    "previousReleaseAvailable": previous == "true", "databaseMigration": "Flyway forward-only; V10 baseline; never auto-reverse or clear",
    "maintenanceWindowId": maintenance, "backupResult": backup, "smokeEvidence": smoke,
}
pathlib.Path(path).write_text(json.dumps(record, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
PY
chmod 0600 "$record"
printf 'TEST_DEPLOY_COMPLETE %s\n' "$record"
