@echo off
setlocal

cd /d "%~dp0"

set "APP_JAR=build\libs\readonly-db-mcp-0.1.0.jar"
if not exist "%APP_JAR%" (
    >&2 echo MCP server JAR not found: %CD%\%APP_JAR%
    >&2 echo Run gradlew.bat clean test bootJar first.
    exit /b 1
)

java -jar "%APP_JAR%" --spring.profiles.active=stdio
