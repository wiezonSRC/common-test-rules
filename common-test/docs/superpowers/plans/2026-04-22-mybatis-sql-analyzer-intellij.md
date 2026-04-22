# MyBatis SQL Analyzer IntelliJ Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mybatis-sql-analyzer-core` 모듈의 버그를 수정하고, MyBatis 매퍼 XML 우클릭 → AI 쿼리 튜닝 프롬프트 자동 생성 Tool Window를 제공하는 IntelliJ 플러그인 모듈(`mybatis-sql-analyzer-intellij`)을 추가한다.

**Architecture:** core 모듈(순수 Java)을 IntelliJ 플러그인 모듈이 의존성으로 참조하는 멀티모듈 구조. 플러그인은 우클릭 Action → Tool Window → Background Task → core 호출 흐름으로 동작. DB 연결 정보는 프로젝트 루트의 `.sql-analyzer.properties`에서 읽는다.

**Tech Stack:** Java 17, Gradle, IntelliJ Platform SDK (org.jetbrains.intellij 1.17.4), JUnit 5, H2 (테스트용), JSqlParser 4.8, Lombok

---

## 파일 맵

### 수정 파일 (core 모듈)
- `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java` — `findMapperFiles()` 추가, `static` field 제거, `getQueryIdDetail()` Path 오버로드
- `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/JdbcAnalyzer.java` — `getExplainInfo()` ResultSet 파싱 버그 수정
- `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/PromptGenerator.java` — `mapperPath` 파라미터 `String` → `Path` 변경
- `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/SqlExtractorTest.java` — `findMapperFiles()` 테스트 추가, static field 직접 할당 제거
- `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/JdbcAnalyzerTest.java` — EXPLAIN 컬럼 파싱 검증 테스트 추가
- `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/PromptGeneratorTest.java` — `mapperPath` 타입 반영

### 신규 파일 (intellij 모듈)
- `mybatis-sql-analyzer-intellij/build.gradle`
- `mybatis-sql-analyzer-intellij/src/main/resources/META-INF/plugin.xml`
- `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/config/SqlAnalyzerConfig.java`
- `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/service/SqlAnalyzerService.java`
- `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/XmlQueryIdReader.java`
- `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/SqlAnalyzerPanel.java`
- `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/SqlAnalyzerToolWindow.java`
- `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/action/AnalyzeSqlAction.java`
- `mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/config/SqlAnalyzerConfigTest.java`
- `mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/service/SqlAnalyzerServiceTest.java`

### 수정 파일 (루트)
- `settings.gradle` — `include 'mybatis-sql-analyzer-intellij'` 추가

---

## Task 1: SqlExtractor — `findMapperFiles()` 프로덕트 코드 승격

**배경:** queryId로 매퍼 파일을 찾는 로직이 `SqlExtractorTest`에만 존재. IntelliJ 플러그인 서비스에서 재사용할 수 없으므로 프로덕트 코드로 승격한다.

**Files:**
- Modify: `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java`
- Modify: `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/SqlExtractorTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`SqlExtractorTest.java`에 아래 테스트 2개를 추가한다. (`findMapperXml()` 기존 테스트 아래에 추가)

```java
@Test
@DisplayName("findMapperFiles - 여러 파일에 걸쳐 queryId 검색 성공")
void findMapperFiles_multipleFiles() throws Exception {
    // TestMapper.xml, TestMapper2.xml, TestMapper3.xml, SampleMapper.xml 모두 포함
    List<Path> result = SqlExtractor.findMapperFiles(mapperBaseDir, queryId);
    Assertions.assertEquals(4, result.size());
}

@Test
@DisplayName("findMapperFiles - 존재하지 않는 queryId 는 빈 리스트 반환")
void findMapperFiles_notFound() throws Exception {
    List<Path> result = SqlExtractor.findMapperFiles(mapperBaseDir, "nonExistentQueryId");
    Assertions.assertTrue(result.isEmpty());
}
```

`SqlExtractorTest` import에 `java.util.List`와 `java.nio.file.Path`가 이미 있는지 확인한다.

- [ ] **Step 2: 테스트가 컴파일 에러로 실패하는지 확인**

```bash
cd mybatis-sql-analyzer-core
../gradlew test --tests "com.example.sqlanalyzer.core.SqlExtractorTest.findMapperFiles_multipleFiles" 2>&1 | tail -20
```

기대 결과: `error: cannot find symbol` (메서드 미존재)

- [ ] **Step 3: `SqlExtractor.java`에 `findMapperFiles()` 메서드 추가**

`SqlExtractor.java`에서 `getQueryIdDetail()` 메서드 바로 위에 아래 메서드를 삽입한다.

```java
/**
 * 지정된 디렉토리에서 특정 queryId를 포함하는 MyBatis 매퍼 XML 파일 목록을 반환한다.
 *
 * <p>queryId는 select, insert, update, delete 태그의 id 속성으로 탐색한다.
 * 동일한 queryId가 여러 파일에 분산될 수 있으므로 List로 반환한다.
 * IntelliJ 플러그인의 SqlAnalyzerService에서 매퍼 파일 선택 UI를 구성할 때도 사용한다.
 *
 * @param mapperBaseDir 매퍼 XML 파일들이 위치한 루트 디렉토리
 * @param queryId       탐색할 MyBatis 쿼리 ID (예: "findBadPerformancePayments")
 * @return queryId를 포함하는 XML 파일 경로 목록 (없으면 빈 List 반환, null 반환 없음)
 * @throws IOException                  파일 탐색 중 IO 오류 발생 시
 * @throws ParserConfigurationException XML 파서 설정 오류 시
 * @throws SAXException                 XML 파싱 오류 시
 */
public static List<Path> findMapperFiles(Path mapperBaseDir, String queryId)
        throws IOException, ParserConfigurationException, SAXException {

    List<Path> matchedPaths = new ArrayList<>();

    if (!Files.isDirectory(mapperBaseDir)) {
        // 디렉토리가 없으면 조용히 빈 리스트를 반환 (예외 대신 경고 로그)
        log.warn("매퍼 디렉토리가 존재하지 않습니다: {}", mapperBaseDir);
        return matchedPaths;
    }

    // XML 파서 사전 설정: MyBatis DTD 검증을 끄지 않으면 오프라인 환경에서 네트워크 요청 발생
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    DocumentBuilder builder = factory.newDocumentBuilder();

    String[] targetTags = {"select", "insert", "update", "delete"};

    // .xml 확장자를 가진 파일만 탐색 (하위 디렉토리 포함)
    List<File> xmlFiles;
    try (Stream<Path> paths = Files.walk(mapperBaseDir)) {
        xmlFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".xml"))
                .map(Path::toFile)
                .toList();
    }

    for (File xmlFile : xmlFiles) {
        Document document = builder.parse(xmlFile);
        document.getDocumentElement().normalize();

        // 파일 하나에서 queryId를 찾으면 즉시 다음 파일로 이동 (중복 추가 방지)
        boolean found = false;
        for (String tagName : targetTags) {
            NodeList nodeList = document.getElementsByTagName(tagName);

            for (int i = 0; i < nodeList.getLength(); i++) {
                Node idAttr = nodeList.item(i).getAttributes().getNamedItem("id");
                if (idAttr != null && queryId.equals(idAttr.getNodeValue())) {
                    matchedPaths.add(xmlFile.toPath());
                    found = true;
                    break;
                }
            }

            if (found) {
                break;
            }
        }
    }

    return matchedPaths;
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd mybatis-sql-analyzer-core
../gradlew test --tests "com.example.sqlanalyzer.core.SqlExtractorTest" 2>&1 | tail -30
```

기대 결과: `BUILD SUCCESSFUL` (기존 테스트 포함 전체 통과)

- [ ] **Step 5: 커밋**

```bash
git add mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java
git add mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/SqlExtractorTest.java
git commit -m "feat(core): SqlExtractor.findMapperFiles() 프로덕트 코드 승격"
```

---

## Task 2: SqlExtractor — static field 제거

**배경:** `static Map<String, String> sqlSnippetRegistry` 필드는 테스트 간 상태를 공유하여 격리가 깨질 수 있다. `buildFakeSql()` 이미 파라미터로 받는 구조이므로 static field만 제거한다.

**Files:**
- Modify: `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java`
- Modify: `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/SqlExtractorTest.java`

- [ ] **Step 1: `SqlExtractor.java`에서 static field 제거**

아래 줄을 찾아 삭제한다.

```java
// 삭제 대상
static Map<String, String> sqlSnippetRegistry;
```

- [ ] **Step 2: `SqlExtractorTest.java`의 `@BeforeEach`에서 직접 할당 코드 제거**

`set()` 메서드에서 아래 줄을 찾아 삭제한다.

```java
// 삭제 대상
SqlExtractor.sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperBaseDir);
```

- [ ] **Step 3: 전체 테스트 통과 확인**

```bash
cd mybatis-sql-analyzer-core
../gradlew test --tests "com.example.sqlanalyzer.core.SqlExtractorTest" 2>&1 | tail -20
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java
git add mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/SqlExtractorTest.java
git commit -m "refactor(core): SqlExtractor static field 제거로 테스트 격리 보장"
```

---

## Task 3: JdbcAnalyzer — `getExplainInfo()` 버그 수정

**배경:** `for (int i = 1; rs.next(); i++) { rs.getString(i) }` 구조는 i가 행 번호인데 컬럼 인덱스로 잘못 사용됨. MySQL/MariaDB의 EXPLAIN은 10개 이상의 컬럼을 가지므로 실제 DB에서는 첫 번째 행만 부분적으로 파싱되고 이후 행은 누락된다.

**Files:**
- Modify: `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/JdbcAnalyzer.java`
- Modify: `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/JdbcAnalyzerTest.java`

- [ ] **Step 1: 실패를 검증하는 테스트 추가**

`JdbcAnalyzerTest.java`의 `doExplain()` 테스트 아래에 추가한다.

```java
@Test
@DisplayName("getExplainInfo - 결과에 컬럼 헤더와 구분선이 포함되어야 함")
void getExplainInfo_includesColumnHeaderAndSeparator() throws Exception {
    // 간단한 SELECT로 EXPLAIN 실행 (H2의 EXPLAIN은 PLAN 컬럼을 가진 단일 컬럼 결과 반환)
    String fakeSql = "SELECT p.payment_id, p.amount FROM payments p WHERE p.amount > ?";
    String result = JdbcAnalyzer.getExplainInfo(connection, fakeSql);

    assertNotNull(result);
    assertFalse(result.isBlank(), "EXPLAIN 결과가 비어있으면 안 됩니다.");
    // 수정 후에는 헤더 행 + 구분선 + 데이터 행 구조여야 함
    assertTrue(result.contains("-"), "컬럼 헤더와 데이터 사이의 구분선이 있어야 합니다.");
    assertTrue(result.lines().count() >= 3, "헤더 행 + 구분선 + 데이터 행 최소 3줄이어야 합니다.");
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd mybatis-sql-analyzer-core
../gradlew test --tests "com.example.sqlanalyzer.core.JdbcAnalyzerTest.getExplainInfo_includesColumnHeaderAndSeparator" 2>&1 | tail -20
```

기대 결과: `FAILED` (구분선 없음)

- [ ] **Step 3: `JdbcAnalyzer.java`의 `getExplainInfo()` 메서드 전체 교체**

```java
/**
 * 지정된 SQL에 대한 EXPLAIN 실행 결과를 반환한다.
 *
 * <p>EXPLAIN은 DB 실행 계획을 보여주며, 인덱스 활용 여부, Full Table Scan 여부 등을
 * 확인할 수 있다. 결과는 '컬럼헤더 | 구분선 | 데이터 행' 형태의 텍스트로 반환한다.
 *
 * <p>이전 구현의 버그: for (int i=1; rs.next(); i++) { rs.getString(i) } 형태는
 * i가 행 번호인데 컬럼 인덱스로 오용됨. MySQL EXPLAIN처럼 여러 컬럼이 있는 경우
 * 첫 번째 행 이후 데이터가 모두 누락되었음.
 *
 * @param connection DB 연결 객체 (호출자가 생명주기를 관리해야 함)
 * @param fakeSql    실행할 SQL (#{}, ${} 가 '?'로 치환된 fakeSql)
 * @return EXPLAIN 결과 텍스트 (컬럼헤더 + 구분선 + 데이터 행)
 * @throws SQLException EXPLAIN 실행 실패 시
 */
public static String getExplainInfo(Connection connection, String fakeSql) throws SQLException {
    // PreparedStatement 대신 Statement 사용: EXPLAIN은 바인딩 파라미터가 없는 정적 명령
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery("EXPLAIN " + fakeSql)) {

        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        StringBuilder result = new StringBuilder();

        // 헤더 행: 컬럼명을 '|' 구분자로 출력
        for (int col = 1; col <= columnCount; col++) {
            result.append(meta.getColumnName(col));
            if (col < columnCount) {
                result.append(" | ");
            }
        }
        result.append("\n");
        result.append("-".repeat(60)).append("\n");

        // 데이터 행: 각 행의 모든 컬럼 값을 '|' 구분자로 출력
        while (rs.next()) {
            for (int col = 1; col <= columnCount; col++) {
                String value = rs.getString(col);
                result.append(value != null ? value : "NULL");
                if (col < columnCount) {
                    result.append(" | ");
                }
            }
            result.append("\n");
        }

        return result.toString();
    }
}
```

- [ ] **Step 4: 전체 JdbcAnalyzerTest 통과 확인**

```bash
cd mybatis-sql-analyzer-core
../gradlew test --tests "com.example.sqlanalyzer.core.JdbcAnalyzerTest" 2>&1 | tail -20
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/JdbcAnalyzer.java
git add mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/JdbcAnalyzerTest.java
git commit -m "fix(core): JdbcAnalyzer.getExplainInfo() ResultSet 컬럼 파싱 버그 수정"
```

---

## Task 4: PromptGenerator — `mapperPath` 타입 `String` → `Path` 변경

**배경:** `Path` 타입으로 통일하면 IntelliJ 플러그인에서 `VirtualFile.getPath()`를 바로 `Path.of()`로 변환하여 전달할 수 있다. `getQueryIdDetail()`도 `Path` 오버로드를 추가한다.

**Files:**
- Modify: `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java`
- Modify: `mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/PromptGenerator.java`
- Modify: `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/JdbcAnalyzerTest.java`
- Modify: `mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/PromptGeneratorTest.java`

- [ ] **Step 1: `SqlExtractor.java`에 `getQueryIdDetail()` Path 오버로드 추가**

기존 `getQueryIdDetail(String queryId, String mapperPath)` 메서드 아래에 추가한다.

```java
/**
 * {@link #getQueryIdDetail(String, String)}의 Path 오버로드.
 *
 * <p>IntelliJ 플러그인 등 Path 기반 코드에서 toString() 변환 없이 바로 호출할 수 있도록
 * 편의 메서드로 제공한다.
 *
 * @param queryId    분석 대상 쿼리 ID
 * @param mapperPath 매퍼 XML 파일 경로 (Path 타입)
 * @return 해당 queryId의 DOM Node (없으면 null)
 */
public static Node getQueryIdDetail(String queryId, Path mapperPath)
        throws ParserConfigurationException, SAXException, IOException {
    // Path 를 String으로 변환하여 기존 구현 재사용
    return getQueryIdDetail(queryId, mapperPath.toString());
}
```

- [ ] **Step 2: `PromptGenerator.java`의 `generatePrompt()` 시그니처 변경**

`generatePrompt()` 메서드에서 `String mapperPath` → `Path mapperPath`로 변경하고 내부 호출도 수정한다.

```java
/**
 * 쿼리 튜닝 AI 프롬프트를 생성한다.
 *
 * <p>실행 순서:
 * 1. queryId에 해당하는 DOM Node 추출
 * 2. 원본 동적 쿼리 XML 문자열로 직렬화
 * 3. MyBatis 태그를 제거한 fakeSql 생성 (EXPLAIN 실행 가능한 형태)
 * 4. fakeSql로 EXPLAIN 실행
 * 5. fakeSql에서 테이블 목록 추출 후 메타데이터(컬럼/인덱스) 조회
 * 6. 수집된 정보를 AI 프롬프트 형식으로 조합
 *
 * @param connection    DB 연결 객체 (호출자가 try-with-resources로 관리해야 함)
 * @param queryId       분석할 MyBatis 쿼리 ID
 * @param mapperPath    queryId가 포함된 매퍼 XML 파일 경로
 * @param mapperPathDir 전체 매퍼 XML 파일들의 루트 디렉토리 (<sql> 태그 캐싱에 사용)
 * @return 완성된 AI 프롬프트 (StringBuilder)
 * @throws Exception DB 연결, 파일 파싱 등 분석 과정의 모든 예외
 */
public static StringBuilder generatePrompt(Connection connection, String queryId,
                                            Path mapperPath, Path mapperPathDir) throws Exception {
    // 1. queryId의 동적 쿼리 Node 추출
    Node queryNode = SqlExtractor.getQueryIdDetail(queryId, mapperPath);

    // 2. queryId의 동적쿼리 원본 XML 문자열 추출
    String originalQuery = SqlExtractor.nodeToString(queryNode);

    // 3. MyBatis 동적 태그를 제거한 fakeSql 생성
    String namespace;
    String fakeSql = null;

    if (queryNode != null) {
        namespace = queryNode.getOwnerDocument().getDocumentElement().getAttribute("namespace");
        fakeSql = SqlExtractor.buildFakeSql(
                queryNode, true, namespace, SqlExtractor.getSqlSnippetRegistry(mapperPathDir));
    }

    String explainInfo = null;
    String metaDataInfo = null;

    if (fakeSql != null) {
        // 4. fakeSql로 EXPLAIN 실행
        explainInfo = JdbcAnalyzer.getExplainInfo(connection, fakeSql);

        // 5. 사용된 테이블의 메타데이터(컬럼 타입, 인덱스) 추출
        Set<String> tables = JdbcAnalyzer.extractTableMethod(fakeSql);
        metaDataInfo = JdbcAnalyzer.getMetaDataInfo(tables, connection.getMetaData()).toString();
    }

    StringBuilder prompt = new StringBuilder();

    // 페르소나 및 환경 설정: AI가 PG 도메인 전문가로 동작하도록 역할을 명시
    prompt.append("너는 10년 차 수석 DBA이자 쿼리 튜닝 전문가야.\n");
    prompt.append("우리 시스템은 대용량 트랜잭션이 발생하는 환경이며, MyBatis를 이용해 데이터베이스와 통신하고 있어.\n");
    prompt.append("아래 제공된 쿼리와 실행 계획(Explain), 데이터베이스 메타데이터를 종합적으로 분석해서 정밀한 쿼리 튜닝 리포트를 작성해 줘.\n\n");

    // 실제 데이터 컨텍스트 주입
    prompt.append("[Original Query] : ").append(originalQuery).append("\n");
    prompt.append("[Remove Tag Query] : ").append(fakeSql).append("\n");
    prompt.append("[Explain] : ").append(explainInfo).append("\n");
    prompt.append("[MetaData] : ").append(metaDataInfo).append("\n");

    // 출력 형식(Output Format) 지시: 목차 기반으로 구조적 답변 유도
    prompt.append("[요청 사항]\n");
    prompt.append("다음 목차에 따라 분석 결과와 개선안을 제시해 줘:\n");
    prompt.append("1. 실행 계획 분석: 병목 구간(예: Full Table Scan, Filesort, 비효율적인 Join 등)과 원인 파악\n");
    prompt.append("2. 인덱스 최적화 제안: 메타데이터를 참고하여, 성능 향상을 위해 추가하거나 수정해야 할 DDL(CREATE INDEX 등)이 있다면 구체적인 이유와 함께 작성\n");
    prompt.append("3. MyBatis 동적 쿼리 리뷰: [Original Query]의 <if>, <foreach> 등의 구조상, 파라미터 조건에 따라 성능 널뛰기나 인덱스 무력화가 발생할 위험이 있는지 점검\n");
    prompt.append("4. 개선된 쿼리 및 매퍼 제안: 최적화가 적용된 최종 SQL과 이를 반영한 MyBatis XML 코드를 작성\n");

    return prompt;
}
```

- [ ] **Step 3: `PromptGeneratorTest.java`의 `mapperPath` 타입 수정**

`PromptGeneratorTest.java`에서 아래와 같이 필드를 수정한다.

```java
// 변경 전
private final String mapperPath = "src/test/resources/mapper/TestMapper.xml";

// 변경 후
private final Path mapperPath = Path.of("src/test/resources/mapper/TestMapper.xml");
```

import에 `java.nio.file.Path`를 추가한다.

- [ ] **Step 4: `JdbcAnalyzerTest.java`의 `mapperPath` 타입 수정**

`JdbcAnalyzerTest.java`에서 아래와 같이 필드를 수정한다.

```java
// 변경 전
private final String mapperPath = "src/test/resources/mapper/TestMapper.xml";

// 변경 후
private final Path mapperPath = Path.of("src/test/resources/mapper/TestMapper.xml");
```

`doExplain()`과 `extractTables()`, `extractDDLAndIndex()` 내부의 `getQueryIdDetail(queryId, mapperPath)` 호출은 타입이 맞으므로 그대로 유지한다.

- [ ] **Step 5: 전체 core 테스트 통과 확인**

```bash
cd mybatis-sql-analyzer-core
../gradlew test 2>&1 | tail -30
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/SqlExtractor.java
git add mybatis-sql-analyzer-core/src/main/java/com/example/sqlanalyzer/core/PromptGenerator.java
git add mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/PromptGeneratorTest.java
git add mybatis-sql-analyzer-core/src/test/java/com/example/sqlanalyzer/core/JdbcAnalyzerTest.java
git commit -m "refactor(core): mapperPath 파라미터 타입 String→Path 통일, getQueryIdDetail Path 오버로드 추가"
```

---

## Task 5: IntelliJ 모듈 골격 생성

**배경:** 새 Gradle 모듈을 등록하고 IntelliJ Platform SDK와 plugin.xml을 설정한다.

**Files:**
- Create: `mybatis-sql-analyzer-intellij/build.gradle`
- Create: `mybatis-sql-analyzer-intellij/src/main/resources/META-INF/plugin.xml`
- Modify: `settings.gradle`

- [ ] **Step 1: `settings.gradle`에 모듈 추가**

`settings.gradle` 파일 마지막 줄에 아래를 추가한다.

```groovy
include 'mybatis-sql-analyzer-intellij'
```

- [ ] **Step 2: 디렉토리 구조 생성**

```bash
mkdir -p mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/{action,toolwindow,service,config}
mkdir -p mybatis-sql-analyzer-intellij/src/main/resources/META-INF
mkdir -p mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/{config,service}
```

- [ ] **Step 3: `mybatis-sql-analyzer-intellij/build.gradle` 작성**

```groovy
plugins {
    id 'java'
    // IntelliJ Platform Gradle Plugin 1.x: plugin.xml, runIde, buildPlugin 태스크 제공
    id 'org.jetbrains.intellij' version '1.17.4'
}

group = 'com.example'
version = '0.1.0'

// IntelliJ SDK 는 자체 JDK를 사용하므로 toolchain 대신 sourceCompatibility 사용
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {
    // 타입 'IC' = IntelliJ IDEA Community Edition (무료, 의존성 최소화)
    version = '2024.1'
    type = 'IC'
    // 추가 플러그인 없음: 플랫폼 기본 기능만 사용
    plugins = []
}

repositories {
    mavenCentral()
}

dependencies {
    // core 모듈: SqlExtractor, JdbcAnalyzer, PromptGenerator 포함
    implementation project(':mybatis-sql-analyzer-core')

    // JDBC 드라이버: core에서 compileOnly로 선언되어 있어 플러그인 실행 시 번들 필요
    implementation 'org.mariadb.jdbc:mariadb-java-client:3.3.3'
    implementation 'com.mysql:mysql-connector-j:8.3.0'

    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'

    // 테스트: SqlAnalyzerConfig, SqlAnalyzerService 는 순수 JUnit으로 테스트
    testImplementation 'com.h2database:h2:2.2.224'
    testImplementation platform('org.junit:junit-bom:5.10.1')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: `plugin.xml` 작성**

`mybatis-sql-analyzer-intellij/src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <!-- 플러그인 고유 ID: 마켓플레이스 등록 시에도 이 ID를 사용 -->
    <id>com.example.mybatis-sql-analyzer-intellij</id>
    <name>MyBatis SQL Analyzer</name>
    <version>0.1.0</version>
    <vendor>wiezon</vendor>
    <description>
        MyBatis 매퍼 XML에서 특정 queryId를 선택하여 실제 DB의 EXPLAIN과 스키마 정보를
        기반으로 AI 쿼리 튜닝 프롬프트를 자동 생성합니다.
    </description>

    <!-- com.intellij.modules.platform: 모든 JetBrains IDE에서 동작하는 최소 의존성 -->
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Tool Window: IDE 우측에 고정 패널로 등록 -->
        <toolWindow id="MyBatis SQL Analyzer"
                    anchor="right"
                    factoryClass="com.example.sqlanalyzer.intellij.toolwindow.SqlAnalyzerToolWindow"/>
    </extensions>

    <actions>
        <!-- XML 에디터 우클릭 메뉴에 분석 액션 추가 -->
        <action id="com.example.sqlanalyzer.intellij.action.AnalyzeSqlAction"
                class="com.example.sqlanalyzer.intellij.action.AnalyzeSqlAction"
                text="Analyze SQL with AI"
                description="선택된 MyBatis 매퍼 XML의 SQL 성능 분석 AI 프롬프트를 생성합니다.">
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
        </action>
    </actions>
</idea-plugin>
```

- [ ] **Step 5: 모듈 인식 확인**

```bash
./gradlew projects 2>&1 | grep mybatis
```

기대 결과:
```
+--- Project ':mybatis-sql-analyzer-core'
+--- Project ':mybatis-sql-analyzer-intellij'
+--- Project ':mybatis-sql-analyzer-plugin'
```

- [ ] **Step 6: 커밋**

```bash
git add settings.gradle
git add mybatis-sql-analyzer-intellij/
git commit -m "feat(intellij): IntelliJ 플러그인 모듈 골격 생성 (build.gradle, plugin.xml)"
```

---

## Task 6: SqlAnalyzerConfig — 설정 파일 파싱 (TDD)

**배경:** 프로젝트 루트의 `.sql-analyzer.properties`를 읽어 JDBC 연결 정보와 매퍼 경로를 반환한다. IntelliJ API에 의존하지 않으므로 순수 JUnit으로 테스트한다.

**Files:**
- Create: `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/config/SqlAnalyzerConfig.java`
- Create: `mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/config/SqlAnalyzerConfigTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`SqlAnalyzerConfigTest.java`:

```java
package com.example.sqlanalyzer.intellij.config;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class SqlAnalyzerConfigTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("sql-analyzer-config-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        // 임시 디렉토리 정리 (역순 정렬로 하위 파일부터 삭제)
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    @Test
    @DisplayName("정상적인 properties 파일 로드 - 4개 필수 키 모두 존재")
    void load_success() throws IOException {
        Path configFile = tempDir.resolve(".sql-analyzer.properties");
        Files.writeString(configFile,
                "jdbc.url=jdbc:h2:mem:test\n" +
                "jdbc.user=sa\n" +
                "jdbc.password=\n" +
                "mapper.base.dir=src/main/resources/mapper\n"
        );

        SqlAnalyzerConfig config = SqlAnalyzerConfig.load(configFile);

        assertEquals("jdbc:h2:mem:test", config.getJdbcUrl());
        assertEquals("sa", config.getJdbcUser());
        assertEquals("", config.getJdbcPassword());
        assertEquals("src/main/resources/mapper", config.getMapperBaseDir());
    }

    @Test
    @DisplayName("필수 키 누락 시 IllegalStateException 발생")
    void load_missingRequiredKey_throwsIllegalStateException() throws IOException {
        Path configFile = tempDir.resolve(".sql-analyzer.properties");
        // jdbc.password 와 mapper.base.dir 키 자체가 없는 경우
        Files.writeString(configFile,
                "jdbc.url=jdbc:h2:mem:test\n" +
                "jdbc.user=sa\n"
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> SqlAnalyzerConfig.load(configFile)
        );
        assertTrue(ex.getMessage().contains(".sql-analyzer.properties"));
    }

    @Test
    @DisplayName("파일이 존재하지 않을 때 IOException 발생")
    void load_fileNotFound_throwsIOException() {
        Path nonExistent = tempDir.resolve("nonexistent.properties");
        assertThrows(IOException.class, () -> SqlAnalyzerConfig.load(nonExistent));
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 에러로 실패하는지 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew test --tests "com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfigTest" 2>&1 | tail -10
```

기대 결과: `error: cannot find symbol`

- [ ] **Step 3: `SqlAnalyzerConfig.java` 구현**

```java
package com.example.sqlanalyzer.intellij.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 프로젝트 루트의 .sql-analyzer.properties 파일을 읽어 DB 연결 정보와 매퍼 경로를 제공한다.
 *
 * <p>설정 파일 위치: {프로젝트 루트}/.sql-analyzer.properties
 *
 * <p>필수 키 (키가 아예 없으면 IllegalStateException):
 * <ul>
 *   <li>jdbc.url        - JDBC 연결 URL (예: jdbc:mariadb://localhost:3306/mydb)</li>
 *   <li>jdbc.user       - DB 사용자명</li>
 *   <li>jdbc.password   - DB 비밀번호 (빈 값 허용 — H2 등 비밀번호 없는 DB 지원)</li>
 *   <li>mapper.base.dir - 매퍼 XML 루트 디렉토리 (프로젝트 루트 기준 상대경로)</li>
 * </ul>
 *
 * <p>보안 주의: jdbc.password가 평문으로 저장되므로 .gitignore에 반드시 추가할 것.
 */
public class SqlAnalyzerConfig {

    private static final String JDBC_URL_KEY        = "jdbc.url";
    private static final String JDBC_USER_KEY       = "jdbc.user";
    private static final String JDBC_PASSWORD_KEY   = "jdbc.password";
    private static final String MAPPER_BASE_DIR_KEY = "mapper.base.dir";

    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final String mapperBaseDir;

    private SqlAnalyzerConfig(String jdbcUrl, String jdbcUser,
                               String jdbcPassword, String mapperBaseDir) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.mapperBaseDir = mapperBaseDir;
    }

    /**
     * 지정된 경로의 properties 파일을 읽어 설정 객체를 생성한다.
     *
     * @param configPath .sql-analyzer.properties 파일의 절대 경로
     * @return 파싱된 설정 객체
     * @throws IOException           파일을 열거나 읽는 데 실패한 경우
     * @throws IllegalStateException 필수 키가 파일에 없는 경우
     */
    public static SqlAnalyzerConfig load(Path configPath) throws IOException {
        Properties properties = new Properties();

        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }

        // 4개 키 모두 파일에 존재해야 함 (값은 빈 문자열 허용, 키 자체가 없으면 예외)
        String jdbcUrl        = requireKey(properties, JDBC_URL_KEY);
        String jdbcUser       = requireKey(properties, JDBC_USER_KEY);
        // password는 빈 값을 허용하므로 requireKey 사용 (null만 차단)
        String jdbcPassword   = requireKey(properties, JDBC_PASSWORD_KEY);
        String mapperBaseDir  = requireKey(properties, MAPPER_BASE_DIR_KEY);

        return new SqlAnalyzerConfig(jdbcUrl, jdbcUser, jdbcPassword, mapperBaseDir);
    }

    /**
     * 프로퍼티에서 키 값을 반환한다. 키가 없으면 IllegalStateException을 발생시킨다.
     *
     * <p>빈 문자열("")은 허용한다 — jdbc.password처럼 비밀번호가 없는 경우를 지원하기 위함.
     */
    private static String requireKey(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null) {
            throw new IllegalStateException(
                    ".sql-analyzer.properties에 필수 키가 없습니다: " + key +
                    "\n설정 파일 예시:\n" +
                    "  jdbc.url=jdbc:mariadb://localhost:3306/mydb\n" +
                    "  jdbc.user=root\n" +
                    "  jdbc.password=secret\n" +
                    "  mapper.base.dir=src/main/resources/mapper"
            );
        }

        return value.trim();
    }

    public String getJdbcUrl()       { return jdbcUrl; }
    public String getJdbcUser()      { return jdbcUser; }
    public String getJdbcPassword()  { return jdbcPassword; }
    public String getMapperBaseDir() { return mapperBaseDir; }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew test --tests "com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfigTest" 2>&1 | tail -20
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/config/SqlAnalyzerConfig.java
git add mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/config/SqlAnalyzerConfigTest.java
git commit -m "feat(intellij): SqlAnalyzerConfig - .sql-analyzer.properties 파싱 구현"
```

---

## Task 7: SqlAnalyzerService — 분석 오케스트레이터 (TDD)

**배경:** core 모듈을 순서대로 호출하여 AI 프롬프트를 생성한다. IntelliJ API에 의존하지 않으므로 H2 기반 JUnit 테스트가 가능하다.

**Files:**
- Create: `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/service/SqlAnalyzerService.java`
- Create: `mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/service/SqlAnalyzerServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`SqlAnalyzerServiceTest.java`:

```java
package com.example.sqlanalyzer.intellij.service;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.sql.*;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlAnalyzerServiceTest {

    // TestMapper.xml 에 정의된 queryId (테스트 리소스 파일과 일치)
    private static final String QUERY_ID = "findBadPerformancePayments";

    private Path tempDir;
    private Connection connection;
    private SqlAnalyzerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SqlAnalyzerService();
        tempDir = Files.createTempDirectory("sql-analyzer-service-test");

        // H2 인메모리 DB 세팅 (analyze() 호출 전 테이블이 있어야 EXPLAIN 실행 가능)
        // DB_CLOSE_DELAY=-1: 이 connection이 열려 있는 동안 DB 유지
        connection = DriverManager.getConnection("jdbc:h2:mem:svctest;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE payments (payment_id VARCHAR(50) PRIMARY KEY, order_id VARCHAR(50), amount DECIMAL(10,2), status VARCHAR(20), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE orders (order_id VARCHAR(50) PRIMARY KEY, user_id VARCHAR(50), mid VARCHAR(10))");
            stmt.execute("CREATE TABLE merchants (mid VARCHAR(10) PRIMARY KEY, merchant_name VARCHAR(100), status VARCHAR(20))");
        }

        // .sql-analyzer.properties: analyze() 내부에서 이 파일을 읽어 JDBC 연결 생성
        Path configFile = tempDir.resolve(".sql-analyzer.properties");
        Files.writeString(configFile,
                "jdbc.url=jdbc:h2:mem:svctest;DB_CLOSE_DELAY=-1\n" +
                "jdbc.user=sa\n" +
                "jdbc.password=\n" +
                "mapper.base.dir=../mybatis-sql-analyzer-core/src/test/resources/mapper\n"
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    @DisplayName("analyze - 프롬프트에 필수 섹션 포함 여부 검증")
    void analyze_promptContainsRequiredSections() throws Exception {
        Path mapperFile = Path.of("../mybatis-sql-analyzer-core/src/test/resources/mapper/TestMapper.xml");

        String prompt = service.analyze(tempDir.toString(), mapperFile, QUERY_ID);

        assertNotNull(prompt);
        assertTrue(prompt.contains("[Original Query]"), "원본 쿼리 섹션 없음");
        assertTrue(prompt.contains("[Remove Tag Query]"), "fakeSql 섹션 없음");
        assertTrue(prompt.contains("[Explain]"), "Explain 섹션 없음");
        assertTrue(prompt.contains("[MetaData]"), "MetaData 섹션 없음");
        assertTrue(prompt.contains("[요청 사항]"), "요청 사항 섹션 없음");
    }

    @Test
    @DisplayName("findMatchingFiles - queryId 포함 파일 목록 반환")
    void findMatchingFiles_returnsNonEmptyList() throws Exception {
        Path mapperDir = Path.of("../mybatis-sql-analyzer-core/src/test/resources/mapper");
        List<Path> files = service.findMatchingFiles(mapperDir, QUERY_ID);

        assertFalse(files.isEmpty(), "매퍼 파일 목록이 비어있으면 안 됩니다.");
        assertEquals(4, files.size(), "테스트 매퍼 4개 파일에 findBadPerformancePayments 가 있어야 합니다.");
    }

    @Test
    @DisplayName("findMatchingFiles - 존재하지 않는 queryId 는 빈 리스트 반환")
    void findMatchingFiles_unknownQueryId_returnsEmpty() throws Exception {
        Path mapperDir = Path.of("../mybatis-sql-analyzer-core/src/test/resources/mapper");
        List<Path> files = service.findMatchingFiles(mapperDir, "nonExistentQueryId_XYZ");

        assertTrue(files.isEmpty());
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 에러로 실패하는지 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew test --tests "com.example.sqlanalyzer.intellij.service.SqlAnalyzerServiceTest" 2>&1 | tail -10
```

기대 결과: `error: cannot find symbol`

- [ ] **Step 3: `SqlAnalyzerService.java` 구현**

```java
package com.example.sqlanalyzer.intellij.service;

import com.example.sqlanalyzer.core.PromptGenerator;
import com.example.sqlanalyzer.core.SqlExtractor;
import com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * IntelliJ 플러그인의 분석 오케스트레이터.
 *
 * <p>core 모듈의 SqlExtractor, JdbcAnalyzer, PromptGenerator를 순서대로 호출하여
 * AI 쿼리 튜닝 프롬프트를 생성한다.
 *
 * <p>이 클래스는 IntelliJ API(Project, ToolWindow 등)에 직접 의존하지 않으므로
 * 순수 JUnit + H2로 단위 테스트가 가능하다.
 * UI 관련 처리(백그라운드 태스크, 다이얼로그)는 SqlAnalyzerPanel이 담당한다.
 */
@Slf4j
public class SqlAnalyzerService {

    /**
     * 지정된 매퍼 파일의 queryId로 AI 프롬프트를 생성한다.
     *
     * <p>호출 전 전제 조건:
     * 1. projectBasePath/{@code .sql-analyzer.properties} 파일이 존재해야 함
     * 2. 해당 properties의 jdbc.url DB에 분석 대상 테이블이 존재해야 함
     *
     * @param projectBasePath  프로젝트 루트 절대 경로 (IntelliJ의 project.getBasePath() 값)
     * @param selectedMapperFile 분석할 매퍼 XML 파일 경로
     * @param queryId          분석할 MyBatis 쿼리 ID
     * @return 생성된 AI 프롬프트 문자열
     * @throws Exception DB 연결 실패, 파일 파싱 실패, queryId 미존재 등 모든 분석 예외
     */
    public String analyze(String projectBasePath, Path selectedMapperFile, String queryId) throws Exception {
        // 1. 설정 파일 로드 (JDBC 연결 정보 + 매퍼 디렉토리)
        Path configPath = Path.of(projectBasePath, ".sql-analyzer.properties");
        SqlAnalyzerConfig config = SqlAnalyzerConfig.load(configPath);

        log.info("SQL 분석 시작 - queryId: {}, mapperFile: {}", queryId, selectedMapperFile);

        // 2. 설정의 mapper.base.dir 을 절대경로로 변환
        //    (properties에 상대경로로 저장되어 있으면 프로젝트 루트 기준으로 해석)
        Path mapperDir = Path.of(projectBasePath).resolve(config.getMapperBaseDir());

        // 3. JDBC 연결 생성 후 프롬프트 생성 (try-with-resources로 연결 자동 해제)
        try (Connection connection = DriverManager.getConnection(
                config.getJdbcUrl(), config.getJdbcUser(), config.getJdbcPassword())) {

            return PromptGenerator.generatePrompt(connection, queryId, selectedMapperFile, mapperDir).toString();
        }
    }

    /**
     * 지정된 매퍼 디렉토리에서 queryId를 포함하는 파일 목록을 반환한다.
     *
     * <p>UI 레이어(SqlAnalyzerPanel)에서 파일 선택 다이얼로그 구성에 활용한다:
     * - 결과 1개 → 즉시 analyze() 호출
     * - 결과 2개 이상 → 사용자 선택 다이얼로그 표시 후 analyze() 호출
     * - 결과 0개 → 오류 메시지 표시
     *
     * @param mapperBaseDir 매퍼 XML 파일들의 루트 디렉토리
     * @param queryId       검색할 MyBatis 쿼리 ID
     * @return queryId를 포함하는 파일 경로 목록 (없으면 빈 List)
     */
    public List<Path> findMatchingFiles(Path mapperBaseDir, String queryId) throws Exception {
        return SqlExtractor.findMapperFiles(mapperBaseDir, queryId);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew test --tests "com.example.sqlanalyzer.intellij.service.SqlAnalyzerServiceTest" 2>&1 | tail -20
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/service/SqlAnalyzerService.java
git add mybatis-sql-analyzer-intellij/src/test/java/com/example/sqlanalyzer/intellij/service/SqlAnalyzerServiceTest.java
git commit -m "feat(intellij): SqlAnalyzerService 구현 - core 모듈 오케스트레이션"
```

---

## Task 8: XmlQueryIdReader — XML에서 queryId 목록 추출

**배경:** Tool Window의 queryId 드롭다운을 채울 때 사용. 단일 XML 파일에서 DML 태그(select/insert/update/delete)의 id 속성만 뽑아낸다.

**Files:**
- Create: `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/XmlQueryIdReader.java`

- [ ] **Step 1: `XmlQueryIdReader.java` 작성**

```java
package com.example.sqlanalyzer.intellij.toolwindow;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 단일 MyBatis 매퍼 XML 파일에서 DML 태그의 id 목록을 읽어온다.
 *
 * <p>Tool Window의 queryId 드롭다운을 자동으로 구성할 때 사용한다.
 * SqlAnalyzerPanel에서만 사용되는 패키지-프라이빗 유틸리티 클래스다.
 *
 * <p>대상 태그: select, insert, update, delete (MyBatis DML 태그 전체)
 */
class XmlQueryIdReader {

    private static final String[] DML_TAGS = {"select", "insert", "update", "delete"};

    // 유틸리티 클래스이므로 인스턴스화 금지
    private XmlQueryIdReader() {}

    /**
     * 지정된 XML 파일에서 DML 태그의 id 속성 목록을 반환한다.
     *
     * <p>MyBatis DTD 검증을 비활성화하여 오프라인 환경에서도 파싱이 가능하다.
     *
     * @param mapperFilePath 매퍼 XML 파일 경로
     * @return id 목록 (태그 출현 순서대로 반환, 없으면 빈 리스트)
     * @throws ParserConfigurationException XML 파서 설정 오류 시
     * @throws IOException                  파일 읽기 실패 시
     * @throws SAXException                 XML 파싱 오류 시
     */
    static List<String> readIds(Path mapperFilePath)
            throws ParserConfigurationException, IOException, SAXException {

        List<String> ids = new ArrayList<>();

        // MyBatis DTD 검증 비활성화: 인터넷 없는 환경에서 네트워크 요청 방지
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(mapperFilePath.toFile());

        for (String tag : DML_TAGS) {
            NodeList nodeList = doc.getElementsByTagName(tag);

            for (int i = 0; i < nodeList.getLength(); i++) {
                Node idAttr = nodeList.item(i).getAttributes().getNamedItem("id");

                if (idAttr != null) {
                    ids.add(idAttr.getNodeValue());
                }
            }
        }

        return ids;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew compileJava 2>&1 | tail -10
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/XmlQueryIdReader.java
git commit -m "feat(intellij): XmlQueryIdReader - XML DML id 목록 추출 유틸리티"
```

---

## Task 9: SqlAnalyzerPanel — Tool Window UI

**배경:** Tool Window의 메인 패널. 상단에 입력 폼(매퍼 디렉토리, queryId 드롭다운, Analyze 버튼), 중앙에 결과 텍스트 영역, 하단에 클립보드 복사 버튼을 배치한다. Analyze 클릭 시 IntelliJ ProgressManager 백그라운드 태스크로 실행한다.

**Files:**
- Create: `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/SqlAnalyzerPanel.java`

- [ ] **Step 1: `SqlAnalyzerPanel.java` 작성**

```java
package com.example.sqlanalyzer.intellij.toolwindow;

import com.example.sqlanalyzer.intellij.service.SqlAnalyzerService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.List;

/**
 * MyBatis SQL Analyzer Tool Window의 메인 UI 패널.
 *
 * <p>레이아웃 구조:
 * <pre>
 * ┌─ MyBatis SQL Analyzer ──────────────────────┐
 * │  Mapper Dir : [경로 입력창          ] [...]  │  ← 상단 입력 폼
 * │  Query ID   : [드롭다운             ] [▼]   │
 * │                             [Analyze]        │
 * ├─────────────────────────────────────────────┤
 * │  (분석 결과 / AI 프롬프트 텍스트)             │  ← 중앙 결과 영역
 * ├─────────────────────────────────────────────┤
 * │                   [Copy to Clipboard]        │  ← 하단 액션 버튼
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>IntelliJ EDT(Event Dispatch Thread) 규칙 준수:
 * - UI 컴포넌트 갱신은 반드시 EDT에서 실행 (ProgressTask의 onSuccess()는 EDT에서 호출됨)
 * - DB 연결 등 I/O 작업은 ProgressManager 백그라운드 태스크에서 실행 (EDT 블로킹 방지)
 */
@Slf4j
public class SqlAnalyzerPanel extends JPanel {

    private final Project project;
    private final SqlAnalyzerService service;

    private JTextField mapperDirField;
    private JComboBox<String> queryIdCombo;
    private JButton analyzeButton;
    private JTextArea resultArea;
    private JButton copyButton;

    public SqlAnalyzerPanel(Project project) {
        this.project = project;
        this.service = new SqlAnalyzerService();
        initComponents();
    }

    /**
     * 외부(AnalyzeSqlAction)에서 XML 파일 경로를 받아 UI를 초기화한다.
     *
     * <p>매퍼 파일의 부모 디렉토리를 mapperDir 필드에 자동 세팅하고,
     * 해당 XML 파일의 DML id 목록을 드롭다운에 로드한다.
     *
     * @param mapperFilePath 에디터에서 우클릭한 XML 파일의 절대 경로
     */
    public void setMapperFile(String mapperFilePath) {
        Path mapperPath = Path.of(mapperFilePath);

        // 파일의 부모 디렉토리를 매퍼 베이스 디렉토리로 설정
        mapperDirField.setText(mapperPath.getParent().toString());

        // 해당 XML에 정의된 queryId 목록을 드롭다운에 로드
        loadQueryIds(mapperFilePath);
    }

    /**
     * XML 파일에서 DML 태그의 id 목록을 읽어 드롭다운(queryIdCombo)을 갱신한다.
     *
     * <p>파싱 실패 시 사용자에게 오류 다이얼로그를 표시하며, 드롭다운은 비워 둔다.
     */
    private void loadQueryIds(String mapperFilePath) {
        queryIdCombo.removeAllItems();

        try {
            List<String> ids = XmlQueryIdReader.readIds(Path.of(mapperFilePath));
            ids.forEach(queryIdCombo::addItem);
        } catch (Exception e) {
            log.error("queryId 목록 로드 실패: {}", mapperFilePath, e);
            Messages.showErrorDialog(project,
                    "queryId 목록을 불러올 수 없습니다:\n" + e.getMessage(), "파싱 오류");
        }
    }

    /**
     * 컴포넌트 초기화 및 레이아웃 배치.
     */
    private void initComponents() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildInputPanel(), BorderLayout.NORTH);
        add(buildResultPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    /**
     * 상단 입력 폼: 매퍼 디렉토리 필드, queryId 드롭다운, Analyze 버튼.
     */
    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 행 0: Mapper Dir 레이블 + 입력 필드 + 선택 버튼
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel("Mapper Dir:"), gbc);

        mapperDirField = new JTextField();
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(mapperDirField, gbc);

        JButton browseButton = new JButton("...");
        browseButton.setToolTipText("매퍼 XML 파일 디렉토리를 선택합니다.");
        browseButton.addActionListener(e -> browseMapperDir());
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(browseButton, gbc);

        // 행 1: Query ID 레이블 + 드롭다운 + Analyze 버튼
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Query ID:"), gbc);

        queryIdCombo = new JComboBox<>();
        // editable=true: 드롭다운에 없는 queryId도 직접 입력 가능
        queryIdCombo.setEditable(true);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(queryIdCombo, gbc);

        analyzeButton = new JButton("Analyze");
        analyzeButton.addActionListener(e -> runAnalysis());
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(analyzeButton, gbc);

        return panel;
    }

    /**
     * 중앙 결과 영역: 스크롤 가능한 읽기 전용 텍스트 영역.
     */
    private JScrollPane buildResultPanel() {
        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        return new JScrollPane(resultArea);
    }

    /**
     * 하단 액션 패널: 클립보드 복사 버튼 (분석 완료 전까지 비활성).
     */
    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        copyButton = new JButton("Copy to Clipboard");
        copyButton.setEnabled(false);
        copyButton.addActionListener(e -> copyToClipboard());
        panel.add(copyButton);
        return panel;
    }

    /**
     * IntelliJ 디렉토리 선택 다이얼로그를 열어 매퍼 디렉토리를 선택한다.
     */
    private void browseMapperDir() {
        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
                null
        );

        if (chosen != null) {
            mapperDirField.setText(chosen.getPath());
        }
    }

    /**
     * 분석을 IntelliJ ProgressManager 백그라운드 태스크로 실행한다.
     *
     * <p>백그라운드 태스크를 사용하는 이유:
     * DB EXPLAIN 실행 및 메타데이터 조회가 수 초 걸릴 수 있어,
     * EDT에서 직접 실행하면 IntelliJ UI 전체가 응답 불가 상태(freezing)가 된다.
     * ProgressManager는 진행 표시줄을 자동으로 관리하며 취소 기능도 제공한다.
     */
    private void runAnalysis() {
        String queryId = (String) queryIdCombo.getSelectedItem();
        String mapperDir = mapperDirField.getText().trim();

        if (queryId == null || queryId.isBlank()) {
            Messages.showWarningDialog(project, "Query ID를 입력하세요.", "입력 오류");
            return;
        }

        if (mapperDir.isBlank()) {
            Messages.showWarningDialog(project, "Mapper Dir을 지정하세요.", "입력 오류");
            return;
        }

        // 분석 시작 전 UI 상태 초기화
        analyzeButton.setEnabled(false);
        copyButton.setEnabled(false);
        resultArea.setText("분석 중...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "MyBatis SQL 분석 중...") {
            private String result;
            private Exception error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 이 블록은 백그라운드 스레드에서 실행됨 (EDT 아님)
                indicator.setIndeterminate(true);
                indicator.setText("매퍼 파일 검색 중...");

                try {
                    Path mapperDirPath = Path.of(mapperDir);
                    List<Path> matchedFiles = service.findMatchingFiles(mapperDirPath, queryId);

                    if (matchedFiles.isEmpty()) {
                        error = new IllegalArgumentException(
                                "'" + queryId + "' 를 포함하는 매퍼 파일을 찾을 수 없습니다.\n" +
                                "Mapper Dir 경로와 queryId를 확인하세요."
                        );
                        return;
                    }

                    Path selectedFile;
                    if (matchedFiles.size() == 1) {
                        selectedFile = matchedFiles.get(0);
                    } else {
                        // 여러 파일에 동일 queryId가 있을 때: EDT에서 선택 다이얼로그 표시
                        selectedFile = selectFileFromDialog(matchedFiles);
                    }

                    // 사용자가 다이얼로그를 취소한 경우
                    if (selectedFile == null) {
                        return;
                    }

                    indicator.setText("DB 분석 중...");
                    result = service.analyze(project.getBasePath(), selectedFile, queryId);

                } catch (Exception e) {
                    error = e;
                }
            }

            @Override
            public void onSuccess() {
                // onSuccess()는 EDT에서 실행됨 → UI 컴포넌트 직접 갱신 가능
                analyzeButton.setEnabled(true);

                if (error != null) {
                    resultArea.setText("오류 발생:\n" + error.getMessage());
                    log.error("SQL 분석 실패 - queryId: {}", queryId, error);
                } else if (result != null) {
                    resultArea.setText(result);
                    resultArea.setCaretPosition(0); // 스크롤을 맨 위로
                    copyButton.setEnabled(true);
                }
            }

            @Override
            public void onCancel() {
                analyzeButton.setEnabled(true);
                resultArea.setText("분석이 취소되었습니다.");
            }
        });
    }

    /**
     * 동일한 queryId를 포함하는 여러 파일 중 하나를 사용자가 선택하도록 다이얼로그를 표시한다.
     *
     * <p>백그라운드 스레드에서 호출되므로 invokeAndWait으로 EDT 전환 후 다이얼로그를 표시한다.
     * invokeAndWait을 사용하는 이유: 다이얼로그 결과(사용자 선택)를 백그라운드 스레드로
     * 돌아온 후에도 사용해야 하므로 비동기(invokeLater)가 아닌 동기 방식이 필요하다.
     *
     * @param files queryId를 포함하는 파일 목록
     * @return 사용자가 선택한 파일, 취소 시 null
     */
    private Path selectFileFromDialog(List<Path> files) {
        String[] options = files.stream()
                .map(p -> p.getFileName().toString())
                .toArray(String[]::new);

        int[] choiceIndex = {-1};

        ApplicationManager.getApplication().invokeAndWait(() -> {
            choiceIndex[0] = Messages.showChooseDialog(
                    project,
                    "동일한 queryId가 여러 파일에 존재합니다.\n분석할 파일을 선택하세요.",
                    "매퍼 파일 선택",
                    Messages.getQuestionIcon(),
                    options,
                    options[0]
            );
        });

        return choiceIndex[0] >= 0 ? files.get(choiceIndex[0]) : null;
    }

    /**
     * 결과 텍스트를 시스템 클립보드에 복사하고 2초간 버튼 레이블로 피드백을 준다.
     */
    private void copyToClipboard() {
        String text = resultArea.getText();
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);

        // 복사 완료 피드백: 버튼 텍스트를 일시적으로 변경
        copyButton.setText("Copied!");
        Timer timer = new Timer(2000, e -> copyButton.setText("Copy to Clipboard"));
        timer.setRepeats(false);
        timer.start();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew compileJava 2>&1 | tail -15
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/SqlAnalyzerPanel.java
git commit -m "feat(intellij): SqlAnalyzerPanel UI 구현 - 입력 폼, 결과 영역, 클립보드 복사"
```

---

## Task 10: SqlAnalyzerToolWindow + AnalyzeSqlAction 구현

**배경:** Tool Window 팩토리와 에디터 우클릭 액션을 구현한다. `AnalyzeSqlAction`은 XML 파일이고 `<mapper` 태그를 포함할 때만 메뉴를 노출한다.

**Files:**
- Create: `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/SqlAnalyzerToolWindow.java`
- Create: `mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/action/AnalyzeSqlAction.java`

- [ ] **Step 1: `SqlAnalyzerToolWindow.java` 작성**

```java
package com.example.sqlanalyzer.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * IntelliJ Tool Window 팩토리.
 *
 * <p>plugin.xml의 toolWindow 확장 포인트에 등록되어, IDE 시작 시 또는
 * 사용자가 Tool Window를 처음 열 때 호출된다.
 * 실제 UI 컴포넌트 생성은 SqlAnalyzerPanel에 위임한다.
 *
 * <p>Tool Window ID "MyBatis SQL Analyzer" 는 plugin.xml 및 AnalyzeSqlAction과
 * 반드시 일치해야 한다.
 */
public class SqlAnalyzerToolWindow implements ToolWindowFactory {

    /**
     * Tool Window 콘텐츠를 생성한다. IDE가 Tool Window를 처음 열 때 한 번 호출된다.
     *
     * @param project    현재 IntelliJ 프로젝트
     * @param toolWindow Tool Window 컨테이너 (탭, 제목 등 관리)
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SqlAnalyzerPanel panel = new SqlAnalyzerPanel(project);

        // ContentFactory: Tool Window 내 탭(Content) 생성 팩토리
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 2: `AnalyzeSqlAction.java` 작성**

```java
package com.example.sqlanalyzer.intellij.action;

import com.example.sqlanalyzer.intellij.toolwindow.SqlAnalyzerPanel;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * XML 에디터 우클릭 메뉴에 "Analyze SQL with AI" 항목을 추가하는 IntelliJ 액션.
 *
 * <p>활성화 조건 (update() 메서드):
 * - 현재 에디터 파일이 .xml 확장자를 가져야 함
 * - 파일 내용에 {@code <mapper} 문자열이 포함되어야 함 (MyBatis 매퍼 XML 판별)
 *
 * <p>실행 흐름 (actionPerformed() 메서드):
 * 1. "MyBatis SQL Analyzer" Tool Window를 열거나 포커스를 가져옴
 * 2. Tool Window가 열린 후 SqlAnalyzerPanel에 현재 파일 경로 전달
 * 3. SqlAnalyzerPanel이 매퍼 디렉토리와 queryId 드롭다운을 자동 세팅
 */
public class AnalyzeSqlAction extends AnAction {

    // plugin.xml의 toolWindow id와 반드시 일치해야 함
    private static final String TOOL_WINDOW_ID = "MyBatis SQL Analyzer";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || file == null) {
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);

        if (toolWindow == null) {
            return;
        }

        // Tool Window를 열고, 열림이 완료된 후 콜백으로 파일 경로를 패널에 전달
        toolWindow.show(() -> {
            Content content = toolWindow.getContentManager().getSelectedContent();

            // Java 16+ pattern matching instanceof: SqlAnalyzerPanel 타입 확인 및 캐스팅 동시 처리
            if (content != null && content.getComponent() instanceof SqlAnalyzerPanel panel) {
                panel.setMapperFile(file.getPath());
            }
        });
    }

    /**
     * 메뉴 항목의 활성화 여부를 결정한다.
     *
     * <p>getActionUpdateThread()가 BGT를 반환하므로 이 메서드는 백그라운드 스레드에서 실행된다.
     * 파일 I/O(내용 읽기)가 포함되어 있으므로 EDT에서 실행하면 UI 블로킹이 발생한다.
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

        boolean isMapperXml = file != null
                && "xml".equalsIgnoreCase(file.getExtension())
                && isMapperXmlContent(file);

        event.getPresentation().setEnabledAndVisible(isMapperXml);
    }

    /**
     * IntelliJ 2022.3+에서 update()를 실행할 스레드를 지정한다.
     *
     * <p>BGT(백그라운드 스레드) 선택 이유: update()에서 파일 내용을 읽는 I/O가 발생하므로
     * EDT에서 실행하면 UI 응답성이 떨어진다. BGT에서 실행하면 IDE 성능 경고도 피할 수 있다.
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 파일 내용에 {@code <mapper} 문자열이 포함되어 있는지 확인한다.
     *
     * <p>파일 전체를 읽지 않고 라인 단위로 스트리밍하여 {@code <mapper}를 찾는 즉시 반환한다.
     * 대부분의 매퍼 XML은 앞부분에 {@code <mapper} 태그가 있으므로 성능 영향이 최소화된다.
     *
     * @param file 검사할 VirtualFile
     * @return true이면 MyBatis 매퍼 XML로 판단
     */
    private boolean isMapperXmlContent(VirtualFile file) {
        try (InputStream input = file.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {

            return reader.lines().anyMatch(line -> line.contains("<mapper"));

        } catch (IOException e) {
            // 파일을 읽을 수 없는 경우 안전하게 false 반환 (메뉴 비활성화)
            return false;
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew compileJava 2>&1 | tail -15
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 4: 전체 intellij 모듈 테스트 실행**

```bash
cd mybatis-sql-analyzer-intellij
../gradlew test 2>&1 | tail -20
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/toolwindow/SqlAnalyzerToolWindow.java
git add mybatis-sql-analyzer-intellij/src/main/java/com/example/sqlanalyzer/intellij/action/AnalyzeSqlAction.java
git commit -m "feat(intellij): SqlAnalyzerToolWindow + AnalyzeSqlAction 구현"
```

---

## Task 11: 전체 빌드 및 플러그인 실행 검증

**배경:** `runIde` Gradle 태스크로 샌드박스 IntelliJ IDEA를 실행하여 플러그인이 정상 동작하는지 확인한다.

- [ ] **Step 1: 전체 모듈 빌드**

```bash
./gradlew :mybatis-sql-analyzer-core:build :mybatis-sql-analyzer-intellij:build 2>&1 | tail -20
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 2: 플러그인 JAR 빌드**

```bash
./gradlew :mybatis-sql-analyzer-intellij:buildPlugin 2>&1 | tail -10
```

기대 결과: `BUILD SUCCESSFUL`
생성 위치: `mybatis-sql-analyzer-intellij/build/distributions/mybatis-sql-analyzer-intellij-0.1.0.zip`

- [ ] **Step 3: 샌드박스 IDE 실행 (수동 검증)**

```bash
./gradlew :mybatis-sql-analyzer-intellij:runIde
```

실행 후 아래 항목을 수동으로 확인한다:

1. IDE 우측에 "MyBatis SQL Analyzer" Tool Window가 표시되는지 확인
2. 테스트 프로젝트를 열고 `.sql-analyzer.properties` 파일 생성:
   ```properties
   jdbc.url=jdbc:h2:mem:test
   jdbc.user=sa
   jdbc.password=
   mapper.base.dir=src/test/resources/mapper
   ```
3. `TestMapper.xml` 파일을 에디터에서 열고 우클릭 → "Analyze SQL with AI" 메뉴 노출 확인
4. 클릭 시 Tool Window에 파일 경로가 자동 세팅되는지 확인
5. queryId 드롭다운에 `findBadPerformancePayments` 등이 로드되는지 확인
6. "Analyze" 버튼 클릭 시 결과가 텍스트 영역에 표시되는지 확인
7. "Copy to Clipboard" 버튼 클릭 후 텍스트 에디터에 붙여넣기 확인

- [ ] **Step 4: docs에 결과 요약 문서 작성**

```bash
mkdir -p docs/superpowers/results
```

`docs/superpowers/results/2026-04-22-mybatis-sql-analyzer-intellij-result.md` 파일을 생성하고 아래 내용을 채운다:

```markdown
# MyBatis SQL Analyzer IntelliJ 플러그인 — 구현 결과

**완료일**: (작성 시점 날짜)

## 완료된 작업
- [ ] Task 1: SqlExtractor.findMapperFiles() 추가
- [ ] Task 2: static field 제거
- [ ] Task 3: getExplainInfo() 버그 수정
- [ ] Task 4: mapperPath String→Path 변경
- [ ] Task 5: IntelliJ 모듈 골격
- [ ] Task 6: SqlAnalyzerConfig
- [ ] Task 7: SqlAnalyzerService
- [ ] Task 8: XmlQueryIdReader
- [ ] Task 9: SqlAnalyzerPanel
- [ ] Task 10: SqlAnalyzerToolWindow + AnalyzeSqlAction
- [ ] Task 11: 전체 빌드 검증

## 수동 검증 결과
(runIde 실행 후 각 항목 체크)

## 잔여 이슈 / 다음 단계
(작업 중 발견된 이슈나 향후 작업 기록)
```

- [ ] **Step 5: 최종 커밋**

```bash
git add docs/superpowers/results/
git commit -m "docs: IntelliJ 플러그인 구현 결과 문서 추가"
```

---

## 보충: `.sql-analyzer.properties` gitignore 추가

DB 비밀번호가 평문으로 포함되므로 `.gitignore`에 추가한다.

- [ ] `.gitignore` 파일에 아래 줄 추가 후 커밋

```
# SQL Analyzer 설정 파일 (DB 비밀번호 포함)
.sql-analyzer.properties
```
