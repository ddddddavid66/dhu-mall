#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/yueji-ops-common.sh"

usage() { printf 'Usage: DEPLOY_ENV=test %s BACKUP.age --confirm-isolated-restore\n' "$0"; }
[[ "${2:-}" == --confirm-isolated-restore ]] || { usage >&2; exit 2; }
[[ "${DEPLOY_ENV:-}" == test ]] || yueji_die 'isolated restore is restricted to DEPLOY_ENV=test'
archive=${1:?backup archive path required}
yueji_init
[[ "$(yueji_env DEPLOY_ENV || true)" == test ]] || yueji_die 'protected runtime env must also declare DEPLOY_ENV=test'
record_dir="$YUEJI_PROJECT_ROOT/records"
install -d -m 0700 "$record_dir"
for name in age tar gzip sha256sum python3; do command -v "$name" >/dev/null || yueji_die "$name is required"; done
[[ -f "$archive" && -r "$archive" ]] || yueji_die "backup archive unreadable: $archive"
age_identity=$(yueji_env AGE_IDENTITY_FILE) || yueji_die 'AGE_IDENTITY_FILE is required'
mysql_root_file=$(yueji_env MYSQL_ROOT_PASSWORD_FILE) || yueji_die 'MYSQL_ROOT_PASSWORD_FILE is required'
restore_db=$(yueji_env RESTORE_DATABASE) || yueji_die 'RESTORE_DATABASE is required'
restore_storage=$(yueji_env RESTORE_FILE_STORAGE_PATH) || yueji_die 'RESTORE_FILE_STORAGE_PATH is required'
[[ "$restore_db" =~ ^yueji_mall_restore_[A-Za-z0-9_]+$ ]] || yueji_die 'RESTORE_DATABASE must use the isolated yueji_mall_restore_ prefix'
yueji_require_private_file "$age_identity"
yueji_require_private_file "$mysql_root_file"
active_storage=$(yueji_env FILE_STORAGE_PATH) || yueji_die 'FILE_STORAGE_PATH is required'
restore_root=$(realpath -m /opt/yueji/restore)
restore_canonical=$(realpath -m "$restore_storage")
active_canonical=$(realpath -m "$active_storage")
[[ "$restore_canonical" == "$restore_root/"* && "$restore_canonical" != "$restore_root" ]] || yueji_die 'restore file directory must resolve under /opt/yueji/restore'
[[ "$restore_canonical" != "$active_canonical" && ! -L "$restore_storage" ]] || yueji_die 'restore file directory must be a separate non-symlink path from active storage'
restore_storage="$restore_canonical"
yueji_require_empty_or_missing_dir "$restore_storage"

mysql_id=$(yueji_dc ps -q mysql)
[[ -n "$mysql_id" && "$(docker inspect --format '{{.State.Health.Status}}' "$mysql_id")" == healthy ]] || yueji_die 'test MySQL container must be healthy'
work=$(mktemp -d "${TMPDIR:-/tmp}/yueji-restore.XXXXXX")
chmod 0700 "$work"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
restore_started=$(date -u +%s)
age -d -i "$age_identity" -o "$work/restore.tar.gz" "$archive"

# Accept only the three regular files produced by backup-yueji-linux.sh.
python3 - "$work/restore.tar.gz" "$work/unpacked" <<'PY'
import pathlib, sys, tarfile
source, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
allowed = {"manifest.json", "database.sql.gz", "files.tar.gz"}
out.mkdir(mode=0o700)
with tarfile.open(source, "r:gz") as tf:
    for member in tf.getmembers():
        if member.name not in allowed or not member.isfile() or member.issym() or member.islnk():
            raise SystemExit(f"unsafe or unexpected archive member: {member.name!r}")
        stream = tf.extractfile(member)
        if stream is None:
            raise SystemExit(f"unreadable archive member: {member.name!r}")
        with stream, (out / member.name).open("xb") as target:
            while chunk := stream.read(1024 * 1024):
                target.write(chunk)
if {p.name for p in out.iterdir()} != allowed:
    raise SystemExit("backup archive is incomplete")
PY
python3 - "$work/unpacked/manifest.json" "$work/unpacked/database.sql.gz" "$work/unpacked/files.tar.gz" <<'PY'
import hashlib, json, pathlib, sys
manifest_path, db_path, files_path = map(pathlib.Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
for key, path in (("databaseDumpSha256", db_path), ("filesArchiveSha256", files_path)):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != manifest.get(key):
        raise SystemExit(f"backup checksum mismatch: {key}")
if manifest.get("environment") != "test":
    raise SystemExit("isolated test restore accepts test-environment backups only")
print(json.dumps({"backupEnvironment": manifest["environment"], "createdAtUtc": manifest["createdAtUtc"]}))
PY

restore_file_tree() {
  python3 - "$work/unpacked/files.tar.gz" "$restore_storage" <<'PY'
import pathlib, sys, tarfile
source, destination = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=True, mode=0o750)
root = destination.resolve()
with tarfile.open(source, "r:gz") as tf:
    for member in tf.getmembers():
        p = pathlib.PurePosixPath(member.name)
        if p.is_absolute() or ".." in p.parts or member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
            raise SystemExit(f"unsafe file-tree entry: {member.name!r}")
        target = (destination / pathlib.Path(*p.parts)).resolve()
        if root not in target.parents and root != target:
            raise SystemExit(f"file-tree path escapes restore directory: {member.name!r}")
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            stream = tf.extractfile(member)
            if stream is None:
                raise SystemExit(f"unreadable file-tree entry: {member.name!r}")
            with stream, target.open("xb") as out:
                while chunk := stream.read(1024 * 1024):
                    out.write(chunk)
PY
}

mysql_query() {
  docker exec "$mysql_id" sh -ec 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql -Nse "$1"' sh "$1"
}
mysql_query "CREATE DATABASE IF NOT EXISTS \`$restore_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
table_count=$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$restore_db'")
[[ "$table_count" == 0 ]] || yueji_die "isolated restore database is not empty (table_count=$table_count); no data was changed"
mysql_db_import() {
  docker exec -i "$mysql_id" sh -ec 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql "$1"' sh "$restore_db"
}
gzip -dc "$work/unpacked/database.sql.gz" | mysql_db_import
restore_file_tree
chown -R 10001:10001 "$restore_storage"
table_count=$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$restore_db'")
[[ "$table_count" =~ ^[0-9]+$ && "$table_count" -gt 0 ]] || yueji_die 'restore produced no database tables'
orders=$(mysql_query "SELECT COUNT(*) FROM \`$restore_db\`.trade_order")
order_sample_status=NO_ORDER_SAMPLE
if [[ "$orders" =~ ^[0-9]+$ && "$orders" -gt 0 ]]; then
  order_sample_status=$(mysql_query "SELECT status FROM \`$restore_db\`.trade_order ORDER BY id LIMIT 1")
fi
files=$(find "$restore_storage" -type f -print | wc -l | tr -d ' ')
[[ "$files" =~ ^[0-9]+$ && "$files" -gt 0 ]] || yueji_die 'restored file tree has no regular file to read-check'
first_file=$(find "$restore_storage" -type f -print -quit)
[[ -r "$first_file" && -s "$first_file" ]] || yueji_die 'restored file sample is unreadable or empty'
file_sample_sha=$(sha256sum "$first_file" | awk '{print $1}')
restore_finished=$(date -u +%s)
source_created=$(python3 -c 'import json,sys,datetime; d=json.load(open(sys.argv[1], encoding="utf-8")); print(int(datetime.datetime.fromisoformat(d["createdAtUtc"].replace("Z", "+00:00")).timestamp()))' "$work/unpacked/manifest.json")
restore_stamp=$(date -u +%Y%m%dT%H%M%SZ)
record="$record_dir/isolated-restore-$restore_stamp.json"
restore_result=$(python3 - "$restore_db" "$table_count" "$orders" "$order_sample_status" "$files" "$file_sample_sha" "$restore_started" "$restore_finished" "$source_created" "$(sha256sum "$archive" | awk '{print $1}')" <<'PY'
import json, sys
db, tables, orders, sample_status, files, sample_sha, started, finished, source, archive_sha = sys.argv[1:]
finish, source, start = int(finished), int(source), int(started)
print(json.dumps({
    "result": "ISOLATED_RESTORE_COMPLETE", "environment": "test", "database": db,
    "tableCount": int(tables), "orderCount": int(orders), "orderSampleStatus": sample_status,
    "restoredFileCount": int(files), "readableFileSampleSha256": sample_sha,
    "sourceBackupAtEpoch": source, "completedAtEpoch": finish,
    "rpoSecondsAtRestore": max(0, finish - source), "rtoSeconds": finish - start,
    "archiveSha256": archive_sha,
    "databaseAction": "new isolated schema only; no clear/drop/rename of active database",
}, ensure_ascii=False))
PY
)
printf '%s\n' "$restore_result" > "$record"
chmod 0600 "$record"
printf '%s\n' "$restore_result"
printf 'ISOLATED_RESTORE_RECORD=%s\n' "$record"
