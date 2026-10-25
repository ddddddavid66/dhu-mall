"""仅供本机隔离 dev 实例：使用忽略文件中的随机账号，输出不含凭据的 API 冒烟证据。"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from datetime import datetime, timezone
import argparse
import json

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', default='http://127.0.0.1:18080')
    args = parser.parse_args()
    if not args.backend.startswith(('http://127.0.0.1:', 'http://localhost:')):
        raise SystemExit('只允许本机开发实例')
    credentials = json.loads((ROOT / '.artifacts/B-dev-accounts.json').read_text(encoding='utf-8'))
    checks = []

    def call(name, method, path, expected, code='OK', body=None, token=None, session=None):
        headers = {'Content-Type': 'application/json', 'X-Request-Id': 'stage-b-smoke-identity'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if session:
            headers.update({'Cookie': session[0], 'X-CSRF-Token': session[1], 'Origin': 'http://localhost:5173'})
        request = Request(args.backend + '/api/v1' + path,
                          data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        try:
            response = urlopen(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            value = json.loads(response.read())
            assert response.code == expected and value['code'] == code, (name, response.code, value['code'])
            assert value['traceId'] == response.headers['X-Request-Id']
            cookie = response.headers.get('Set-Cookie', '').split(';', 1)[0]
        checks.append({'name': name, 'actualStatus': expected, 'actualCode': code, 'passed': True})
        return value.get('data'), cookie

    def login(user):
        data, cookie = call('login-' + user, 'POST', '/auth/login', 200,
                            body={'username': user, 'password': credentials[user]['password']})
        assert 'refreshToken' not in data
        return data['accessToken'], (cookie, data['csrfToken'])

    access, original = login('reader_a')
    other, other_session = login('reader_b')
    call('me', 'GET', '/me', 200, token=access)
    address, _ = call('create-own-address', 'POST', '/addresses', 201, token=access, body={
        'receiverName': '虚构冒烟收件人', 'phone': '13800000001', 'province': '测试省',
        'city': '测试市', 'district': '测试区', 'detail': '隔离开发冒烟地址', 'isDefault': True})
    call('horizontal-address-denied', 'DELETE', '/addresses/' + address['id'], 404, 'RESOURCE_NOT_FOUND', token=other)
    call('delete-own-address', 'DELETE', '/addresses/' + address['id'], 200, token=access)
    fresh, cookie = call('rotate-refresh', 'POST', '/auth/refresh', 200, session=original)
    call('refresh-replay', 'POST', '/auth/refresh', 401, 'AUTH_REQUIRED', session=original)
    call('family-access-revoked', 'GET', '/me', 401, 'AUTH_REQUIRED', token=fresh['accessToken'])
    call('logout', 'POST', '/auth/logout', 200, session=other_session)
    call('logout-access-revoked', 'GET', '/me', 401, 'AUTH_REQUIRED', token=other)
    admin, admin_session = login('admin_a')
    users, _ = call('admin-list-users', 'GET', '/admin/users?page=1&pageSize=20', 200, token=admin)
    # B 的 14 个开发角色账号必须保留；后续阶段可经正式注册增加独立联调会员。
    assert users['total'] >= 14
    call('admin-logout', 'POST', '/auth/logout', 200, session=admin_session)
    report = {'verifiedAtUtc': datetime.now(timezone.utc).isoformat(), 'checks': checks, 'total': len(checks)}
    (ROOT / '.artifacts/B-identity-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Identity smoke: {len(checks)} checks passed; credentials and tokens omitted')


if __name__ == '__main__':
    main()
