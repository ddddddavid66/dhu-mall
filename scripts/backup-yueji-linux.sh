#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/yueji-ops-common.sh"

usage() { printf 'Usage: DEPLOY_ENV=test|prod %s --confirm-maintenance\n' "$0"; }
[[ "${1:-}" == --confirm-maintenance ]] || { usage >&2; exit 2; }
[[ "${DEPLOY_ENV:-}" == test || "${DEPLOY_ENV:-}" == prod ]] || yueji_die 'DEPLOY_ENV must be test or prod'
yueji_init
configured_env=$(yueji_env DEPLOY_ENV) || yueji_die 'DEPLOY_ENV is required in the protected runtime env'
[[ "$configured_env" == "$DEPLOY_ENV" ]] || yueji_die 'DEPLOY_ENV does not match the protected runtime env'

for name in age rclone tar gzip sha256sum python3 stat; do command -v "$name" >/dev/null || yueji_die "$name is required"; done
db=$(yueji_env MYSQL_DATABASE) || yueji_die 'MYSQL_DATABASE is required'
storage=$(yueji_env FILE_STORAGE_PATH) || yueji_die 'FILE_STORAGE_PATH is required'
backup_dir=$(yueji_env BACKUP_DIRECTORY) || yueji_die 'BACKUP_DIRECTORY is required'
recipient=$(yueji_env BACKUP_AGE_RECIPIENT) || yueji_die 'BACKUP_AGE_RECIPIENT is required'
remote=$(yueji_env BACKUP_REMOTE) || yueji_die 'BACKUP_REMOTE is required'
rclone_config=$(yueji_env RCLONE_CONFIG_FILE) || yueji_die 'RCLONE_CONFIG_FILE is required'
root_password_file=$(yueji_env MYSQL_ROOT_PASSWORD_FILE) || yueji_die 'MYSQL_ROOT_PASSWORD_FILE is required'
[[ "$db" =~ ^[A-Za-z0-9_]+$ ]] || yueji_die 'invalid MYSQL_DATABASE name'
[[ -d "$storage" ]] || yueji_die "file storage directory missing: $storage"
yueji_require_private_file "$root_password_file"
yueji_require_private_file "$rclone_config"
mkdir -p -m 0700 "$backup_dir"
chmod 0700 "$backup_dir"

running=$(yueji_dc ps --status running --services)
if printf '%s\n' "$running" | grep -Eq '^(backend|web)$'; then
  yueji_die 'stop backend and web and quiesce uploads before taking a consistent backup'
fi
mysql_id=$(yueji_dc ps -q mysql)
[[ -n "$mysql_id" ]] || yueji_die 'MySQL container is not running'
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$mysql_id")" == healthy ]] || yueji_die 'MySQL is not healthy'

stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d "${TMPDIR:-/tmp}/yueji-backup.XXXXXX")
chmod 0700 "$work"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT

docker exec "$mysql_id" sh -ec 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysqldump --single-transaction --routines --triggers --events --no-tablespaces "$1"' sh "$db" \
  | gzip -n > "$work/database.sql.gz"
tar -czf "$work/files.tar.gz" -C "$storage" .
database_sha=$(sha256sum "$work/database.sql.gz" | awk '{print $1}')
files_sha=$(sha256sum "$work/files.tar.gz" | awk '{print $1}')
DEPLOY_ENV="$DEPLOY_ENV" MYSQL_DATABASE="$db" BACKUP_UTC="$stamp" DATABASE_SHA="$database_sha" FILES_SHA="$files_sha" python3 - "$work/manifest.json" <<'PY'
import json, os, sys
manifest = {
    "environment": os.environ["DEPLOY_ENV"],
    "database": os.environ["MYSQL_DATABASE"],
    "createdAtUtc": os.environ["BACKUP_UTC"],
    "databaseDumpSha256": os.environ["DATABASE_SHA"],
    "filesArchiveSha256": os.environ["FILES_SHA"],
    "consistency": "application writers stopped; mysqldump single-transaction; file tree archived in the same maintenance window",
}
with open(sys.argv[1], "w", encoding="utf-8") as out:
    json.dump(manifest, out, ensure_ascii=False, indent=2)
    out.write("\n")
PY
archive="$work/yueji-${DEPLOY_ENV}-${stamp}.tar.gz"
tar -czf "$archive" -C "$work" manifest.json database.sql.gz files.tar.gz
encrypted="$backup_dir/$(basename "$archive").age"
age -r "$recipient" -o "$encrypted" "$archive"
chmod 0600 "$encrypted"
archive_sha=$(sha256sum "$encrypted" | awk '{print $1}')
remote_file="${remote%/}/$(basename "$encrypted")"
RCLONE_CONFIG="$rclone_config" rclone copyto "$encrypted" "$remote_file" --config "$rclone_config"
RCLONE_CONFIG="$rclone_config" rclone lsjson "$remote_file" --config "$rclone_config" >/dev/null
record="$backup_dir/backup-$DEPLOY_ENV-$stamp.json"
python3 - "$record" "$DEPLOY_ENV" "$stamp" "$encrypted" "$archive_sha" "$remote_file" <<'PY'
import json, pathlib, sys
path, environment, created, encrypted, sha, remote = sys.argv[1:]
record = {"result":"BACKUP_UPLOADED", "environment":environment,
          "createdAtUtc":created, "encryptedFile":encrypted,
          "encryptedSha256":sha, "remoteObject":remote}
pathlib.Path(path).write_text(json.dumps(record, indent=2)+"\n", encoding="utf-8")
PY
chmod 0600 "$record"
cat "$record"
