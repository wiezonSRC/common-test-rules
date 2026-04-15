# 아키텍처 분석 보고서

- 분析일시: 2026-04-15 15:19:14
- 분析 대상 프로젝트: mybatis-sql-analyzer-core + mybatis-sql-analyzer-plugin

---

<step1_code_analysis>

## 비즈니스 흐름 및 도메인 목적 요약

### 시스템이 해결하려는 문제

MyBatis XML 매퍼 파일에 정의된 동적 쿼리(Dynamic SQL)는 `<if>`, `<choose>`, `<foreach>` 등의 태그로 구성되어 있어, 일반적인 SQL 파서로는 직접 분석이 불가능합니다. 이 시스템은 해당 동적 쿼리를 실행 가능한 형태(Fake SQL)로 변환하고, JDBC를 통해 EXPLAIN 및 DB 메타데이터를 수집한 뒤, AI(Gemini/ChatGPT)에 전달할 쿼리 튜닝 프롬프트를 자동 생성하는 도구입니다.

### 주요 도메인 객체 및 유즈케이스

| 클래스 | 역할 |
|---|---|
| `SqlExtractor` | MyBatis XML 파싱, Fake SQL 생성, `<sql>` 태그 캐싱 |
| `JdbcAnalyzer` | EXPLAIN 실행, 테이블명 추출(JSqlParser), 메타데이터 수집 |
| `PromptGenerator` | 위 두 클래스를 조합하여 AI 프롬프트 문자열 생성 |
| `SqlAnalyzerPlugin` | Gradle Plugin 진입점 (현재 전체 주석 처리) |
| `SqlAnalyzerTask` | Gradle Task 실행 로직 (현재 전체 주석 처리) |
| `SqlAnalyzerExtension` | Gradle DSL 설정 클래스 (현재 전체 주석 처리) |

### 전체 데이터 흐름

```
[입력] queryId + mapperPath + JDBC Connection
    │
    ▼
SqlExtractor.getQueryIdDetail()      → XML DOM 파싱하여 queryId에 해당하는 Node 추출
SqlExtractor.getSqlSnippetRegistry() → 전체 매퍼 디렉토리에서 <sql> 태그 캐싱
SqlExtractor.buildFakeSql()          → 동적 태그를 순회하며 파라미터를 ?로 치환한 실행 가능 SQL 생성
    │
    ▼
JdbcAnalyzer.getExplainInfo()        → EXPLAIN {fakeSql} 실행 후 결과 문자열 반환
JdbcAnalyzer.extractTableMethod()    → JSqlParser로 fakeSql에서 테이블명 Set 추출
JdbcAnalyzer.getMetaDataInfo()       → DatabaseMetaData API로 컬럼/인덱스 정보 수집
    │
    ▼
PromptGenerator.generatePrompt()     → 모든 정보를 조합한 AI 프롬프트 StringBuilder 반환
    │
    ▼
[출력] AI 프롬프트 문자열 (콘솔 출력 또는 파일 저장)
```

### PG 도메인 컨텍스트와의 연관성

테스트 리소스(`TestMapper.xml`, `SampleMapper.xml`)에서 `payments`, `orders`, `merchants`, `refunds` 테이블을 사용하고 있어, 결제-주문-가맹점-환불 구조의 PG 도메인 쿼리를 분석 대상으로 상정하고 있습니다. 이 도구는 PG 시스템에서 성능 이슈가 의심되는 쿼리를 빠르게 식별하고 AI 기반 튜닝 제안을 받기 위한 개발자 지원 도구로 포지셔닝됩니다.

</step1_code_analysis>

---

<step2_architecture_analysis>

## 구조적 설계 평가

### 관심사 분리 (SoC) 평가

현재 구조는 계층형이 아닌 **3개의 정적 유틸리티 클래스가 수평 나열된 형태**입니다.

```
SqlExtractor  ←──── PromptGenerator ────→  JdbcAnalyzer
(XML 파싱)          (조합 오케스트레이터)    (JDBC 분석)
```

- `PromptGenerator`는 두 클래스를 직접 호출하는 오케스트레이터 역할을 하지만, 동시에 AI 프롬프트 문자열 생성이라는 별도 관심사까지 담당하고 있습니다.
- `SqlExtractor`는 XML 파싱과 Fake SQL 변환이라는 두 가지 책임을 가집니다. `getSqlSnippetRegistry()`(캐시 초기화)와 `buildFakeSql()`(SQL 변환)은 분리될 수 있는 관심사입니다.
- `JdbcAnalyzer`는 "SQL에서 테이블 추출(정적 분석)"과 "DB에 직접 접속하여 EXPLAIN/메타데이터 실행(동적 분석)"이라는 이질적인 두 책임을 하나의 클래스에 혼재시키고 있습니다.

### 의존성 역전 원칙 (DIP) 평가

**DIP가 전혀 적용되지 않은 구조입니다.**

- 3개의 핵심 클래스 모두 인터페이스 없이 구체 클래스로만 구성되어 있습니다.
- `PromptGenerator`가 `SqlExtractor`와 `JdbcAnalyzer`의 구체 클래스(static 메서드)를 직접 호출하므로, 구현체 교체나 Mocking이 구조적으로 불가능합니다.
- Gradle Plugin(`SqlAnalyzerTask`)이 core 모듈 클래스를 직접 참조하는 구조도 추상화 없이 강결합되어 있습니다.

### 모듈 간 결합도

- `mybatis-sql-analyzer-plugin`이 `mybatis-sql-analyzer-core`를 `api` 스코프로 의존합니다. 의미상 `implementation`으로 충분한데 `api`로 선언하여 core의 내부 구현이 plugin 사용자에게도 전이 노출됩니다.
- `SqlExtractor`에 `static Map<String, String> sqlSnippetRegistry` 필드가 선언되어 있으며 `package-private` 접근 수준입니다. 이 필드는 테스트 코드(`SqlExtractorTest`)에서 직접 할당(`SqlExtractor.sqlSnippetRegistry = ...`)하는 방식으로 사용되어, 테스트가 프로덕션 클래스의 내부 상태에 직접 결합됩니다.

### 응집도 평가

| 클래스 | 응집도 | 문제점 |
|---|---|---|
| `SqlExtractor` | 보통 | XML 파싱 + Fake SQL 생성 + 캐시 관리 + Node 직렬화 4가지 역할 혼재 |
| `JdbcAnalyzer` | 낮음 | 정적 SQL 분석(JSqlParser)과 동적 DB 접속(JDBC) 역할 혼재 |
| `PromptGenerator` | 낮음 | 프롬프트 조합 로직과 오케스트레이션(다른 클래스 호출 흐름 제어) 혼재 |

</step2_architecture_analysis>

---

<step3_code_review>

## 시스템 확장성 및 안전성 리뷰

### 1. EXPLAIN 결과 파싱 버그 (JdbcAnalyzer.getExplainInfo)

```java
// 현재 코드 - 치명적 버그
for (int i = 1; rs.next(); i++) {
    result.append(rs.getString(i));  // 컬럼 인덱스가 루프 횟수에 따라 증가
}
```

EXPLAIN 결과는 여러 컬럼(id, select_type, table, type, key 등)을 가진 **다수의 행**으로 구성됩니다. 현재 로직은 첫 번째 행에서 컬럼 1, 두 번째 행에서 컬럼 2... 방식으로 인덱스가 증가하므로, 두 번째 행부터는 엉뚱한 컬럼 값을 읽거나 `SQLException`이 발생합니다. 사실상 EXPLAIN 결과의 **첫 번째 행의 첫 번째 컬럼 값만** 정상적으로 수집됩니다.

### 2. static 필드를 통한 암묵적 전역 상태 (SqlExtractor)

```java
// 프로덕션 클래스에 선언된 package-private static 필드
static Map<String, String> sqlSnippetRegistry;
```

```java
// 테스트 코드에서 직접 접근
SqlExtractor.sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperBaseDir);
```

이 패턴은 세 가지 문제를 동시에 가집니다.
- **스레드 안전성 없음**: 멀티스레드 환경(예: Gradle 병렬 빌드)에서 경쟁 조건 발생 가능
- **테스트 오염**: 한 테스트에서 설정한 static 상태가 다른 테스트에 영향
- **캡슐화 파괴**: 테스트가 프로덕션 클래스의 내부 구조에 강결합

### 3. Connection 라이프사이클 관리 책임 부재 (JdbcAnalyzer)

`JdbcAnalyzer`의 모든 메서드가 외부에서 `Connection`을 주입받지만, `getExplainInfo()`에서 `ResultSet`을 `try-with-resources` 외부에 선언하고 `finally` 블록에서 수동 close 처리합니다.

```java
ResultSet rs = null;
try (Statement stmt = connection.createStatement()) {
    // ...
    rs = stmt.executeQuery(sql);   // stmt가 닫히면 rs도 자동으로 닫힘 - 중복 close
} finally {
    if (rs != null) rs.close();    // 이미 닫힌 rs를 다시 close 시도
}
```

`Statement`가 `try-with-resources`로 닫히면 그 하위 `ResultSet`도 자동으로 닫힙니다. `finally`의 수동 close는 불필요하며, `Statement` 외부에서 `rs`를 선언할 이유가 없습니다.

### 4. Gradle Plugin 모듈 전체 주석 처리 (미완성 상태)

`SqlAnalyzerPlugin`, `SqlAnalyzerTask`, `SqlAnalyzerExtension` 3개 파일이 전부 주석 처리되어 있으며, `SqlAnalyzerTask` 내부에서 참조하는 메서드들(`SqlExtractor.findMapperFiles()`, `SqlExtractor.extractRawSql()`, `SqlSimplifier.simplify()`, `JdbcAnalyzer.analyze()`)이 core 모듈에 존재하지 않습니다. 즉, **plugin 모듈은 현재 사용 불가능한 상태**이며 core API와 plugin이 설계 단계에서 분리되지 않은 채 개발이 진행되었음을 보여줍니다.

### 5. DocumentBuilder 재사용으로 인한 잠재적 SAX 상태 오염 (SqlExtractor.getSqlSnippetRegistry)

```java
DocumentBuilder builder = dbf.newDocumentBuilder();
for (File xmlFile : xmlFileList) {
    Document doc = builder.parse(xmlFile);   // 동일 builder 인스턴스 재사용
```

`DocumentBuilder`는 스레드 안전하지 않으며, 파싱 오류 발생 시 내부 상태가 오염될 수 있습니다. 파일마다 새로운 `DocumentBuilder` 인스턴스를 생성하거나, 오류 격리를 위한 예외 처리 블록을 파일 단위로 적용해야 합니다.

### 6. 예외 선언 남용 (PromptGenerator.generatePrompt)

```java
public static StringBuilder generatePrompt(...) throws Exception {
```

`throws Exception`은 모든 예외를 상위로 전파하는 가장 광범위한 선언입니다. 실제 발생 가능한 예외(`ParserConfigurationException`, `SAXException`, `IOException`, `SQLException`, `JSQLParserException`)를 구체적으로 선언하거나, 도메인 예외로 변환하여 던져야 합니다.

### 7. 코드 컨벤션 위반 사항

| 위치 | 위반 내용 |
|---|---|
| `SqlExtractorTest.findDynamicQuery()` | `System.out.println()` 사용 (컨벤션: `@Slf4j` + `log.debug()` 사용) |
| `SqlExtractorTest.dynamicQueryChangeToSql()` | `System.out.println()` 사용 |
| `JdbcAnalyzerTest.extractTables()` | `System.out.println()` 사용 |
| `JdbcAnalyzerTest.extractDDLAndIndex()` | `System.out.println()` 사용 |
| `SqlExtractor.sqlSnippetRegistry` | `package-private static` 필드 (접근 제어자 명시 없음, 공유 가변 상태) |
| `JdbcAnalyzer.extractTableMethod()` | 지역 변수 `stmt = null` 초기화 후 즉시 사용 (불필요한 null 초기화) |
| `SqlExtractor.nodeToString()` | `try` 블록 시작 `{` 앞 공백 없음 (포맷팅 불일치) |
| 테스트 클래스 셋업 메서드 | `set()`, `remove()` 는 의미 없는 축약명, `setUp()`, `tearDown()` 또는 `initH2Database()` 등 의도를 드러내는 이름 사용 권장 |
| `PromptGenerator.generatePrompt()` | 하드코딩된 한국어 프롬프트 문자열이 코드에 직접 삽입 (설정 외부화 필요) |

### 8. 시스템 확장성 (Stateless/Stateful)

모든 클래스가 static 메서드로만 구성되어 인스턴스 상태를 갖지 않는 것처럼 보이지만, `SqlExtractor.sqlSnippetRegistry`라는 `static` 가변 필드가 존재하여 실질적으로 **전역 상태를 가진 구조**입니다. 수평 확장(Scale-out) 시나리오에서 JVM이 달라지면 캐시가 공유되지 않는 문제는 없으나, 단일 JVM 내 병렬 실행 시 경쟁 조건이 발생합니다.

</step3_code_review>

---

<step4_pros_and_cons>

## 구조적 장단점 분석

### 장점

**1. 기능 핵심 파이프라인의 명확한 흐름**
`SqlExtractor → JdbcAnalyzer → PromptGenerator`로 이어지는 데이터 흐름이 `PromptGenerator.generatePrompt()` 메서드에서 선형적으로 읽혀, 시스템이 하는 일을 빠르게 이해할 수 있습니다.

**2. `<include>` 태그의 Cross-Mapper 참조 지원**
`buildFakeSql()` 내의 `include` 처리 로직이 FQN(`namespace.id`) 기반으로 다른 매퍼 파일의 `<sql>` 조각을 참조할 수 있도록 구현되어 있습니다. 실무 MyBatis 코드에서 자주 사용되는 패턴을 지원한다는 점에서 도메인 이해도가 반영된 설계입니다.

**3. DTD 검증 비활성화 처리**
`DocumentBuilderFactory`에서 `setValidating(false)` 및 `load-external-dtd` 비활성화를 일관되게 적용하여, 오프라인 환경이나 네트워크 제한 환경에서도 XML 파싱이 가능하도록 배려되어 있습니다.

**4. H2 인메모리 DB 기반 테스트 환경 구성**
`@BeforeEach` / `@AfterEach`에서 H2 테이블을 매 테스트마다 생성/삭제하여 테스트 격리성을 확보하고 있습니다. 외부 DB 없이 EXPLAIN 및 메타데이터 수집 로직을 검증할 수 있는 구조입니다.

**5. JSqlParser 연동을 통한 SQL 정적 분석**
문자열 파싱이 아닌 `JSqlParser`를 활용하여 테이블명을 추출함으로써, 복잡한 JOIN 구문에서도 신뢰도 높은 테이블 목록을 얻을 수 있습니다.

---

### 단점 및 개선 필요 사항

**[단점 1] EXPLAIN 결과 수집 로직의 치명적 버그**

- 현재 상태: `for (int i = 1; rs.next(); i++) { result.append(rs.getString(i)); }` — 행 순회와 컬럼 인덱스가 동기화되어 있지 않아 첫 행 이후 데이터가 유실되거나 예외 발생
- 개선 방향: 컬럼 메타데이터(`ResultSetMetaData.getColumnCount()`)를 활용하여 모든 컬럼을 순회하거나, 명시적 컬럼명으로 추출

```java
// 개선 예시
ResultSetMetaData metaData = rs.getMetaData();
int columnCount = metaData.getColumnCount();
while (rs.next()) {
    for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
        result.append(metaData.getColumnName(columnIndex))
              .append(": ")
              .append(rs.getString(columnIndex))
              .append(" | ");
    }
    result.append("\n");
}
```

**[단점 2] static 전역 상태로 인한 테스트 오염 및 스레드 안전성 부재**

- 현재 상태: `static Map<String, String> sqlSnippetRegistry` 필드를 테스트에서 직접 주입
- 개선 방향: `SqlSnippetCache` 또는 `SqlSnippetRegistry` 값 객체를 도입하고, 메서드 파라미터로 전달하는 방식을 유지하되 static 필드를 제거. 이미 `buildFakeSql()`은 `sqlSnippetRegistry`를 파라미터로 받는 올바른 설계를 갖추고 있으므로, static 필드는 완전히 제거 가능

```java
// 제거 대상
static Map<String, String> sqlSnippetRegistry;  // 이 필드 삭제

// 테스트에서는 아래처럼 로컬 변수로 사용 (이미 일부 테스트에서 이 방식 사용 중)
Map<String, String> registry = SqlExtractor.getSqlSnippetRegistry(mapperBaseDir);
String fakeSql = SqlExtractor.buildFakeSql(node, true, namespace, registry);
```

**[단점 3] Plugin 모듈과 Core 모듈 간 API 불일치**

- 현재 상태: plugin의 `SqlAnalyzerTask`가 참조하는 `SqlExtractor.findMapperFiles()`, `SqlSimplifier`, `JdbcAnalyzer.analyze()` 등이 core에 존재하지 않아 전체 주석 처리된 상태
- 개선 방향: core 모듈의 public API를 먼저 인터페이스로 확정하고, plugin은 해당 인터페이스에만 의존하도록 계약 기반 설계 적용. plugin 개발 재개 전 core의 API 설계를 완성해야 함

```java
// core 모듈에 추가해야 할 퍼사드(Facade) 인터페이스 예시
public interface SqlAnalysisService {
    List<Path> findMapperFiles(Path mapperDir, String queryId) throws IOException;
    String buildFakeSql(String queryId, Path mapperPath, Path mapperDir) throws Exception;
    AnalysisResult analyze(String fakeSql, Connection connection) throws Exception;
}
```

**[단점 4] JdbcAnalyzer의 책임 과부하로 인한 확장성 저하**

- 현재 상태: 정적 SQL 분석(JSqlParser 기반 테이블 추출)과 동적 DB 실행(EXPLAIN, 메타데이터)이 한 클래스에 공존
- 개선 방향: `SqlStaticAnalyzer`(파서 기반 분석)와 `JdbcMetadataCollector`(DB 접속 기반 분석)로 분리. 정적 분석은 DB 연결 없이 테스트 가능하여 테스트 속도와 격리성이 개선됨

**[단점 5] PromptGenerator의 하드코딩된 프롬프트 템플릿**

- 현재 상태: 한국어 프롬프트 문자열이 Java 코드 내에 하드코딩되어, AI 모델 변경이나 프롬프트 최적화 시 코드 수정이 필요
- 개선 방향: 프롬프트 템플릿을 외부 리소스 파일(`prompt-template.txt` 또는 `.mustache`)로 분리하고, 변수 치환 방식으로 구성하여 코드 변경 없이 프롬프트를 개선할 수 있는 구조로 전환

**[단점 6] DocumentBuilder 재사용으로 인한 상태 오염 위험**

- 현재 상태: `getSqlSnippetRegistry()` 내에서 단일 `DocumentBuilder` 인스턴스로 여러 XML 파일을 순차 파싱
- 개선 방향: try-with-resources 내에서 파일별로 `DocumentBuilder`를 재생성하거나, `DocumentBuilderFactory`를 `ThreadLocal`로 관리하여 스레드 안전성 확보

</step4_pros_and_cons>

---

<step5_interview_qna>

## 시스템 아키텍처 심화 인터뷰 Q&A

---

**Q1. SqlExtractor, JdbcAnalyzer, PromptGenerator 모두 static 메서드로만 구성된 유틸리티 클래스 구조를 선택한 이유가 있나요? 이 구조가 향후 확장에 어떤 제약을 줄 수 있을까요?**

> **모범 답변:**
> static 유틸리티 클래스 구조는 진입 장벽이 낮고 사용이 간편하다는 단기적 장점이 있습니다. 그러나 이 구조는 세 가지 확장 제약을 만듭니다.
>
> 첫째, **Mocking 불가**입니다. `PromptGenerator`가 `SqlExtractor.buildFakeSql()`을 static 메서드로 직접 호출하므로, 단위 테스트에서 SQL 추출 로직을 Mock으로 대체할 수 없습니다. 결과적으로 모든 테스트가 실제 XML 파일과 실제 JDBC를 필요로 하는 통합 테스트가 되어버립니다.
>
> 둘째, **전략 교체 불가**입니다. 예를 들어 JSqlParser 대신 다른 SQL 파서 라이브러리로 교체하거나, MySQL EXPLAIN 대신 PostgreSQL EXPLAIN ANALYZE를 지원하려면 `JdbcAnalyzer` 클래스 코드를 직접 수정해야 합니다. 인터페이스 기반 설계였다면 구현체만 교체하면 됩니다.
>
> 셋째, **상태 관리의 함정**입니다. 현재 `SqlExtractor.sqlSnippetRegistry`처럼 static 필드가 등장하는 순간 유틸리티 클래스의 무상태성이 깨지면서, 스레드 안전성 문제와 테스트 오염이 동시에 발생합니다.
>
> 개선 방향으로는 `SqlParser`, `ExplainExecutor`, `MetadataCollector` 인터페이스를 정의하고, 현재의 static 로직을 구현체(`DefaultSqlParser` 등)로 이관한 뒤, `PromptGenerationService`가 생성자 주입으로 이들을 받는 구조로 전환하는 것을 권장합니다.

---

**Q2. EXPLAIN 결과를 AI 프롬프트에 포함시킬 때, H2와 실제 운영 DB(MySQL/MariaDB) 사이의 EXPLAIN 결과 차이로 인해 AI 분석이 부정확해질 수 있는 문제를 어떻게 해결하시겠습니까?**

> **모범 답변:**
> 이것은 도구의 신뢰도와 직결되는 핵심 문제입니다. H2의 EXPLAIN 결과는 MySQL의 `type`, `key`, `rows`, `Extra` 정보와 구조 자체가 다르기 때문에, H2 기반의 EXPLAIN을 AI에게 제공하면 잘못된 튜닝 제안을 받을 가능성이 높습니다.
>
> 해결 전략은 두 계층으로 나눕니다.
>
> **단기 전략**: EXPLAIN 실행 전에 `DatabaseMetaData.getDatabaseProductName()`으로 DB 종류를 감지하고, H2인 경우 EXPLAIN 결과 대신 "H2 인메모리 DB는 운영 환경과 실행 계획이 다를 수 있으므로 실제 DB 연결을 권장합니다"라는 경고 문구를 프롬프트에 포함시킵니다. 이미 `getMetaDataInfo()`에서 DB 종류 분기 처리 패턴이 존재하므로 동일 방식으로 적용 가능합니다.
>
> **장기 전략**: `ExplainStrategy` 인터페이스를 정의하고, `MySqlExplainStrategy`, `MariaDbExplainStrategy`, `H2ExplainStrategy` 구현체를 분리합니다. 각 구현체는 해당 DB에 최적화된 EXPLAIN 명령(MySQL 8.0의 `EXPLAIN ANALYZE`, MariaDB의 `ANALYZE FORMAT=JSON` 등)을 실행하고, 결과를 정규화된 `ExplainResult` VO로 반환합니다. AI에게 전달할 때는 정규화된 구조로 제공하여 DB 종류에 관계없이 일관된 분석을 받을 수 있습니다.

---

**Q3. 이 도구가 대규모 프로젝트에서 수백 개의 MyBatis XML 매퍼 파일과 수천 개의 queryId를 가진 경우에도 빠르게 동작하려면 어떤 캐싱 및 최적화 전략이 필요할까요?**

> **모범 답변:**
> 현재 구조에는 두 가지 성능 병목이 있습니다. 첫째, `getSqlSnippetRegistry()`가 매 호출마다 전체 매퍼 디렉토리를 재스캔하며 모든 XML 파일을 파싱합니다. 둘째, `getQueryIdDetail()`도 호출 시마다 지정된 XML 파일을 다시 파싱합니다.
>
> 개선 전략을 3단계로 제시합니다.
>
> **1단계 - 지연 초기화 + 캐시 불변 객체화**: `getSqlSnippetRegistry()`의 결과를 `Map<String, String>`이 아닌 `SqlSnippetRegistry` 값 객체로 래핑하고, 최초 1회만 초기화되도록 합니다. Gradle Task 컨텍스트에서는 Task 시작 시점에 1회 초기화하여 이후 재사용합니다.
>
> **2단계 - 파일 변경 감지 기반 무효화**: `Files.getLastModifiedTime()`을 활용하여 XML 파일의 마지막 수정 시각을 캐시 키에 포함시킵니다. 파일이 변경된 경우에만 해당 파일의 캐시를 무효화하는 Selective Eviction 전략을 적용합니다. Gradle의 `@InputDirectory` 어노테이션과 연계하면 파일 변경 여부를 Gradle 빌드 캐시 수준에서 감지할 수 있습니다.
>
> **3단계 - queryId 인덱스 구축**: 전체 매퍼 파일 스캔 시, `queryId → 파일 경로` 역방향 인덱스(`Map<String, Path>`)를 함께 구축합니다. 이후 특정 queryId 분석 시 전체 파일을 순회하지 않고 O(1)로 해당 파일을 찾을 수 있습니다. 현재 `SqlExtractorTest.findMapperXml()` 테스트의 O(N) 파일 전체 스캔 로직을 이 인덱스로 대체할 수 있습니다.

</step5_interview_qna>
