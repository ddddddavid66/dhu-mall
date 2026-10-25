#!/usr/bin/env bash
set -Eeuo pipefail
host=${APP_HOSTNAME:?APP_HOSTNAME is required}
base=${APP_BASE_URL:?APP_BASE_URL is required}
[[ "$host" =~ ^[A-Za-z0-9.-]+$ ]] || { echo 'invalid APP_HOSTNAME' >&2; exit 2; }
[[ "$base" == "https://$host" ]] || { echo 'APP_BASE_URL must be the exact HTTPS origin' >&2; exit 2; }
tmp=$(mktemp -d "${TMPDIR:-/tmp}/yueji-smoke.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT

check() {
  local name="$1" path="$2" expected_status="$3" expected_code="$4" status body
  body="$tmp/$name.json"
  status=$(curl --silent --show-error --max-time 10 --resolve "$host:443:127.0.0.1" \
    --output "$body" --write-out '%{http_code}' "$base$path") || return 1
  python3 - "$name" "$status" "$expected_status" "$expected_code" "$body" <<'PY'
import json, pathlib, sys
name, status, expected_status, expected_code, path = sys.argv[1:]
body = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
if status != expected_status or body.get("code") != expected_code:
    raise SystemExit(f"{name}: expected HTTP {expected_status}/{expected_code}, got {status}/{body.get('code')}")
if not isinstance(body.get("traceId"), str) or not body["traceId"]:
    raise SystemExit(f"{name}: missing traceId")
if expected_status == "501" and body.get("data") is not None:
    raise SystemExit(f"{name}: unexpected data for an unimplemented operation")
if expected_status == "200" and body.get("data") is None:
    raise SystemExit(f"{name}: missing response data")
print(json.dumps({"name": name, "httpStatus": int(status), "code": body["code"], "traceIdPresent": True}))
PY
}

check categories /api/v1/categories 200 OK
check products '/api/v1/products?page=1&pageSize=5' 200 OK
# Dashboard is an intentionally unimplemented API-89 contract; a 501 is expected.
check dashboard-contract /api/v1/dashboard 501 NOT_IMPLEMENTED
