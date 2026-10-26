"""本机专用：拒绝退出的候选容器，回退已知前端 digest，再恢复当前版本；不逆向迁移。"""
from pathlib import Path
from datetime import datetime, timezone
import json
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / '.artifacts/local-test/runtime.env'
PREVIOUS = 'yueji-local-frontend@sha256:d3a112a08a11cb3775e3486321d58ed8b58ddb224ea63157925cd4fd6e8451c3'


def docker(*args, check=True):
    return subprocess.run(['docker', *args], cwd=ROOT, text=True, capture_output=True, check=check)


def snapshot():
    statement = "SELECT JSON_OBJECT('orders',(SELECT COUNT(*) FROM trade_order),'payments',(SELECT COUNT(*) FROM payment),'refunds',(SELECT COUNT(*) FROM refund),'settlements',(SELECT COUNT(*) FROM settlement),'inventory',(SELECT SUM(on_hand) FROM inventory),'reserved',(SELECT SUM(reserved) FROM inventory),'migration',(SELECT MAX(version+0) FROM flyway_schema_history WHERE success=1));"
    response = docker('exec', 'yueji-local-test-mysql-1', 'sh', '-ec',
                      'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql -N -B -uroot yueji_mall_test -e "$1"', 'sh', statement)
    return json.loads(response.stdout)


def change_image(text, image):
    return '\n'.join('FRONTEND_IMAGE=' + image if line.startswith('FRONTEND_IMAGE=') else line for line in text.splitlines()) + '\n'


def activate():
    result = docker('compose', '-p', 'yueji-local-test', '--env-file', str(ENV), '-f', 'infra/compose.yml',
                    '-f', 'infra/compose.local-test.yml', 'up', '-d', '--wait', '--pull', 'never', 'web')
    return result.returncode == 0


original = ENV.read_text(encoding='utf-8-sig')
assert 'PAYMENT_PROVIDER=LOCAL_MOCK' in original and 'MYSQL_DATABASE=yueji_mall_test' in original
current = next(line.split('=', 1)[1] for line in original.splitlines() if line.startswith('FRONTEND_IMAGE='))
assert current.startswith('yueji-local-frontend@sha256:') and current != PREVIOUS
before = snapshot()
candidate = 'yueji-rejected-candidate-' + uuid.uuid4().hex[:10]
# 候选在独立容器失败，禁止把它晋级为正在运行的服务。
failed = docker('run', '--name', candidate, '--entrypoint', '/bin/false', current, check=False)
assert failed.returncode == 1
state = json.loads(docker('inspect', candidate).stdout)[0]['State']
assert not state['Running'] and state['ExitCode'] == 1
try:
    ENV.write_text(change_image(original, PREVIOUS), encoding='utf-8')
    assert activate()
    assert docker('inspect', 'yueji-local-test-web-1', '--format', '{{.Config.Image}}').stdout.strip() == PREVIOUS
    assert snapshot() == before
finally:
    ENV.write_text(original, encoding='utf-8')
    assert activate(), 'Current frontend could not be restored; inspect the local stack'
after = snapshot()
assert after == before
report = dict(status='LOCAL_FRONTEND_ROLLBACK_PASSED', checkedAt=datetime.now(timezone.utc).isoformat(),
              rejectedCandidateExitCode=state['ExitCode'], previousFrontend=PREVIOUS, restoredFrontend=current,
              before=before, after=after, destructiveMigration=False,
              limitation='Frontend digest rollback on the local stack; backend version rollback and remote CD were not exercised.')
(ROOT / '.artifacts/R-local-rollback.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report))
