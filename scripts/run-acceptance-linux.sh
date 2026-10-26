#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
[[ "${YUEJI_TEST_DB_URL:-}" =~ ^jdbc:mysql://(127\.0\.0\.1|localhost):[0-9]+/yueji_mall_test(\?.*)?$ ]] || { echo 'isolated yueji_mall_test required' >&2; exit 2; }
mkdir -p .artifacts
(cd backend && ./mvnw -B -ntp -Dtest=E2EFixturePreparation test) > .artifacts/R-e2e-fixture.log 2>&1
export DB_URL="$YUEJI_TEST_DB_URL" DB_USERNAME="$YUEJI_TEST_DB_USERNAME" DB_PASSWORD="$YUEJI_TEST_DB_PASSWORD"
export PAYMENT_PROVIDER=LOCAL_MOCK ENABLE_MOCK_PAYMENT=true
export JWT_SIGNING_KEY="$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
export E2E_BACKEND_URL=http://127.0.0.1:18080 E2E_BASE_URL=http://127.0.0.1:5173
java -jar backend/target/yueji-mall-0.1.0-SNAPSHOT.jar --spring.profiles.active=test \
  --server.port=18080 --yueji.security.cookie-secure=false \
  --yueji.allowed-origins="$E2E_BASE_URL" --yueji.security.login-limit=1000 --yueji.security.write-limit=1000 \
  > .artifacts/R-e2e-backend.log 2>&1 &
backend_pid=$!
trap 'kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true' EXIT
ready=false
for _ in $(seq 1 60); do
  if curl -fsS "$E2E_BACKEND_URL/actuator/health/readiness" > .artifacts/R-e2e-readiness.json; then ready=true; break; fi
  kill -0 "$backend_pid" 2>/dev/null || { echo 'backend exited before readiness' >&2; exit 1; }
  sleep 1
done
[[ "$ready" == true ]] || { echo 'backend readiness timed out' >&2; exit 1; }
pnpm --dir frontend run test:acceptance
