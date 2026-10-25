#!/bin/sh
set -eu

load_secret() {
    name="$1"
    path="$2"
    if [ -n "$path" ]; then
        if [ ! -r "$path" ]; then
            printf 'required secret file is unreadable: %s\n' "$name" >&2
            exit 1
        fi
        value=$(cat "$path")
        export "$name=$value"
    fi
}

load_secret DB_PASSWORD "${DB_PASSWORD_FILE:-}"
load_secret JWT_SIGNING_KEY "${JWT_SIGNING_KEY_FILE:-}"
load_secret ALIPAY_APP_PRIVATE_KEY "${ALIPAY_APP_PRIVATE_KEY_FILE:-}"
load_secret ALIPAY_PUBLIC_KEY "${ALIPAY_PUBLIC_KEY_FILE:-}"

exec java -XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp/yueji -jar /opt/yueji/app.jar
