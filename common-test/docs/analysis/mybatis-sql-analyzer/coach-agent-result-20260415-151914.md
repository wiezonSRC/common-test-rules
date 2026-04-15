# 코드 리뷰 & 기술 면접 시뮬레이션 분석 보고서

- 분析일시: 2026-04-15 15:19:14
- 분析 대상 프로젝트: mybatis-sql-analyzer-core + mybatis-sql-analyzer-plugin

---

<step1_code_analysis>

## 🔍 Step 1. 코드 핵심 로직 파악

### 프로젝트 전체 목적 추론

이 프로젝트는 **MyBatis Mapper XML에 작성된 동적 쿼리를 정적 분석하여 AI(Gemini/ChatGPT)에게 쿼리 튜닝 프롬프트를 자동 생성하는 Gradle 플러그인 도구**입니다. 개발자가 `--queryId` 파라미터를 지정하면, 해당 쿼리의 동적 태그를 실행 가능한 SQL로 변환(fakeSql)하고, H2/MySQL에서 EXPLAIN을 실행한 뒤 메타데이터까지 수집하여 LLM에 전달하기 좋은 컨텍스트를 만들어 냅니다.

### 모듈 구성

**mybatis-sql-analyzer-core** (라이브러리 모듈)
- `SqlExtractor` : MyBatis Mapper XML을 DOM 파싱하여 `<select>`, `<update>`, `<insert>`, `<delete>` 노드를 찾고, 동적 태그(`<if>`, `<where>`, `<set>`, `<foreach>`, `<choose>`, `<trim>`, `<include>` 등)를 재귀적으로 순회하며 실행 가능한 fakeSql로 변환합니다. `<sql>` 태그는 FQN(namespace.id) 기반으로 Map에 캐싱하여 `<include>` 처리 시 재사용합니다.
- `JdbcAnalyzer` : JSqlParser로 fakeSql에서 테이블명을 추출하고, `DatabaseMetaData` API를 통해 컬럼·인덱스 정보를 수집합니다. `MessageFormat`을 사용해 EXPLAIN 쿼리를 조립하고 실행합니다.
- `PromptGenerator` : 위 두 클래스를 오케스트레이션하여 `[Original Query] / [Remove Tag Query] / [Explain] / [MetaData]` 섹션으로 구성된 프롬프트 문자열을 생성합니다.

**mybatis-sql-analyzer-plugin** (Gradle 플러그인 모듈)
- `SqlAnalyzerPlugin` : `generateSqlPrompt` 태스크를 등록합니다.
- `SqlAnalyzerTask` : `--queryId` CLI 옵션 또는 Extension 설정값을 받아 core 라이브러리를 호출합니다.
- `SqlAnalyzerExtension` : DSL 블록(`sqlAnalyzer { ... }`)의 설정 모델을 정의합니다.
- **단, plugin 모듈의 세 클래스는 모두 주석 처리(전체 코멘트 아웃) 상태**이며 현재 동작하지 않습니다.

### 핵심 흐름 요약

```
queryId 입력
  → SqlExtractor.getQueryIdDetail()     // DOM으로 해당 Node 탐색
  → SqlExtractor.getSqlSnippetRegistry() // <sql> 태그 FQN 캐싱
  → SqlExtractor.buildFakeSql()          // 동적 태그 재귀 변환 → fakeSql
  → JdbcAnalyzer.getExplainInfo()        // EXPLAIN 실행
  → JdbcAnalyzer.extractTableMethod()   // JSqlParser로 테이블명 추출
  → JdbcAnalyzer.getMetaDataInfo()      // DDL+Index 메타데이터 수집
  → PromptGenerator.generatePrompt()    // 최종 프롬프트 조립
```

</step1_code_analysis>

<step2_architecture_analysis>

## 📐 Step 2. 알고리즘 / 자료구조 복잡도 분석

### 사용된 알고리즘 및 자료구조

- **DOM 트리 재귀 순회**: `buildFakeSql()`은 DOM Node 트리를 재귀적으로 내려가며 문자열을 조립합니다.
- **HashMap (sqlSnippetRegistry)**: `<sql>` 태그를 FQN Key → XML 문자열 Value로 캐싱합니다.
- **파일 시스템 탐색**: `Files.walk()` + Stream 필터로 매퍼 XML 파일을 수집합니다.
- **JSqlParser AST**: fakeSql을 파싱하여 테이블명을 추출합니다.
- **JDBC DatabaseMetaData API**: 표준 JDBC 인터페이스로 컬럼·인덱스 메타데이터를 조회합니다.
- **Switch Expression (Java 14+)**: `case "if", "when", "otherwise":` 형태의 다중 케이스 패턴 활용.

### 시간 복잡도 (Time Complexity)

- **전체 (PromptGenerator.generatePrompt 기준)**: O(F × N × D) + O(T × M)
  - F: 매퍼 XML 파일 수
  - N: XML 내 노드 수
  - D: DOM 트리의 최대 깊이
  - T: 추출된 테이블 수
  - M: JDBC 메타데이터 조회 비용 (DB 왕복)

- **주요 연산별 분석**:
  - `getSqlSnippetRegistry()`: O(F × N) — 모든 파일의 모든 `<sql>` 노드를 한 번 순회
  - `getQueryIdDetail()`: O(N) — 단일 파일 DOM 순회로 queryId 탐색
  - `buildFakeSql()`: O(D × W) — 노드 트리의 너비(W)와 깊이(D)에 비례하는 재귀 순회. `<include>` 태그마다 snippet XML을 **재파싱**하므로 include 수(I)가 많을수록 O(I × P) 추가 비용 발생
  - `extractTableMethod()`: O(N log N) 수준 — JSqlParser의 AST 구축 비용
  - `getMetaDataInfo()`: O(T × C) — 테이블 수 × 컬럼/인덱스 조회 (DB 왕복이 지배적)

### 공간 복잡도 (Space Complexity)

- **전체**: O(F × S) — 모든 파일의 `<sql>` 태그 내용이 문자열로 메모리에 보관됨
- **상세 분석**:
  - `sqlSnippetRegistry (HashMap)`: `<sql>` 태그 XML 문자열 전체를 값으로 보관. 대형 프로젝트에서 수백 개의 snippet이 등록될 경우 힙 사용량이 증가함.
  - `buildFakeSql()` 재귀 호출 스택: 동적 쿼리 중첩 깊이에 비례. 실제 MyBatis XML에서 중첩이 수십 단계를 넘기 어려우므로 스택 오버플로 위험은 낮음.
  - `<include>` 처리 시 snippet마다 새 `DocumentBuilderFactory` / `DocumentBuilder` / `Document` 객체를 생성하므로 include가 많은 파일에서 GC 압박 발생 가능.

### 최적화 가능성

1. **include 재파싱 비용 제거**: `sqlSnippetRegistry`의 Value를 XML 문자열 대신 **파싱된 `Node` 객체(또는 경량 DSL)**로 저장하면 `buildFakeSql()`에서 snippet마다 `DocumentBuilder.parse()`를 반복 호출하는 O(I × P) 비용을 O(1) 캐시 조회로 줄일 수 있습니다. 단, `Node`는 `Document`에 종속된 생명주기를 가지므로 `Document.adoptNode()` 또는 별도 경량 AST 구조가 필요합니다(메모리 학습 `pattern_dom_node_lifecycle.md` 참조).
2. **getQueryIdDetail의 다중 파일 탐색 미분리**: 현재 단일 파일만 받도록 설계되어 있으나, 동일 queryId가 4개 파일에 존재하는 테스트 픽스처가 증명하듯 실제 환경에서는 다중 파일 탐색 로직이 필요합니다. 파일 탐색을 분리하면 재사용성도 높아집니다.
3. **StringBuilder 연결 패턴**: `fakeSql.append(text).append(" ")` 방식은 불필요한 공백을 누적시킵니다. 최종 출력 전 `replaceAll("\\s+", " ").trim()`으로 정리하는 것이 가독성 면에서 유리합니다.

</step2_architecture_analysis>

<step3_code_review>

## 🧐 Step 3. 가독성 & 엣지 케이스 리뷰

### 가독성 평가

**긍정적인 부분**
- `@Slf4j` Lombok 어노테이션으로 로깅 설정이 깔끔합니다.
- `buildFakeSql()`의 `switch` 케이스가 MyBatis 태그 이름과 1:1로 대응되어 직관적으로 읽힙니다.
- `[핵심 1]`, `[핵심 2]`, `[핵심 3]` 인라인 주석이 핵심 로직의 의도를 잘 설명합니다.
- 유틸리티 성격의 클래스에 `private` 생성자를 명시하여 인스턴스화를 방지한 것은 좋은 습관입니다.

**개선이 필요한 부분**
- `SqlExtractor.sqlSnippetRegistry` 필드가 `static` + package-private (접근 제한자 없음)으로 선언되어 있습니다. 이는 `SqlExtractorTest`의 `set()` 메서드에서 외부에서 직접 주입(`SqlExtractor.sqlSnippetRegistry = ...`)할 목적으로 만든 것으로 보이는데, **서비스 레이어가 테스트 편의를 위해 내부 상태를 노출하는 안티패턴**입니다. 실제로 `buildFakeSql()`은 파라미터로 `sqlSnippetRegistry`를 받으므로 이 필드는 불필요하게 공개되어 있습니다.
- 메서드명 `extractTableMethod`는 목적어와 행위가 혼재되어 있습니다. `extractTables` 또는 `findUsedTables`가 더 명확합니다.
- `JdbcAnalyzer.getExplainInfo()`에서 `ResultSet rs = null`을 try 블록 밖에 선언한 후 try-with-resources와 혼용하는 패턴은 불필요합니다. `ResultSet`도 try-with-resources 안에 포함할 수 있습니다.
- `getExplainInfo()`의 EXPLAIN 결과 파싱 로직(`rs.getString(i)` — i는 1로 고정)이 H2에서는 동작하지만, MySQL/MariaDB의 EXPLAIN 결과는 컬럼이 10개 이상이며 첫 번째 컬럼만 읽는 것은 결과를 매우 불완전하게 만듭니다(하단 엣지 케이스 표 참조).
- 테스트 3개(`JdbcAnalyzerTest`, `SqlExtractorTest`, `PromptGeneratorTest`)의 `@BeforeEach` 셋업 로직(4개 테이블 + 4개 인덱스 DDL)이 완전히 동일하게 복사되어 있습니다. **DRY(Don't Repeat Yourself) 원칙 위반**이며, 테이블 구조 변경 시 3곳을 모두 수정해야 합니다.
- `PromptGenerator.generatePrompt()` 시그니처가 checked exception을 `Exception`으로 뭉뚱그려 던집니다. 호출자 입장에서 어떤 예외를 대비해야 하는지 알 수 없습니다.
- plugin 모듈의 세 파일이 전부 주석 처리 상태입니다. 빌드는 성공하지만 플러그인으로서 기능하지 않습니다.

### 엣지 케이스 처리 여부

| 엣지 케이스 | 처리 여부 | 비고 |
|---|---|---|
| queryId가 여러 파일에 존재하는 경우 | ❌ | `getQueryIdDetail()`은 단일 파일만 수신. 플러그인 코드에는 다중 파일 선택 로직이 있으나 주석 처리됨 |
| queryId가 존재하지 않는 경우 | ⚠️ | `null`을 반환하고 `generatePrompt()`에서 null 체크하지만, `nodeToString(null)` 호출 시 NPE 발생 가능 |
| `<sql>` 태그가 다른 네임스페이스에 있는 경우 | ✅ | FQN 처리로 크로스 네임스페이스 include 지원 |
| `<include>`의 refid가 등록되지 않은 경우 | ✅ | `/* MISSING_INCLUDE: ... */` 주석 처리로 graceful degradation |
| MySQL/MariaDB EXPLAIN 결과 컬럼 처리 | ❌ | H2는 단일 컬럼 텍스트로 반환하지만 MySQL EXPLAIN은 id/select_type/table/... 등 10개 컬럼. `rs.getString(1)`만 읽으면 결과 대부분 유실 |
| `MessageFormat`과 fakeSql 내 중괄호(`{}`) 충돌 | ❌ | `MessageFormat.format("EXPLAIN {0}", fakeSql)` 사용 시, fakeSql 내에 MyBatis 파라미터(`?`) 치환 후에도 SQL에 리터럴 중괄호가 있으면 `IllegalArgumentException` 발생 (메모리 `pattern_messagformat_sql_injection.md` 참조) |
| `<trim>` suffix / suffixOverrides 처리 | ❌ | `prefix`와 `prefixOverrides`만 처리하고 `suffix`, `suffixOverrides`는 미구현 |
| 동일 파일에서 중복 queryId 존재 시 | ⚠️ | `getQueryIdDetail()`은 첫 번째 매칭 노드만 반환하며 경고 없음 |
| 매퍼 디렉토리가 존재하지 않는 경우 | ⚠️ | `getSqlSnippetRegistry()`에서 `log.info()`로 로그만 남기고 `Files.walk()`를 계속 호출하여 예외 발생 가능 |
| `<foreach>` 내부 SQL이 테이블명을 포함하는 경우 | ❌ | `( ? )`로만 치환하여 `IN` 절 등은 JSqlParser가 정상 인식하나, `INSERT INTO ... VALUES` 형태의 foreach는 파싱 실패 가능 |

### 면접관 시점의 개선 제안

1. **"이 `static` 필드는 왜 여기 있나요?"**: `SqlExtractor.sqlSnippetRegistry`는 `buildFakeSql()`에 이미 파라미터로 전달되므로 필드로 공개할 이유가 없습니다. 테스트에서 `SqlExtractor.sqlSnippetRegistry = ...` 로 직접 대입하는 방식을 제거하고, 테스트도 `getSqlSnippetRegistry()`를 직접 호출해 로컬 변수로 사용하면 됩니다.
2. **"EXPLAIN 결과가 MySQL에서 다르게 나올 것 같은데요?"**: `getExplainInfo()`의 ResultSet 순회 로직은 H2 전용으로 동작합니다. MySQL EXPLAIN의 컬럼별 의미 있는 정보를 수집하려면 컬럼명(`rs.getString("type")`, `rs.getString("key")` 등)으로 접근해야 합니다.
3. **"테스트 셋업 코드가 3번 복붙되어 있네요"**: 공통 `@BeforeEach` 로직을 별도 `TestFixtureHelper` 유틸리티 클래스나 JUnit 5의 `@TestInstance(Lifecycle.PER_CLASS)` + 공유 픽스처로 추출하는 것이 좋습니다.
4. **"플러그인 모듈이 전부 주석 처리된 이유가 있나요?"**: core와 plugin의 API 계약(SqlExtractor의 메서드 시그니처)이 변경되면서 plugin 코드가 컴파일 오류 상태가 된 것으로 추정됩니다. 현재 plugin은 `findMapperFiles()`, `extractRawSql()`, `SqlSimplifier` 등 core에 존재하지 않는 메서드를 참조하고 있습니다. 이는 두 모듈 간 계약이 아직 정리되지 않았음을 의미합니다.

</step3_code_review>

<step4_pros_and_cons>

## ⚖️ Step 4. 엔지니어 역량 장단점 평가

### ✅ 강점 (Strengths)

- **실용적인 문제 해결 관점**: "MyBatis 동적 쿼리를 AI가 분석할 수 있게 만들겠다"는 아이디어는 실무 고통 포인트를 정확히 짚은 것입니다. 단순 코드 생성 도구가 아닌, 기존 레거시 자산(XML 매퍼)을 AI 친화적으로 변환하는 파이프라인을 직접 설계한 점이 인상적입니다.

- **DOM API와 JDBC MetaData API의 능숙한 조합**: W3C DOM API(`NodeList`, `Node`, `Element`)와 `DatabaseMetaData`를 직접 다루는 것은 많은 주니어 개발자가 기피하는 저수준 API입니다. DTD 검증 비활성화(`setFeature("...nonvalidating...")`)와 같은 MyBatis XML 특성 대응도 경험에서 나온 코드입니다.

- **FQN 기반 크로스 네임스페이스 `<include>` 지원**: `refid`에 점(`.`)이 포함된 경우 전체 FQN으로 처리하고 그렇지 않으면 현재 namespace를 앞에 붙이는 로직은 실제 MyBatis의 동작을 정확히 모델링합니다. 세부 사양을 꼼꼼히 파악하고 있다는 증거입니다.

- **`<choose>/<when>/<otherwise>` 분기 처리**: `isForExplain` 플래그를 활용하여 EXPLAIN용 fakeSql 생성 시에는 `<otherwise>`를 우선 선택하는 세심한 처리가 구현되어 있습니다. 단순히 태그를 무시하는 것이 아니라 EXPLAIN 실행 가능성을 높이려는 의도가 보입니다.

- **Gradle 플러그인 설계 이해**: `SqlAnalyzerExtension`에서 `abstract Property<String>` 패턴(Gradle Lazy Configuration)을 사용한 것은 Gradle 플러그인 개발의 베스트 프랙티스를 이해하고 있음을 보여줍니다.

### ⚠️ 개선 필요 영역 (Areas for Improvement)

- **H2/MySQL 동작 불일치 (심각)**: `getExplainInfo()`의 `rs.getString(1)` 패턴은 H2 테스트를 통과시키지만 MySQL 프로덕션 환경에서는 의미 없는 결과를 반환합니다. 이 도구의 핵심 가치(EXPLAIN 결과를 AI에 전달)를 훼손하는 버그입니다. 테스트가 통과한다고 프로덕션이 정확하게 동작하는 것이 아님을 항상 인식해야 합니다.

- **`MessageFormat`의 위험한 사용 (잠재적 장애)**: `MessageFormat.format("EXPLAIN {0}", fakeSql)`는 fakeSql 내에 리터럴 중괄호(`{`, `}`)가 존재하면 런타임 예외를 발생시킵니다. SQL에서 중괄호는 드물지만 일부 DB 함수나 JSON 컬럼 접근 시 등장할 수 있습니다. `String.format("EXPLAIN %s", fakeSql)` 또는 단순 문자열 연결(`"EXPLAIN " + fakeSql`)이 안전합니다.

- **모듈 간 API 계약 불일치 (설계 완성도)**: plugin 모듈이 참조하는 `SqlExtractor.findMapperFiles()`, `SqlExtractor.extractRawSql()`, `SqlSimplifier` 등이 core 모듈에 존재하지 않습니다. 두 모듈이 동시에 발전하면서 인터페이스가 어긋난 것입니다. 이는 공개 API를 먼저 정의하고 구현을 뒤따르게 하는 API First 설계 원칙이 필요한 상황입니다.

- **테스트 코드 DRY 위반**: 동일한 `@BeforeEach` DDL이 3개 테스트 클래스에 복붙되어 있습니다. 실무에서는 테이블 구조 변경 요구사항 발생 시 3곳 모두 수정해야 하며, 한 곳을 놓치면 테스트 간 불일치가 발생합니다. `TestSchemaInitializer` 같은 공유 픽스처 클래스 추출이 필요합니다.

- **`<trim>`의 suffix 처리 미구현**: MyBatis `<trim>` 태그는 `prefix`, `prefixOverrides`, `suffix`, `suffixOverrides` 4가지 속성을 지원합니다. 현재 구현은 prefix/prefixOverrides만 처리합니다. `suffix`를 활용하는 매퍼가 입력되면 잘못된 SQL이 생성됩니다.

- **정적 유틸리티 클래스이지만 전역 가변 상태 보유**: `SqlExtractor.sqlSnippetRegistry`는 `static` 가변 필드입니다. 멀티스레드 환경(병렬 Gradle 빌드 등)에서 레이스 컨디션이 발생할 수 있습니다.

### 📊 종합 평가

- **기술 역량**: ⭐⭐⭐⭐☆
- **문제 해결력**: ⭐⭐⭐⭐☆
- **코드 품질**: ⭐⭐⭐☆☆
- **총평**: 이 지원자는 MyBatis XML의 동작 원리를 깊이 이해하고 있으며, DOM API·JDBC MetaData·Gradle 플러그인 등 비교적 진입 장벽이 높은 API를 능숙하게 다룹니다. 아이디어도 실용적이고 실무 감각이 돋보입니다. 다만, H2 테스트 통과가 MySQL 정확성을 보장하지 않는다는 점, `MessageFormat`의 잠재적 위험, plugin-core 간 API 불일치, 테스트 코드 DRY 위반 등은 프로덕션 품질을 달성하기 위해 반드시 해결해야 할 과제입니다. 네이버/카카오 기준으로는 **실무 경험과 문제 해결 능력에서 강점**을 보이지만, **코드 품질과 엣지 케이스 완성도**에서 보완이 필요한 미드레벨(3~5년차) 수준으로 평가됩니다. 기술 면접 통과 가능성은 60~70%이며, 추가 리뷰 라운드에서 위 이슈들에 대한 인식과 개선 방향을 제시할 수 있다면 합격선에 충분히 진입할 수 있습니다.

</step4_pros_and_cons>

<step5_interview_qna>

## 💬 Step 5. 기술 면접 예상 Q&A

---

### 🔗 CS 꼬리 질문 (심화)

**Q1. `buildFakeSql()`에서 `<include>` 처리 시마다 `DocumentBuilderFactory.newInstance()`와 `DocumentBuilder.parse()`를 반복 호출합니다. 이것이 성능에 미치는 영향을 설명하고, 개선 방법을 제안해 보세요.**

> 💡 **모범 답변 가이드라인**:
> - `DocumentBuilderFactory.newInstance()`는 서비스 프로바이더 로더를 사용하므로 매 호출 시 적지 않은 초기화 비용이 발생합니다. `DocumentBuilderFactory`는 스레드 안전하지 않으므로 싱글톤으로 재사용하려면 동기화가 필요하다는 점을 언급하면 깊이 있는 답변입니다.
> - 핵심 개선 방향은 `sqlSnippetRegistry`의 Value 타입을 XML 문자열 대신 파싱이 완료된 경량 구조(예: `Node`를 별도 `Document`에 임포트한 것, 또는 자체 정의한 `SqlFragment` VO)로 변경하는 것입니다.
> - 단, `Node`는 자신이 속한 `Document` 인스턴스에 종속된 생명주기를 가지므로, `Document.importNode()` 또는 `Document.adoptNode()`를 통해 새 `Document`로 이관해야 함을 설명할 수 있으면 Senior 수준의 답변입니다.
> - "스레드 안전성을 보장하려면 `ThreadLocal<DocumentBuilder>` 패턴을 고려할 수 있다"는 점도 가산점 포인트입니다.

**Q2. `JdbcAnalyzer.getExplainInfo()`에서 H2와 MySQL의 EXPLAIN 결과 처리 방식이 다를 수 있습니다. 어떤 문제가 발생하고, 어떻게 DB 독립적으로 설계할 수 있을까요?**

> 💡 **모범 답변 가이드라인**:
> - 현재 코드는 `for(int i = 1; rs.next(); i++) result.append(rs.getString(i))`로 컬럼 인덱스를 순서대로 읽지만, H2 EXPLAIN은 단일 컬럼 텍스트를 반환하고, MySQL EXPLAIN은 `id`, `select_type`, `table`, `partitions`, `type`, `possible_keys`, `key`, `key_len`, `ref`, `rows`, `filtered`, `Extra` 등 12개 컬럼을 반환한다는 점을 지적해야 합니다.
> - 개선 방향으로는 `ResultSetMetaData`를 활용해 컬럼 수와 컬럼명을 동적으로 파악하고, 컬럼명을 키로 하는 `List<Map<String, String>>`으로 결과를 수집하는 DB 독립적 설계를 제안합니다.
> - 더 나아가 `DatabaseMetaData.getDatabaseProductName()`으로 DB 종류를 판별하고 전략 패턴(Strategy Pattern)으로 DB별 EXPLAIN 결과 파서를 분리하는 방안을 언급하면 우수한 답변입니다.
> - 이 문제가 보여주는 교훈("H2 테스트 통과 ≠ MySQL 정확성")을 일반화하여 테스트 전략(통합 테스트 필요성, Testcontainers 활용 등)으로 연결할 수 있으면 Senior 수준입니다.

**Q3. `SqlExtractor.sqlSnippetRegistry`가 `static` 가변 필드로 선언되어 있습니다. Gradle의 병렬 빌드나 Worker API 환경에서 이 설계가 어떤 문제를 일으킬 수 있을까요?**

> 💡 **모범 답변 가이드라인**:
> - Gradle의 `--parallel` 플래그나 Worker API는 동일 JVM 내에서 여러 스레드가 Task를 동시에 실행할 수 있습니다. `static` 가변 필드는 JVM 내 모든 스레드가 공유하므로 레이스 컨디션(Race Condition)의 위험이 있습니다.
> - 구체적으로는 Task A가 `sqlSnippetRegistry`에 쓰는 도중 Task B가 읽으면 불완전한 Map이 반환될 수 있습니다. Java Memory Model(JMM)의 가시성(Visibility) 문제와 복합 연산의 원자성(Atomicity) 부재를 언급하면 깊이 있는 답변입니다.
> - 해결책으로는 (1) `ConcurrentHashMap` 사용 + `computeIfAbsent`로 원자적 초기화, (2) static 필드 제거 후 `SqlAnalyzerTask`의 인스턴스 변수로 관리, (3) Gradle `@Internal` 상태 관리 패턴 활용을 제안할 수 있습니다.
> - "애초에 Gradle Task는 인스턴스 단위로 생성되므로 인스턴스 변수로 만들면 해결된다"는 실용적 관점도 좋은 답변입니다.

---

### 🔥 압박 면접 질문 (Stress Interview)

**Q. 이 코드를 사용해서 `generateSqlPrompt` Gradle 태스크를 실행하면 MySQL 데이터베이스에서 어떤 결과가 나올지 직접 시연해 주실 수 있나요? 그리고 H2로 테스트할 때와 실제 MySQL에서의 EXPLAIN 결과가 동일한가요?**

> 💡 **대응 가이드라인**:
> - 이 질문은 **현재 plugin 모듈이 주석 처리 상태라 실제 실행이 불가능**하다는 사실과, **`getExplainInfo()`의 ResultSet 파싱 버그** 두 가지를 동시에 찌르는 압박 질문입니다.
> - 당황하지 말고 먼저 "현재 plugin 모듈이 주석 처리 상태라 태스크 실행이 안 됩니다"라고 사실을 솔직하게 인정하세요. 이 때 "왜 그렇게 되었는지" — core API 변경에 plugin이 미처 따라가지 못한 상황 — 를 설명할 수 있으면 됩니다.
> - H2 vs MySQL EXPLAIN 결과 차이에 대해서는, H2 EXPLAIN은 단일 컬럼 텍스트 형태이고 MySQL EXPLAIN은 다중 컬럼 행 형태라는 차이를 인정하고, 현재 `rs.getString(1)` 코드가 MySQL에서 의미 있는 결과를 돌려주지 못한다는 점을 인정합니다.
> - 면접관이 듣고 싶은 것은 "모른다" 또는 "동일합니다"가 아니라, **이슈를 인식하고 어떻게 고칠지**에 대한 로드맵입니다. "Testcontainers로 실제 MySQL 컨테이너를 띄운 통합 테스트를 추가하고, ResultSetMetaData로 컬럼을 동적으로 처리하겠다"는 구체적 개선안을 제시하면 압박을 돌파할 수 있습니다.
> - **이 질문의 의도**: 코드를 작성한 사람이 자기 코드의 한계를 인식하고 있는지, 테스트 환경과 프로덕션 환경의 차이를 얼마나 의식하는지를 검증합니다.

---

### 📌 면접 준비 To-Do

- [ ] **Testcontainers 사용법 학습**: H2 인메모리 DB와 MySQL/MariaDB의 동작 차이를 극복하기 위해 실제 DB 컨테이너를 테스트에서 사용하는 방법을 익히세요. `org.testcontainers:mysql` 의존성과 `@Testcontainers`, `@Container` 어노테이션 사용법을 공부하세요.
- [ ] **Java Memory Model(JMM)과 동시성 기초**: `static` 가변 상태의 위험성, `volatile`, `synchronized`, `ConcurrentHashMap`, `AtomicReference` 등 Java 동시성 도구의 사용 시나리오를 정리하세요. 특히 Gradle의 병렬 빌드 환경에서 플러그인이 어떻게 동작하는지 공식 문서를 참고하세요.
- [ ] **W3C DOM API의 Node 생명주기와 Document 소유권**: `Node`는 자신을 생성한 `Document`에 종속됩니다. `Document.importNode()` vs `Document.adoptNode()` 차이, XPath를 이용한 효율적 노드 탐색(`javax.xml.xpath.XPath`), JAXB/Jackson과의 비교 등을 학습하여 XML 처리 관련 CS 심화 질문에 대비하세요.

</step5_interview_qna>
