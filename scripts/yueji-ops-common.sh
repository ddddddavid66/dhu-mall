#!/usr/bin/env bash
set -Eeuo pipefail

yueji_die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

yueji_init() {
  : "${YUEJI_PROJECT_ROOT:=/opt/yueji}"
  : "${YUEJI_RUNTIME_ENV:=/etc/yueji/${DEPLOY_ENV:-test}.env}"
  : "${YUEJI_COMPOSE_FILE:=$YUEJI_PROJECT_ROOT/infra/compose.yml}"
  [[ -d "$YUEJI_PROJECT_ROOT" ]] || yueji_die "project root missing: $YUEJI_PROJECT_ROOT"
  [[ -f "$YUEJI_RUNTIME_ENV" ]] || yueji_die "restricted runtime env missing: $YUEJI_RUNTIME_ENV"
  [[ -f "$YUEJI_COMPOSE_FILE" ]] || yueji_die "Compose file missing: $YUEJI_COMPOSE_FILE"
  local mode
  mode=$(stat -c '%a' "$YUEJI_RUNTIME_ENV")
  (( (8#$mode & 077) == 0 )) || yueji_die "runtime env must not be group/world accessible (mode=$mode)"
  command -v docker >/dev/null || yueji_die 'docker is required'
}

yueji_env() {
  local key="$1" value
  value=$(awk -v key="$key" '
    index($0, "=") > 0 {
      name = substr($0, 1, index($0, "=") - 1)
      if (name == key) { print substr($0, index($0, "=") + 1); found = 1; exit }
    }
    END { if (!found) exit 1 }
  ' "$YUEJI_RUNTIME_ENV") || return 1
  value=${value%$'\r'}
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then value=${value:1:${#value}-2}; fi
  if [[ "$value" == \'*\' && "$value" == *\' ]]; then value=${value:1:${#value}-2}; fi
  printf '%s' "$value"
}

yueji_dc() {
  docker compose --project-directory "$YUEJI_PROJECT_ROOT" \
    --env-file "$YUEJI_RUNTIME_ENV" -f "$YUEJI_COMPOSE_FILE" "$@"
}

yueji_require_empty_or_missing_dir() {
  local path="$1"
  if [[ -e "$path" ]]; then
    [[ -d "$path" ]] || yueji_die "restore target is not a directory: $path"
    [[ -z "$(find "$path" -mindepth 1 -maxdepth 1 -print -quit)" ]] || yueji_die "restore target is not empty: $path"
  fi
}

yueji_require_private_file() {
  local path="$1" mode
  [[ -f "$path" && -r "$path" ]] || yueji_die "restricted file missing or unreadable: $path"
  mode=$(stat -c '%a' "$path")
  (( (8#$mode & 077) == 0 )) || yueji_die "restricted file must not be group/world accessible (mode=$mode): $path"
}
