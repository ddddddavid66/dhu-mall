#!/usr/bin/env bash
set -Eeuo pipefail
export COMPOSE_PROJECT_NAME=yueji-local-test DEPLOY_ENV=test
export YUEJI_PROJECT_ROOT=/opt/yueji YUEJI_RUNTIME_ENV=/opt/yueji/runtime.env YUEJI_COMPOSE_FILE=/opt/yueji/infra/compose.yml
install -d -m 0700 /opt/yueji/secrets /opt/yueji/backups /opt/yueji/restore /opt/yueji/records /opt/yueji/local-copy
install -d -m 0750 /opt/yueji/infra
install -m 0600 /src/.artifacts/local-test/resolved-compose.yml "$YUEJI_COMPOSE_FILE"
python3 - <<'PY'
from pathlib import Path
import shutil
source=Path('/src/.artifacts/local-test/runtime.env')
env=dict(line.split('=',1) for line in source.read_text().splitlines() if '=' in line and not line.startswith('#'))
for key in ('MYSQL_ROOT_PASSWORD_FILE','DB_PASSWORD_FILE','JWT_SIGNING_KEY_FILE','REDIS_PASSWORD_FILE','RABBITMQ_PASSWORD_FILE','ALIPAY_APP_PRIVATE_KEY_FILE','ALIPAY_PUBLIC_KEY_FILE'):
    name=Path(env[key]).name
    target=Path('/opt/yueji/secrets')/name
    shutil.copyfile(Path('/src/.artifacts/local-test/secrets')/name,target);target.chmod(0o600);env[key]=str(target)
env.update(FILE_STORAGE_PATH='/opt/yueji/files',BACKUP_DIRECTORY='/opt/yueji/backups',BACKUP_REMOTE='local-test-only:/opt/yueji/local-copy',
           RCLONE_CONFIG_FILE='/opt/yueji/secrets/rclone.conf',AGE_IDENTITY_FILE='/opt/yueji/secrets/age-identity')
Path('/opt/yueji/runtime.env').write_text(''.join(f'{k}={v}\n' for k,v in env.items()));Path('/opt/yueji/runtime.env').chmod(0o600)
Path('/opt/yueji/secrets/rclone.conf').write_text('[local-test-only]\ntype = local\n');Path('/opt/yueji/secrets/rclone.conf').chmod(0o600)
PY
if [[ ! -f /opt/yueji/secrets/age-identity ]]; then age-keygen -o /opt/yueji/secrets/age-identity 2>/opt/yueji/records/age-keygen.log; fi
chmod 0600 /opt/yueji/secrets/age-identity
recipient=$(age-keygen -y /opt/yueji/secrets/age-identity)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
printf 'BACKUP_AGE_RECIPIENT=%s\nRESTORE_DATABASE=yueji_mall_restore_%s\nRESTORE_FILE_STORAGE_PATH=/opt/yueji/restore/%s\n' "$recipient" "$stamp" "$stamp" >> "$YUEJI_RUNTIME_ENV"
bash /usr/local/lib/yueji/backup-yueji-linux.sh --confirm-maintenance > /opt/yueji/records/R-local-backup.json
archive=$(python3 -c 'import json;print(json.load(open("/opt/yueji/records/R-local-backup.json"))["encryptedFile"])')
bash /usr/local/lib/yueji/restore-yueji-isolated-linux.sh "$archive" --confirm-isolated-restore > /opt/yueji/records/R-local-restore.log
if bash /usr/local/lib/yueji/restore-yueji-isolated-linux.sh "$archive" --confirm-isolated-restore > /opt/yueji/records/R-repeat-restore-rejected.log 2>&1; then
  echo 'repeated restore unexpectedly accepted nonempty target' >&2; exit 1
fi
source /usr/local/lib/yueji/yueji-ops-common.sh
mysql_id=$(yueji_dc ps -q mysql)
query() { docker exec "$mysql_id" sh -ec 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql -N -B -uroot -e "$1"' sh "$1"; }
database=$(yueji_env MYSQL_DATABASE)
restored=$(yueji_env RESTORE_DATABASE)
[[ "$database" == yueji_mall_test && "$restored" =~ ^yueji_mall_restore_[A-Za-z0-9_]+$ ]] || exit 2
snapshot() {
  query "SELECT JSON_OBJECT('orders',(SELECT COUNT(*) FROM \`$1\`.trade_order),'payments',(SELECT COUNT(*) FROM \`$1\`.payment),'refunds',(SELECT COUNT(*) FROM \`$1\`.refund),'movements',(SELECT COUNT(*) FROM \`$1\`.inventory_movement),'settlements',(SELECT COUNT(*) FROM \`$1\`.settlement),'files',(SELECT COUNT(*) FROM \`$1\`.file_object),'onHand',(SELECT SUM(on_hand) FROM \`$1\`.inventory),'reserved',(SELECT SUM(reserved) FROM \`$1\`.inventory),'invalidInventory',(SELECT COUNT(*) FROM \`$1\`.inventory WHERE reserved>on_hand),'refundOverflow',(SELECT COUNT(*) FROM \`$1\`.trade_order WHERE refunded_amount>payable_amount),'invalidSettlement',(SELECT COUNT(*) FROM \`$1\`.settlement WHERE payable_amount<>gross_amount-refund_amount-fee_amount+adjustment_amount));"
}
snapshot "$database" > /opt/yueji/records/R-source-invariants.json
snapshot "$restored" > /opt/yueji/records/R-restored-invariants.json
query "GRANT ALL ON \`$restored\`.* TO 'yueji_test'@'%';"
install -d -m 0750 -o 10001 -g 10001 /opt/yueji/restore-app-secrets
install -m 0400 -o 10001 -g 10001 /opt/yueji/secrets/database-password /opt/yueji/restore-app-secrets/database-password
install -m 0400 -o 10001 -g 10001 /opt/yueji/secrets/jwt-signing-key /opt/yueji/restore-app-secrets/jwt-signing-key
export RESTORE_RUN="$stamp"
python3 - <<'PY'
from pathlib import Path
import hashlib,json,os
source=json.loads(Path('/opt/yueji/records/R-source-invariants.json').read_text())
restored=json.loads(Path('/opt/yueji/records/R-restored-invariants.json').read_text())
assert source==restored,(source,restored)
assert restored['invalidInventory']==restored['refundOverflow']==restored['invalidSettlement']==0
fixture=json.loads(Path('/src/.artifacts/R-e2e-fixtures.json').read_text())
key=fixture['coverStorageKey']; assert '..' not in Path(key).parts and not Path(key).is_absolute()
original=Path('/opt/yueji/files')/key
copy=Path('/opt/yueji/restore')/os.environ['RESTORE_RUN']/key
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert digest(original)==digest(copy)
result=dict(status='LOCAL_RESTORE_INVARIANTS_PASSED',restoreDatabase='yueji_mall_restore_'+os.environ['RESTORE_RUN'],
            restoreSubpath='restore/'+os.environ['RESTORE_RUN'],invariants=restored,referencedFileSha256=digest(copy),
            repeatedNonemptyRestoreRejected=True,offsiteVerified=False,
            limitation='Encrypted rclone copy was on the same Docker host; independent offsite storage was not provided.')
Path('/opt/yueji/records/R-local-recovery.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
PY
