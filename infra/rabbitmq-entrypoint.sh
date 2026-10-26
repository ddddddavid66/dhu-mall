#!/bin/sh
set -eu
test -r /run/secrets/rabbitmq_password
RABBITMQ_DEFAULT_PASS=$(cat /run/secrets/rabbitmq_password)
export RABBITMQ_DEFAULT_PASS
exec /usr/local/bin/docker-entrypoint.sh rabbitmq-server
