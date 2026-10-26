"""连续探测真实服务；区分运行中与完整24小时，不把缺失样本计为成功。"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from datetime import datetime, timezone
import argparse
import json
import time

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', required=True)
parser.add_argument('--health-base-url', help='管理端口独立时指定就绪接口根地址')
parser.add_argument('--output', required=True)
parser.add_argument('--duration-seconds', type=int, default=86400)
parser.add_argument('--interval-seconds', type=int, default=60)
args = parser.parse_args()
if args.duration_seconds < 1 or args.interval_seconds < 1:
    parser.error('duration and interval must be positive')
destination = Path(args.output)
destination.parent.mkdir(parents=True, exist_ok=True)
if destination.exists():
    raise SystemExit('拒绝覆盖既有探测；新运行使用新文件名')
started = time.time()
total = (args.duration_seconds + args.interval_seconds - 1) // args.interval_seconds
summary = destination.with_suffix('.summary.json')
passed = 0
for index in range(total):
    wait = started + index * args.interval_seconds - time.time()
    if wait > 0:
        time.sleep(wait)
    scheduled = started + index * args.interval_seconds
    observations = []
    late = time.time() - scheduled > args.interval_seconds
    for path in ('/actuator/health/readiness', '/api/v1/products?page=1&pageSize=1'):
        begin = time.monotonic()
        try:
            base = args.health_base_url if 'actuator' in path and args.health_base_url else args.base_url
            with urlopen(Request(base.rstrip('/') + path, headers={'X-Request-Id': 'availability-probe'}), timeout=10) as response:
                body = json.loads(response.read())
                success = response.status == 200 and (body.get('status') == 'UP' if 'actuator' in path else body.get('code') == 'OK' and body.get('data') is not None)
                observations.append(dict(path=path, status=response.status, success=success))
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
            observations.append(dict(path=path, success=False, errorType=type(error).__name__))
        observations[-1]['elapsedMs'] = round((time.monotonic() - begin) * 1000, 2)
    success = not late and all(item['success'] for item in observations)
    passed += int(success)
    with destination.open('a', encoding='utf-8') as output:
        output.write(json.dumps(dict(index=index, scheduledAtEpoch=scheduled, observedAt=datetime.now(timezone.utc).isoformat(), late=late, success=success, observations=observations)) + '\n')
    elapsed = time.time() - started
    report = dict(status='RUNNING', startedAtEpoch=started, elapsedSeconds=round(elapsed, 2), expectedSamples=total,
                  recordedSamples=index+1, successfulSamples=passed, successPercent=round(passed/(index+1)*100, 4),
                  targetDurationSeconds=86400, complete24Hours=False)
    summary.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
# 最后一个采样点之后仍需完整运行至窗口结束。
remaining = started + args.duration_seconds - time.time()
if remaining > 0:
    time.sleep(remaining)
report.update(status='COMPLETED', elapsedSeconds=round(time.time()-started, 2), complete24Hours=args.duration_seconds >= 86400)
report['acceptancePassed'] = report['complete24Hours'] and passed/total >= .99
summary.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
