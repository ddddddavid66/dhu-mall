"""检查已启动的工程产物与前端代理，仅执行无副作用的 GET。"""
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import argparse
import json

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', default='http://127.0.0.1:8080')
    parser.add_argument('--frontend', default='http://127.0.0.1:5173')
    args = parser.parse_args()
    checks = []

    def check(name, url, expected, verify):
        request = Request(url, headers={'X-Request-Id': 'smoke-stage-a-123'})
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            status = response.code
            raw = response.read()
            # HTTP 头名不区分大小写，反代改变大小写不能被误判为丢失。
            headers = {k.lower(): v for k, v in response.headers.items()}
        assert status == expected, (name, status)
        verify(raw, headers)
        checks.append({'name': name, 'url': url, 'expectedStatus': expected, 'actualStatus': status, 'passed': True})

    def healthy(raw, headers):
        assert json.loads(raw)['status'] == 'UP'

    def operations(raw, headers):
        document = json.loads(raw)
        values = [v for item in document['paths'].values() for k, v in item.items()
                  if k in ['get', 'post', 'put', 'delete']]
        assert len(values) == 92
        assert all(v.get('x-implementation-status') in ['IMPLEMENTED', 'NOT_IMPLEMENTED', 'DISABLED'] for v in values)
        assert sum(v.get('x-implementation-status') == 'IMPLEMENTED' for v in values) == 56

    def placeholder(raw, headers):
        body = json.loads(raw)
        assert body['code'] == 'NOT_IMPLEMENTED' and body['data'] is None
        assert body['traceId'] == headers.get('x-request-id') == 'smoke-stage-a-123'

    def entry(raw, headers):
        assert b'<div id="app"></div>' in raw

    check('jar-liveness', args.backend + '/actuator/health/liveness', 200, healthy)
    check('jar-readiness', args.backend + '/actuator/health/readiness', 200, healthy)
    check('jar-openapi-92', args.backend + '/v3/api-docs', 200, operations)
    check('jar-swagger-ui', args.backend + '/swagger-ui/index.html', 200, lambda raw, headers: None)
    check('frontend-entry', args.frontend + '/', 200, entry)
    check('frontend-workbench-entry', args.frontend + '/workbench', 200, entry)
    check('frontend-health-proxy', args.frontend + '/actuator/health/readiness', 200, healthy)
    check('frontend-api-proxy-501', args.frontend + '/api/v1/merchant/shops/1/orders', 501, placeholder)
    report = {'verifiedAtUtc': datetime.now(timezone.utc).isoformat(), 'checks': checks, 'total': len(checks)}
    path = ROOT / '.artifacts/smoke-results.json'
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Smoke: {len(checks)} checks passed')


if __name__ == '__main__':
    main()
