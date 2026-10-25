"""通过 Vite 同源代理验证公开接口和未实现接口契约；仅允许访问回环地址。"""
from __future__ import annotations

from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import argparse
import json


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:5173')
    args = parser.parse_args()
    parsed = urlparse(args.base_url)
    if parsed.scheme != 'http' or parsed.hostname not in {'127.0.0.1', 'localhost', '::1'}:
        raise SystemExit('仅允许访问 HTTP 回环地址')

    checks = []
    for name, path, expected_status, expected_code in [
        ('categories', '/api/v1/categories', 200, 'OK'),
        ('products', '/api/v1/products?page=1&pageSize=5', 200, 'OK'),
        ('dashboard-not-implemented', '/api/v1/dashboard', 501, 'NOT_IMPLEMENTED'),
    ]:
        request = Request(args.base_url.rstrip('/') + path, headers={'X-Request-Id': 'stage-g-http-smoke'})
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            body = json.loads(response.read())
            assert response.status == expected_status, (name, response.status)
            assert body.get('code') == expected_code, (name, body.get('code'))
            assert isinstance(body.get('traceId'), str) and body['traceId'], (name, 'traceId missing')
            data = body.get('data')
            if expected_status == 501:
                assert data is None, (name, 'unexpected success payload')
            else:
                assert data is not None, (name, 'missing success payload')
            checks.append({
                'name': name,
                'status': response.status,
                'code': body['code'],
                'traceIdPresent': True,
                'total': data.get('total') if isinstance(data, dict) else None,
            })
    print(json.dumps({'passed': True, 'checks': checks}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
