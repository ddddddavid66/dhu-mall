"""仅隔离 Docker LOCAL_MOCK 环境：渠道事实已提交后强杀应用，再由原支付号恢复。"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from datetime import datetime, timezone
import json, subprocess, time, uuid

root = Path(__file__).resolve().parents[1]
container = 'yueji-local-test-backend-1'
database_container = 'yueji-local-test-mysql-1'
base = 'http://127.0.0.1:18080/api/v1'
accounts = json.loads((root / '.artifacts/R-e2e-accounts.json').read_text(encoding='utf-8'))
fixture = json.loads((root / '.artifacts/R-e2e-fixtures.json').read_text(encoding='utf-8'))
token = None

def docker(*args):
    return subprocess.run(['docker', *args], check=True, capture_output=True, text=True).stdout.strip()

def sql(statement):
    if statement.startswith('CREATE TRIGGER '): statement = 'DELIMITER $$\n' + statement + '$$\nDELIMITER ;'
    return docker('exec', database_container, 'sh', '-ec',
                  'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql -N -B -uroot yueji_mall_test -e "$1"', 'sh', statement)

def call(method, path, data=None, expected=200):
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4()), 'X-Request-Id': 'R-process-crash-drill'}
    if token: headers['Authorization'] = 'Bearer ' + token
    request = Request(base + path, None if data is None else json.dumps(data).encode(), headers, method=method)
    try:
        with urlopen(request, timeout=15) as response: status, body = response.status, json.load(response)
    except HTTPError as error: status, body = error.code, json.load(error)
    assert status == expected, (path, status, body.get('code'))
    return body.get('data')

assert json.loads(docker('inspect', container))[0]['Config']['Env'].count('PAYMENT_PROVIDER=LOCAL_MOCK') == 1
assert sql('SELECT DATABASE()') == 'yueji_mall_test'
token = call('POST', '/auth/login', {key: accounts['buyer'][key] for key in ('username', 'password')})['accessToken']
for sku in (fixture['sku1'], fixture['sku2']):
    call('PUT', '/cart/items/' + sku, {'quantity': 1, 'selected': sku == fixture['sku1']})
cart = call('GET', '/cart')
item_ids = [item['id'] for group in cart['groups'] for item in group['items'] if item['skuId'] == fixture['sku1']]
preview = call('POST', '/checkout/previews', {'addressId': call('GET', '/addresses')[0]['id'], 'cartItemIds': item_ids}, 201)
order = call('POST', '/checkout/orders', {'previewId': preview['previewId']}, 201)['orders'][0]
payment = call('POST', '/orders/' + order['id'] + '/payments', {}, 201)
assert order['id'].isdigit() and payment['id'].isdigit()
before = json.loads(sql("SELECT JSON_OBJECT('onHand',on_hand,'reserved',reserved) FROM inventory WHERE sku_id=" + fixture['sku1']))
trigger = 'r_isolated_crash_before_apply'
try:
    sql(f"CREATE TRIGGER {trigger} BEFORE UPDATE ON trade_order FOR EACH ROW BEGIN IF OLD.id={order['id']} AND NEW.status='PAID' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated crash drill persistence fault'; END IF; END")
    call('POST', '/payments/' + payment['id'] + '/mock-confirm', {}, 503)
    assert sql(f"SELECT COUNT(*) FROM payment_event WHERE payment_id={payment['id']} AND event_key='mock:{payment['paymentNo']}:paid'") == '1'
    assert sql(f"SELECT status FROM trade_order WHERE id={order['id']}") == 'PENDING_PAYMENT'
    print('Committed LOCAL_MOCK channel fact observed; business apply failed as injected.', flush=True)
finally:
    sql(f'DROP TRIGGER IF EXISTS {trigger}')

docker('update', '--restart=no', container)
try:
    docker('kill', '--signal=KILL', container)
    killed = datetime.now(timezone.utc).isoformat()
    print('Application container SIGKILL confirmed; preserving 80-second outage for real monitoring alert.', flush=True)
    for _ in range(8): time.sleep(10)
    assert json.loads(docker('inspect', container))[0]['State']['Running'] is False
    docker('start', container)
    # PaymentRecovery 的现有数据库租约是300秒，扫描间隔30秒；测试等待该恢复预算。
    deadline = time.monotonic() + 360
    recovered = None
    while time.monotonic() < deadline:
        try:
            candidate = call('GET', '/payments/' + payment['id'])
            if candidate['status'] == 'SUCCEEDED': recovered = candidate; break
        except (HTTPError, URLError, ConnectionError, OSError, AssertionError): pass
        time.sleep(2)
    assert recovered and recovered['paymentNo'] == payment['paymentNo']
    assert sql(f"SELECT COUNT(*) FROM payment WHERE order_id={order['id']}") == '1'
    assert sql(f"SELECT status FROM trade_order WHERE id={order['id']}") == 'PAID'
    after = json.loads(sql("SELECT JSON_OBJECT('onHand',on_hand,'reserved',reserved) FROM inventory WHERE sku_id=" + fixture['sku1']))
    assert after == {'onHand': before['onHand']-1, 'reserved': before['reserved']-1}, (before, after)
    assert sql('SELECT COUNT(*) FROM inventory WHERE reserved<0 OR on_hand<reserved') == '0'
    record = dict(status='PASSED', provider='LOCAL_MOCK', orderId=order['id'], paymentNo=payment['paymentNo'],
                  killedAt=killed, recoveredAt=datetime.now(timezone.utc).isoformat(), channelFactCommittedBeforeKill=True,
                  processSignal='SIGKILL', originalPaymentNumberPreserved=True, paymentRows=1, before=before, after=after,
                  cleanup='test-only trigger removed; restart policy restored; test order retained as evidence')
    (root / '.artifacts/R-process-crash.json').write_text(json.dumps(record, indent=2)+'\n')
    print(json.dumps(record), flush=True)
finally:
    docker('update', '--restart=unless-stopped', container)
    if not json.loads(docker('inspect', container))[0]['State']['Running']: docker('start', container)
