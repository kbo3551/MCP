# knowledge-mcp

프롬프트를 주고받는 동안 **사용자 패턴과 요구사항을 관찰해서 저장**하고, 반복 관찰된 것만 **규칙(MD)으로 승격**시키고, 그 규칙을 **다시 에이전트에 주입**하는 MCP 서버입니다.

Spring Boot 3.3.5 / Java 17 / Gradle 8.7.

```
관찰(observe) ──► 중복 병합 ──► 승격(OBSERVED→CANDIDATE→ACTIVE) ──► MD 생성 ──► 주입(context)
     ▲                                                                              │
     └──────────────────────── 다음 세션에서 이 규칙을 따른 결과를 또 관찰 ◄──────────┘
```

핵심 설계 판단 하나: **한 번 말한 것은 규칙이 아니다.** 한 번 들은 요구를 바로 규칙으로 박으면 지식베이스가 일회성 요청으로 오염되고, 오염된 규칙이 주입되면 없는 것보다 나쁩니다. 그래서 같은 내용이 `active-hits`(기본 3)회 관찰되어야 주입 대상이 되고, 사용자가 명시적으로 승인(`knowledge_confirm`)하면 즉시 승격합니다.

---

## 1. 빌드 & 실행

이 PC의 전역 `JAVA_HOME`은 32비트 JDK 8이라 Java 17 산출물을 실행할 수 없습니다. `gradle.properties`가 Gradle 데몬만 JDK 17로 띄우므로, **실행할 때는 JDK 17의 java.exe를 절대경로로 지정**해야 합니다.

```powershell
cd C:\Users\USER\Desktop\dev\02.project\temp\devTool\knowledge-mcp

# 테스트 (첫 실행은 h2 등 의존성 다운로드로 조금 걸립니다)
.\gradlew.bat test --console=plain

# 실행 가능한 jar
.\gradlew.bat bootJar
# -> build\libs\knowledge-mcp.jar

# HTTP 모드로 띄우기 (127.0.0.1:8765)
& 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe' -jar build\libs\knowledge-mcp.jar

# stdio 모드 단독 확인 (MCP 클라이언트가 하는 일을 손으로)
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' |
  & 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe' -jar build\libs\knowledge-mcp.jar --stdio
```

저장 위치는 기본 `%USERPROFILE%\.knowledge-mcp` 입니다.

```
~/.knowledge-mcp/
  db/knowledge.mv.db      H2 파일 (패턴 + 근거 로그)
  rules/preferences.md    카테고리별 생성 MD
  rules/conventions.md
  ...
  AGENT_CONTEXT.md        주입용 번들 (점수순, 예산 내)
  logs/mcp-stdio.log      stdio 모드 로그 ← 클라이언트가 "서버 실패"라고만 할 때 볼 곳
```

## 2. MCP 클라이언트에 등록

### stdio (권장 — 데스크톱 클라이언트가 자식 프로세스로 띄움)

```json
{
  "mcpServers": {
    "knowledge": {
      "command": "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot\\bin\\java.exe",
      "args": [
        "-jar",
        "C:\\Users\\USER\\Desktop\\dev\\02.project\\temp\\devTool\\knowledge-mcp\\build\\libs\\knowledge-mcp.jar",
        "--stdio"
      ],
      "env": { "KNOWLEDGE_MCP_TRANSPORT": "stdio" }
    }
  }
}
```

stdio 모드에서 **stdout은 프로토콜 전용**입니다. 배너·콘솔 로그·stray print 한 줄이면 클라이언트는 이유 없이 "서버가 깨졌다"고만 보고합니다. 그래서 `System.out`은 Spring 시작 전에 stderr로 돌리고, 전송 계층은 `FileDescriptor.out`에 직접 쓰고, `logback-spring.xml`의 `stdio` 프로파일에는 콘솔 appender가 아예 없습니다.

### HTTP

`POST http://127.0.0.1:8765/mcp` — stdio와 **같은 디스패처**입니다. id 없는 notification은 `202 Accepted` + 빈 본문.

## 3. 툴 12개

| 툴 | 언제 부르는가 |
|---|---|
| `knowledge_observe` | 사용자가 교정하거나 "항상/절대/다음부터는"이라고 말한 순간. 규칙 1건 |
| `knowledge_observe_batch` | 한 턴에서 배운 게 여러 건일 때 (최대 20건) |
| `knowledge_context` | **작업 시작 시.** 축적된 규칙을 MD 한 덩이로 받음 (`query`로 주제 한정) |
| `knowledge_search` | 비슷한 게 이미 있는지 확인 / "X에 대해 뭘 아는가" |
| `knowledge_inspect` | 규칙 하나의 전문 + **근거 로그**. "이 규칙 왜 있냐"에 답하는 유일한 경로 |
| `knowledge_confirm` | 사용자가 명시적으로 승인 → 즉시 ACTIVE |
| `knowledge_archive` | 규칙이 낡았거나 틀렸을 때 (불편하다는 이유로는 금지) |
| `knowledge_sync_rules` | MD 파일 강제 재생성 |
| `knowledge_import_markdown` | 기존 AGENTS.md / steering 파일로 초기 적재 |
| `knowledge_conflicts` | 서로 반대일 수 있는 규칙 쌍 보고 (자동 해결 안 함) |
| `knowledge_review` | 오래된 ACTIVE / 승격 못 한 CANDIDATE / 식은 관찰 — 정리 후보 (자동 삭제 안 함) |
| `knowledge_stats` | 상태·카테고리별 집계, 임계값 |

입력 검증은 **잘라내지 않고 거부**합니다. `statement` 300자 / `antiPattern` 800자 / `rationale` 1200자를 넘으면 "한 줄로 요약해서 다시"라는 메시지로 반려합니다. 규칙을 조용히 자르면 규칙처럼 읽히는 반쪽 문장이 남고, 그게 주입되면 원문보다 나쁩니다. 여러 줄로 들어온 `statement`는 한 줄로 정규화됩니다.

등록 파일 템플릿은 `C:\Users\USER\Desktop\dev\02.project\temp\devTool\knowledge-mcp\docs\mcp-config.example.json` 에 있습니다. `autoApprove`에는 읽기 전용 툴만 넣어 뒀습니다 — 쓰기 툴은 매번 승인받는 쪽이 맞습니다.

## 4. 주입 경로 3가지

요청의 "자동으로 주입" 부분입니다. MCP 클라이언트는 툴을 알아서 호출해주지 않으므로 세 갈래를 다 깔았습니다.

1. **initialize 응답의 `instructions`** — 서버 연결 시점에 현재 규칙이 시스템 컨텍스트로 들어갑니다. 툴 호출 0회. `knowledge.inject.on-initialize: true`
2. **MCP resource** — `knowledge://context`, `knowledge://rules/*.md`. 리소스를 자동 첨부하는 클라이언트가 집어갑니다. prompt `knowledge-context`도 같은 번들을 user 메시지로 줍니다.
3. **파일 export** — `knowledge.sync.export-targets`에 절대경로를 넣으면 `AGENT_CONTEXT.md`를 그 위치에 미러링합니다. Kiro의 steering 파일을 겨냥한 경로입니다.

```yaml
knowledge:
  sync:
    export-targets:
      - C:/Users/USER/Desktop/dev/02.project/temp/devTool/.kiro/steering/knowledge.md
```

export와 `rules/*.md`는 **생성 블록(`<!-- knowledge-mcp:begin -->` ~ `:end`)만 교체**합니다. 블록 밖에 사람이 쓴 내용은 재생성해도 보존됩니다 — 생성 파일 워크플로가 실제 레포에서 살아남는 유일한 방법입니다.

자동 sync는 항상 `knowledge.default-project-key` 하나만 투영합니다. 디스크의 MD는 **한 프로젝트 컨텍스트의 투영**이라서, sync를 촉발한 관찰의 projectKey를 따라가게 만들면 어떤 관찰이 마지막이었냐에 따라 파일이 프로젝트 규칙을 포함/제외로 왕복합니다. 다른 프로젝트를 일부러 투영하려면 `knowledge_sync_rules`에 `projectKey`를 명시하세요.

## 5. 저장 구조와 승격 규칙

`knowledge_pattern` 1행 = 규칙 1개. 같은 내용을 다시 보면 **행을 추가하지 않고 hit_count를 올립니다.**

- **동일성**: `fingerprint = sha256(category | scope | 정규화된 문장)`. 정규화는 NFKC + 소문자 + 구두점 제거 + 불용어 제거. `category`와 `scope`가 지문에 들어가는 건 의도적입니다 — 같은 문장이 전역 취향이면서 특정 프로젝트 규칙일 수 있고, 그 둘을 합치면 틀립니다.
- **유사 중복**: 지문이 달라도 토큰 Jaccard ≥ `merge-threshold`(0.82)면 기존 행에 병합합니다. "linter 돌려라"와 "linter 돌려라 커밋 전에"가 별개 규칙으로 두 줄 찍히는 것을 막습니다.
- **승격**: 2회 → CANDIDATE, 3회 → ACTIVE(주입 대상). `confirm` → 즉시 ACTIVE + confidence 1.0.
- **병합 시 빈 값으로 덮지 않음**: 짧게 다시 관찰된 건이 먼저 들어온 rationale을 지우지 않습니다.
- **근거**: `pattern_evidence`에 관찰 1건마다 1행. 몇 달 뒤 "이 규칙 왜 있냐"에 답하는 유일한 수단입니다.
- **순위**: `confidence × (1+log(hits)) × 0.5^(경과일/half-life)`. 지난주 교정이 봄에 만든 규칙을 이깁니다.
- **모순**: 겹침이 `[conflict-floor, merge-threshold)`면 "모순 후보"로 **보고만** 합니다. 둘 중 뭘 살릴지는 사람 판단입니다.

번들에는 **문자 예산**(기본 6000자)이 있습니다. 무한히 커지는 지식베이스를 통째로 붙이면 컨텍스트를 넘기는 순간 쓸모가 없어지므로, 점수순으로 자르고 잘렸다는 사실을 문서에 명시해 에이전트가 `query`로 더 끌어가게 합니다.

## 6. REST API (지식 공유용)

MCP를 모르는 쪽(다른 에이전트, 팀원 에디터, CI, 대시보드)도 같은 저장소를 씁니다. 툴 계층과 REST는 **한 저장소의 두 얼굴**이지 동기화해야 할 두 저장소가 아닙니다.

```
GET  /api/health                                 UP/503 + 상태별 건수
GET  /api/context?projectKey=&query=&maxChars=   주입 번들 (text/markdown)
GET  /api/patterns?q=&category=&status=&limit=
GET  /api/patterns/{id}
POST /api/patterns                               관찰 기록
POST /api/patterns/{id}/confirm
POST /api/patterns/{id}/archive?reason=
GET  /api/patterns/{id}/evidence
GET  /api/conflicts
GET  /api/review?staleDays=90
POST /api/rules/sync
GET  /api/stats
```

```powershell
Invoke-RestMethod http://127.0.0.1:8765/api/context
Invoke-RestMethod -Method Post http://127.0.0.1:8765/api/patterns -ContentType 'application/json' -Body (@{
  statement   = 'Gradle 검증은 짧게 끝낼 것'
  antiPattern = '매번 오래 걸리는 빌드 검증으로 기다리게 하지 말 것'
  category    = 'PREFERENCE'
} | ConvertTo-Json)
```

### ⚠ 인증은 기본 꺼져 있습니다

`knowledge.security.token`이 비어 있으면 **쓰기 가능한 `/mcp`를 포함해 모든 HTTP 엔드포인트가 무인증**입니다. 이게 허용되는 이유는 `server.address: 127.0.0.1`로 묶여 있어서 외부에서 라우팅이 안 되기 때문입니다. **바인드 주소를 바꾸거나 리버스 프록시를 붙이기 전에 토큰을 먼저 설정하세요.** 설정하면 `X-Knowledge-Token` 또는 `Authorization: Bearer`가 필수가 됩니다.

여러 사람이 공유하는 서버로 쓸 생각이면 토큰만으로는 부족합니다(작성자 구분·권한 없음). 그 단계에서는 인증을 프록시로 빼고 `author`를 인증 주체로 채우는 쪽이 맞습니다.

## 7. 설정 전체

`src/main/resources/application.yml`에 주석과 함께 다 들어 있습니다. 자주 만질 것만:

| 키 | 기본 | 의미 |
|---|---|---|
| `knowledge.home` | `~/.knowledge-mcp` | DB·MD·로그 루트 |
| `knowledge.promotion.active-hits` | 3 | 주입 대상이 되는 관찰 횟수. 일회성이 쌓이면 올리세요 |
| `knowledge.promotion.merge-threshold` | 0.82 | 유사 중복 병합 기준 |
| `knowledge.bundle.max-chars` | 6000 | 주입 번들 예산 |
| `knowledge.bundle.half-life-days` | 45 | 최근성 감쇠 반감기 |
| `knowledge.inject.on-initialize` | true | initialize 응답에 규칙 동봉 |
| `knowledge.sync.export-targets` | `[]` | AGENT_CONTEXT.md 미러링 경로 |
| `knowledge.default-project-key` | `""` | projectKey 미지정 시 기본값 |

## 8. 구현 메모 (왜 이렇게 했는지)

- **MCP SDK 미사용**: 필요한 표면이 initialize + tools + resources + prompts뿐이고 와이어 포맷은 줄 단위 JSON입니다. 직접 들고 있으면 stdio/HTTP 두 전송이 전송별 글루 없이 같은 디스패처를 씁니다.
- **JPA 대신 JDBC(`JdbcClient`)**: stdio 모드는 MCP 세션마다 프로세스가 새로 뜨므로 Hibernate 부트스트랩 비용을 매번 냅니다. 테이블 2개에 SQL 직접 쓰는 게 더 빠르고 더 읽힙니다.
- **H2 `AUTO_SERVER=TRUE`**: stdio 프로세스와 HTTP 프로세스가 같은 파일을 공유합니다. 프로세스별 저장소가 아니라 공유 저장소가 되는 지점입니다. 그래서 `observe`의 조회-후-삽입이 경합할 수 있고, `DuplicateKeyException`을 잡아 **원래 하려던 병합으로 되돌립니다** — 관찰이 실패로 끝나는 것보다 맞습니다.
- **툴 실패는 JSON-RPC 에러가 아니라 `isError` 결과**: 모델이 호출을 고칠 수 있게 이유를 돌려줘야 합니다. 툴 이름 자체가 틀린 경우만 `-32602`.
- **유사도는 임베딩 없이 Jaccard**: 같은 문장을 하나로 접고, 비슷한 문장은 병합 후보로 *제안*만 하면 됩니다. 그 이상은 이 규모에서 과합니다.
- **`ObjectNode.set`은 제네릭(`<T extends JsonNode> T`)**: 체이닝하면 호출 지점마다 타입 추론에 의존하게 되므로 `McpServer.wrap()`으로 풀어 썼습니다.
- **모순·정리는 보고만**: `knowledge_conflicts`와 `knowledge_review`는 아무것도 바꾸지 않습니다. 둘 중 뭘 살릴지, 오래된 규칙이 무효인지 아니면 아무도 어길 일이 없었던 것인지는 사람만 구분할 수 있습니다.

## 9. 검증 상태

- 작성 완료: 메인 25개 파일, 테스트 5개 클래스.
- `javap`로 실제 확인한 것(빌드 아님): `JdbcClient.StatementSpec.params(Map)` / `update(KeyHolder)` / `query().listOfRows()`, `GeneratedKeyHolder.getKeyList()`, `JsonNode.asText(String)`, `ObjectNode.set`의 제네릭 시그니처 — Spring Framework 6.1.14 + Jackson 2.17.2 (Boot 3.3.5가 관리하는 버전) 기준.
- **미검증: 컴파일과 테스트 실행.** 사용자가 직접 `.\gradlew.bat test` 로 확인합니다.
- 실제 MCP 클라이언트 연결(stdio 핸드셰이크)도 아직 미확인입니다.

테스트가 실제로 잡아내는 것:

| 테스트 | 무엇을 막는가 |
|---|---|
| `TextNormalizerTest` | 같은 규칙이 띄어쓰기 때문에 두 행으로 갈리는 것 / scope·category 뭉개짐 |
| `RuleMarkdownServiceTest` | 재생성이 사람이 쓴 메모를 지우는 것 |
| `MarkdownImportServiceTest` | 산문·코드블록이 규칙으로 들어오는 것, 항목 하나 때문에 import 전체가 죽는 것 |
| `PatternServiceTest` | 1회 관찰이 규칙으로 주입되는 것, 재표현이 중복 행을 만드는 것, 문단이 조용히 잘리는 것, 정리 후보 비교 방향 역전 |
| `McpServerProtocolTest` | 버전 네고 실패, notification에 응답, 툴 실패가 프로토콜 에러로 새는 것, 대량 투입 |

## 10. 남은 과제

1. 승격 임계값을 카테고리별로 분리 (PITFALL은 1회로도 충분한 경우가 있음)
2. `author`를 인증 주체와 연결 — 현재는 호출자가 자기 신고
3. `knowledge_context` 결과 캐싱 — 매 호출마다 전체 재조립
4. 팀 공유 모드: 프록시 인증 + 개인/팀 스코프 분리
5. `knowledge_review`에서 바로 일괄 archive 하는 경로 (지금은 id 하나씩)
6. 프로젝트별 MD를 `rules/project-<key>/`로 분리 — 지금은 한 프로젝트만 투영
