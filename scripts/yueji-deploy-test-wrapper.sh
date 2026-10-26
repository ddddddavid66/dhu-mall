#!/usr/bin/env bash
set -Eeuo pipefail
export DEPLOY_ENV=test
export YUEJI_PROJECT_ROOT=/opt/yueji
export YUEJI_RUNTIME_ENV=/etc/yueji/test.env
export YUEJI_COMPOSE_FILE=/opt/yueji/infra/compose.yml
exec bash "$YUEJI_PROJECT_ROOT/scripts/deploy-yueji-test-linux.sh"
