#!/bin/sh
set -eu

MODE="${1:-${NEXUS_MODE:-rest}}"
if [ "$#" -gt 0 ]; then
  shift
fi

JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then
  JAVA_BIN="java"
fi

case "$MODE" in
  cli)
    exec "$JAVA_BIN" -jar /opt/nexus/lib/nexus-cli.jar "$@"
    ;;
  mcp)
    # MCP STDIO reserves stdout for JSON-RPC. Do not add shell logging here.
    exec "$JAVA_BIN" -jar /opt/nexus/lib/nexus-mcp.jar "$@"
    ;;
  assistant)
    exec "$JAVA_BIN" -jar /opt/nexus/lib/nexus-assistant-clients.jar "$@"
    ;;
  rest)
    REST_HOST="${NEXUS_REST_HOST:-0.0.0.0}"
    REST_PORT="${NEXUS_REST_PORT:-8080}"
    exec "$JAVA_BIN" \
      "-Dquarkus.http.host=${REST_HOST}" \
      "-Dquarkus.http.port=${REST_PORT}" \
      -jar /opt/nexus/rest/quarkus-run.jar "$@"
    ;;
  shell)
    exec /bin/sh "$@"
    ;;
  *)
    # Treat an unknown first token as a CLI argument for ergonomic `docker run image --version` usage.
    exec "$JAVA_BIN" -jar /opt/nexus/lib/nexus-cli.jar "$MODE" "$@"
    ;;
esac
