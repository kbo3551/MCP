#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_JAR="$PROJECT_DIR/build/libs/readonly-db-mcp-0.1.0.jar"

if [[ ! -f "$APP_JAR" ]]; then
  printf 'MCP server JAR not found: %s\n' "$APP_JAR" >&2
  printf 'Run ./gradlew clean test bootJar first.\n' >&2
  exit 1
fi

cd "$PROJECT_DIR"
exec java -jar "$APP_JAR" --spring.profiles.active=stdio
