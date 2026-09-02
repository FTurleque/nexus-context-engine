#!/usr/bin/env bash
set -euo pipefail

HOST="${NEXUS_MANAGEMENT_HOST:-127.0.0.1}"
PORT="${NEXUS_MANAGEMENT_PORT:-9000}"

# Bash /dev/tcp avoids installing curl/wget into the runtime image solely for
# container health checks. The management listener is intentionally loopback-only.
exec 3<>"/dev/tcp/${HOST}/${PORT}"
printf 'GET /q/health/live HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == *" 200 "* ]]
