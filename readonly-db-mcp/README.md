# Read-only Database MCP Server

JDK 17, Spring Boot 3.x, Spring AI MCP로 구현한 데이터베이스 읽기 전용 MCP 서버다.
현재 제공 adapter는 Oracle이며, 프로젝트와 application 계층은 특정 DB 이름에
종속되지 않도록 구성했다.
기본 transport는 배포 가능한 Streamable HTTP이며, 로컬 프로세스 실행이 필요한
경우를 위해 STDIO profile도 유지한다.

## Architecture

```text
Codex
  -> Interface / MCP (DatabaseMcpTools)
  -> Application / UseCase (DatabaseReadService)
  -> Domain / Policy (SqlGuard, OwnerAccessPolicy, QueryLimitAppender, MaskingService)
  -> Infrastructure / DB Adapter
       -> Oracle (JdbcOracleReadRepository, OracleReadQueryDialect)
  -> Database
```

JPA와 MyBatis는 사용하지 않는다. 현재 Oracle adapter는 `JdbcTemplate`의 connection
callback과 순수 JDBC `PreparedStatement`로 수행한다. 다른 DB를 추가할 때는
`DatabaseReadRepository`와 `ReadQueryDialect` 구현을 추가한다.

## Requirements

- JDK 17
- Oracle DB 접속 URL
- 조회 대상 owner에 SELECT 권한만 가진 Oracle 계정

## Configuration

로컬 실행은 프로젝트 루트의 `.env` 파일을 자동으로 읽는다. 저장소에 포함된
`.env.example`을 참고해 실제 값을 `.env`에 입력한다.

```dotenv
ORACLE_DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1
ORACLE_DB_USER=mcp_reader
ORACLE_DB_PASSWORD=change-me
ORACLE_ALLOWED_OWNERS=*
LOG_DIR=logs
MCP_SERVER_ADDRESS=0.0.0.0
MCP_SERVER_PORT=9350
```

`.env`는 Git에서 제외되며 `.env.example`만 버전 관리한다. IntelliJ의 Working
directory가 프로젝트 루트이면 별도의 Run Configuration 환경변수 입력 없이 동작한다.
운영 환경에서는 OS 환경변수, Docker/Kubernetes Secret 또는 별도 secret manager를
우선 사용한다. OS 환경변수는 `.env` 값보다 우선한다.

`src/main/resources/application.yml`:

```yaml
db-mcp:
  allowed-owners:
    - APP
    - REPORTING
  default-limit: 100
  max-limit: 500
  query-timeout-seconds: 30
```

쉘 환경변수로 직접 실행하는 경우:

```bash
export ORACLE_DB_URL='jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
export ORACLE_DB_USER='mcp_reader'
export ORACLE_DB_PASSWORD='change-me'
export ORACLE_ALLOWED_OWNERS='*'
```

모든 owner를 허용하려면 `ORACLE_ALLOWED_OWNERS=*`로 설정한다. 이 모드에서 metadata
도구는 MCP DB 계정이 Oracle `ALL_TABLES`/`ALL_TAB_COLUMNS`를 통해 접근 가능한 범위를
사용한다. `list_tables("*")`는 접근 가능한 전체 owner의 테이블을 반환하고,
`describe_table`은 테이블 식별을 위해 실제 owner 이름이 필요하다. 일부 owner만
허용하려면 `APP,REPORTING`처럼 쉼표로 지정한다.
`run_select_query`는 두 모드 모두 DB 계정 자체의 SELECT 권한이 최종 접근 경계다.

## Build And Test

```bash
./gradlew clean test
./gradlew bootJar
```

생성 파일:

```text
build/libs/readonly-db-mcp-0.1.0.jar
```

## Run

### Docker Compose

WSL의 Docker daemon을 사용할 수 있으면 이 방식이 가장 단순하다. `.env`에 DB 정보를
입력한 뒤 프로젝트 루트에서 실행한다.

```bash
docker compose up --build -d
docker compose logs -f readonly-db-mcp
docker compose ps
```

기동 확인:

```bash
curl -i \
  -H 'Accept: text/event-stream' \
  http://127.0.0.1:9350/mcp
```

`Session ID required in mcp-session-id header` 응답이면 endpoint 접근은 정상이다.

Codex 등록:

```bash
codex mcp remove readonly_db
codex mcp add readonly_db --url http://127.0.0.1:9350/mcp
codex mcp get readonly_db
```

종료와 재빌드:

```bash
docker compose down
docker compose up --build -d
```

Compose는 포트를 WSL localhost에만 공개하고, `.env`는 이미지에 포함하지 않는다.
감사 로그는 호스트의 `logs/`에 유지된다.

`permission denied while trying to connect to the docker API`가 나오면 Docker Desktop의
`Settings > Resources > WSL Integration`에서 현재 WSL 배포판을 활성화하고 WSL
터미널을 다시 연다. WSL 내부에 Docker Engine을 직접 설치한 환경이면 현재 사용자를
`docker` 그룹에 추가한 뒤 새 로그인 세션을 시작해야 한다.

### Streamable HTTP

기본 profile은 `http`다. IntelliJ에서 `ReadonlyDbMcpApplication`을 실행하거나 다음
명령으로 서버를 시작한다.

```bat
gradlew.bat bootRun
```

기본 MCP URL:

```text
http://127.0.0.1:9350/mcp
```

기동 로그에서 다음 내용을 확인한다.

```text
Registered tools: 4
Started ReadonlyDbMcpApplication
```

### STDIO

기존 STDIO 실행이 필요한 경우:

```bat
gradlew.bat clean test bootJar
run-mcp.cmd
```

`run-mcp.cmd`와 `run-mcp.sh`는 `stdio` profile을 지정한다. STDIO에서는 stdout이
JSON-RPC 전용이므로 일반 로그는 stderr로 출력된다.

감사 로그는 기본적으로 `logs/db-mcp-audit.log`에 기록된다.

## Codex MCP Configuration

### Streamable HTTP

먼저 IntelliJ 또는 서버에서 애플리케이션을 실행한 다음 Codex에 URL을 등록한다.

```powershell
codex mcp add readonly_db --url http://127.0.0.1:9350/mcp
```

등록 확인:

```powershell
codex mcp list
codex mcp get readonly_db
```

등록 후 Codex를 새로 시작한다. HTTP 방식에서는 애플리케이션이 먼저 실행 중이어야
하며, Codex는 JAR 프로세스를 직접 실행하지 않는다.

수동 TOML 설정:

```toml
[mcp_servers.readonly_db]
url = "http://127.0.0.1:9350/mcp"
```

### STDIO

```powershell
codex mcp add readonly_db_stdio -- cmd.exe /c C:\Users\USER\Desktop\dev\02.project\test\readonly-db-mcp\run-mcp.cmd
```

연결이 꼬였으면 제거 후 다시 등록한다.

```powershell
codex mcp remove readonly_db
```

### Deployment Warning

Windows IntelliJ와 WSL Codex 사이의 접근을 위해 기본 bind는 `0.0.0.0:9350`이다.
현재 구현에는 HTTP 인증이 없으므로 신뢰할 수 없는 네트워크에 직접 노출하지 않는다.
원격 배포 시 TLS와 인증을 제공하는 reverse proxy/API gateway 뒤에 배치하고 Codex에는
실제 HTTPS MCP URL을 등록한다.

## Tools

| Tool | Description |
|---|---|
| `list_tables(owner)` | 허용된 owner의 테이블과 코멘트 조회 |
| `describe_table(owner, tableName)` | 허용된 테이블의 컬럼 정의 조회 |
| `search_tables(keyword)` | 허용된 owner 범위에서 테이블명/코멘트 검색 |
| `run_select_query(sql, limit)` | 보호 정책과 row limit을 적용한 SELECT 실행 |

## Security Policy

`run_select_query`는 다음 방어를 순서대로 적용한다.

1. SQL은 `SELECT` 또는 `WITH`로 시작해야 한다.
2. DML, DDL, transaction, PL/SQL 실행 토큰을 차단한다.
3. `DBMS_`, `UTL_`, `JAVA`, `DIRECTORY`, `FOR UPDATE`를 차단한다.
4. 세미콜론, SQL comment, 다중 statement를 차단한다.
5. 기본 limit 100, 최대 limit 500을 적용한다.
6. 사용자 SQL을 직접 실행하지 않고 다음 Oracle SQL로 감싼다.

```sql
SELECT *
FROM (
    {사용자 SELECT}
)
WHERE ROWNUM <= {appliedLimit}
```

7. JDBC connection에 `setReadOnly(true)`를 적용한다.
8. 모든 `PreparedStatement`에 query timeout을 적용한다.
9. 민감 컬럼명은 응답 전 `******`로 마스킹한다.
10. tool명, SQL, limit, 성공 여부, row 수, 실행 시간, 오류만 감사 로그에 기록하고
    조회 row 데이터는 기록하지 않는다.

마스킹 대상에는 `PASSWORD`, `PWD`, `TOKEN`, `SECRET`, `API_KEY`, `EMAIL`, `PHONE`,
`MOBILE`, `TEL`, `SSN`, `REG_NO`가 포함된다.

> **Warning:** 코드의 read-only 검사는 애플리케이션 방어층이다. 운영 DB에서는 이를
> 우회하더라도 쓰기가 불가능하도록 MCP 전용 Oracle
> 계정 자체에 필요한 schema/object의 `SELECT` 권한만 부여해야 한다. DML, DDL,
> execute, directory, network package 권한과 과도한 dictionary 권한을 부여하지 않는다.

## Database Extension

현재 runtime dependency와 datasource 설정은 Oracle 기준이다. PostgreSQL/MySQL 등을
추가하려면 다음 adapter를 별도 profile 또는 module로 제공한다.

1. 해당 JDBC driver
2. `DatabaseReadRepository` 구현
3. DB별 metadata query
4. `ReadQueryDialect` 구현 (`LIMIT`, `FETCH FIRST` 등)
5. DB별 위험 함수와 procedure를 반영한 SQL guard 정책

Application, MCP tool, masking, audit 계층은 그대로 재사용할 수 있다.

## Current Limitations

- SQL parser가 아닌 보수적 lexical guard이므로 SQL comment를 포함한 정상 쿼리도 거부한다.
- Oracle 사용자 정의 함수가 내부에서 부작용을 일으키는지 코드만으로 판별할 수 없다.
  따라서 SELECT 전용 DB 계정이 필수다.
- SQL comment와 quoted identifier를 포함한 쿼리는 보수적으로 거부한다.
- CLOB 응답은 4,096자에서 잘리고 BLOB은 크기 정보만 반환한다.
- 기본 transport는 Streamable HTTP다. 현재 애플리케이션 자체 HTTP 인증은 없으므로
  원격 배포에는 인증/TLS reverse proxy가 필요하다.

## Verification

```bash
./gradlew test
```

단위 테스트:

- `SqlGuardTest`
- `QueryLimitAppenderTest`
- `OwnerAccessPolicyTest`
- `MaskingServiceTest`

실제 Oracle 통합 검증은 SELECT 전용 테스트 계정으로 서버를 실행한 뒤 4개 MCP tool을
호출하고, `logs/db-mcp-audit.log`에 row 데이터 없이 감사 필드만 남는지 확인한다.
