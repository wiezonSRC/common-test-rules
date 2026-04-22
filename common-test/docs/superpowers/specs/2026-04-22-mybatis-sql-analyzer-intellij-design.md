# MyBatis SQL Analyzer — IntelliJ 플러그인 설계 문서

**작성일**: 2026-04-22  
**브랜치**: feature/claude-mybatis  
**작성자**: wypark

---

## 1. 목표 및 범위

### 목표
MyBatis 매퍼 XML 파일에서 특정 queryId를 선택하여, 실제 DB의 EXPLAIN 결과와 스키마 메타데이터를 기반으로 AI 쿼리 튜닝 프롬프트를 자동 생성하는 IntelliJ 플러그인을 개발한다.

### 범위 (In-Scope)
- `mybatis-sql-analyzer-core` 모듈 버그 수정 및 기능 개선
- `mybatis-sql-analyzer-intellij` 신규 모듈 추가 (IntelliJ Platform Plugin)
- XML 에디터 우클릭 → Tool Window 연동 흐름
- `.sql-analyzer.properties` 파일 기반 DB 연결 설정
- Tool Window 내 결과 표시 + 클립보드 복사 기능

### 범위 (Out-of-Scope)
- `mybatis-sql-analyzer-plugin` (Gradle 플러그인) — 주석 유지, 이번 작업 제외
- AI API 자동 호출 — 향후 기능, 현재는 프롬프트 생성까지만
- IntelliJ Marketplace 배포

---

## 2. 전체 아키텍처

### 모듈 구조

```
common-test/
├── mybatis-sql-analyzer-core/          # 기존 (버그 수정 + 기능 추가)
│   ├── SqlExtractor.java               # findMapperFiles() 추가, static field 제거
│   ├── JdbcAnalyzer.java               # getExplainInfo() 버그 수정
│   └── PromptGenerator.java            # 다중 파일 선택 지원
├── mybatis-sql-analyzer-plugin/        # 기존 (주석 유지, 변경 없음)
└── mybatis-sql-analyzer-intellij/      # 신규
    ├── build.gradle
    ├── src/main/resources/META-INF/plugin.xml
    └── src/main/java/com/example/sqlanalyzer/intellij/
        ├── action/AnalyzeSqlAction.java
        ├── toolwindow/
        │   ├── SqlAnalyzerToolWindow.java
        │   └── SqlAnalyzerPanel.java
        ├── service/SqlAnalyzerService.java
        └── config/SqlAnalyzerConfig.java
```

### 데이터 흐름

```
[XML 에디터 우클릭]
        │
        ▼
AnalyzeSqlAction
  - 현재 파일 경로 추출
  - Tool Window 열기
  - Mapper Dir 자동 세팅
        │
        ▼
SqlAnalyzerPanel (Tool Window UI)
  - queryId 드롭다운 로드 (해당 XML의 DML id 목록)
  - [Analyze] 버튼 클릭
        │
        ▼
SqlAnalyzerService (Background Task)
  - SqlAnalyzerConfig에서 JDBC 정보 로드
  - SqlExtractor.findMapperFiles() → 매퍼 파일 목록
  - SqlExtractor.getQueryIdDetail() → 동적 쿼리 Node
  - SqlExtractor.buildFakeSql() → 실행 가능한 SQL
  - JdbcAnalyzer.getExplainInfo() → EXPLAIN 결과
  - JdbcAnalyzer.extractTableMethod() → 사용 테이블
  - JdbcAnalyzer.getMetaDataInfo() → 스키마/인덱스
  - PromptGenerator.generatePrompt() → 최종 프롬프트
        │
        ▼
SqlAnalyzerPanel
  - 결과 텍스트 영역 갱신
  - [Copy to Clipboard] 버튼 활성화
```

---

## 3. Core 모듈 개선 명세

### 3-1. `JdbcAnalyzer.getExplainInfo()` 버그 수정

**문제**: `for (int i = 1; rs.next(); i++) { rs.getString(i) }` — i가 행 번호인데 컬럼 인덱스로 잘못 사용됨. EXPLAIN 결과의 첫 번째 행 이후 컬럼 누락.

**수정 방향**:
```java
ResultSetMetaData meta = rs.getMetaData();
int columnCount = meta.getColumnCount();
// 헤더 출력
while (rs.next()) {
    for (int col = 1; col <= columnCount; col++) {
        result.append(meta.getColumnName(col))
              .append(": ").append(rs.getString(col)).append(" | ");
    }
    result.append("\n");
}
```

### 3-2. `SqlExtractor.sqlSnippetRegistry` static field 제거

**문제**: `static Map<String, String> sqlSnippetRegistry` 선언으로 테스트 간 상태 오염 위험. `SqlExtractorTest.set()`에서 직접 할당하는 방식도 위험.

**수정 방향**: static field 완전 제거. `buildFakeSql()` 호출 시 항상 `getSqlSnippetRegistry()` 반환값을 명시적으로 전달하는 방식으로 통일.

### 3-3. `SqlExtractor.findMapperFiles()` 프로덕트 코드 승격

**문제**: queryId로 매퍼 파일을 찾는 로직이 `SqlExtractorTest`에만 존재. IntelliJ 플러그인에서 재사용 불가.

**추가 메서드 시그니처**:
```java
public static List<Path> findMapperFiles(Path mapperBaseDir, String queryId)
    throws IOException, ParserConfigurationException, SAXException
```

### 3-4. `PromptGenerator.generatePrompt()` 시그니처 변경

다중 매퍼 파일 대응을 위해 `mapperPath` 파라미터를 `String`에서 `Path`로 변경하고, 선택된 파일을 명확히 전달받는 구조로 정리.

---

## 4. IntelliJ 플러그인 모듈 명세

### 4-1. `build.gradle`

```groovy
plugins {
    id 'java'
    id 'org.jetbrains.intellij.platform' version '2.x.x'
}

dependencies {
    implementation project(':mybatis-sql-analyzer-core')
    intellijPlatform {
        intellijIdeaCommunity('2024.1')
    }
}
```

### 4-2. `plugin.xml` 등록 항목

| 항목 | 내용 |
|------|------|
| id | `com.example.mybatis-sql-analyzer-intellij` |
| name | MyBatis SQL Analyzer |
| version | 0.1.0 |
| Action | `AnalyzeSqlAction` — XML 파일 에디터 우클릭 메뉴 등록 |
| ToolWindow | `SqlAnalyzerToolWindow` — 우측 사이드 패널 |

### 4-3. `SqlAnalyzerConfig`

`.sql-analyzer.properties` 파일을 프로젝트 루트에서 읽어 JDBC 연결 정보 및 매퍼 경로를 반환.

```properties
# .sql-analyzer.properties (프로젝트 루트)
jdbc.url=jdbc:mariadb://localhost:3306/mydb
jdbc.user=root
jdbc.password=secret
mapper.base.dir=src/main/resources/mapper
```

필수 키 누락 시 Tool Window에 오류 메시지 표시.

### 4-4. `AnalyzeSqlAction`

- 활성화 조건: 현재 에디터 파일이 `.xml` 확장자이고 `<mapper` 태그를 포함할 때만 메뉴 노출
- 실행 시: Tool Window를 열고 현재 파일 경로를 `SqlAnalyzerPanel`에 전달

### 4-5. `SqlAnalyzerPanel` UI 구성

```
┌─ MyBatis SQL Analyzer ──────────────────────────────────┐
│  Mapper Dir : [src/main/resources/mapper        ] [...]  │
│  Query ID   : [findBadPerformancePayments       ] [▼]   │
│                                    [Analyze]             │
├──────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────┐  │
│  │  (분석 결과 / AI 프롬프트 텍스트)                     │  │
│  │                                                    │  │
│  └────────────────────────────────────────────────────┘  │
│                              [Copy to Clipboard]         │
└──────────────────────────────────────────────────────────┘
```

- queryId 드롭다운: 선택된 XML 파일의 DML 태그(`select`, `insert`, `update`, `delete`) id 목록 자동 로드
- Analyze 버튼: IntelliJ `ProgressManager` 백그라운드 태스크 실행
- 결과 영역: 스크롤 가능한 읽기 전용 텍스트 에리어
- Copy to Clipboard: 클립보드 복사 후 버튼 텍스트 일시적으로 "Copied!" 표시

### 4-6. `SqlAnalyzerService`

순수 Java로 구현 (IntelliJ API 의존 최소화). core 모듈 클래스들을 순서대로 호출하는 오케스트레이터 역할.

```java
public String analyze(Project project, String mapperFilePath, String queryId) throws Exception
```

---

## 5. 테스트 전략

### Core 모듈 (TDD — `/go` 스킬 활용)

| 테스트 클래스 | 검증 항목 |
|--------------|----------|
| `SqlExtractorTest` | `findMapperFiles()` 신규 메서드, static field 제거 후 회귀 |
| `JdbcAnalyzerTest` | H2로 `getExplainInfo()` 컬럼 파싱 정확성 검증 |
| `PromptGeneratorTest` | 전체 흐름 통합 검증 (기존 테스트 유지) |

### IntelliJ 플러그인 모듈

| 계층 | 테스트 방식 |
|------|------------|
| `SqlAnalyzerConfig` | 순수 JUnit (IntelliJ 의존 없음) |
| `SqlAnalyzerService` | JUnit + H2 (core 모듈과 동일) |
| `AnalyzeSqlAction` | IntelliJ `LightJavaCodeInsightFixtureTestCase` |

---

## 6. 파일 변경 요약

| 파일 | 변경 유형 |
|------|----------|
| `SqlExtractor.java` | 수정 — static field 제거, `findMapperFiles()` 추가 |
| `JdbcAnalyzer.java` | 수정 — `getExplainInfo()` 버그 수정 |
| `PromptGenerator.java` | 수정 — `mapperPath` 타입 변경 |
| `SqlExtractorTest.java` | 수정 — `findMapperFiles()` 테스트 추가, static field 직접 할당 제거 |
| `JdbcAnalyzerTest.java` | 수정 — EXPLAIN 파싱 검증 테스트 추가 |
| `PromptGeneratorTest.java` | 수정 — `mapperPath` 타입 변경(String→Path) 반영 |
| `mybatis-sql-analyzer-intellij/` | 신규 — 전체 모듈 |
| `settings.gradle` | 수정 — `include 'mybatis-sql-analyzer-intellij'` 추가 |
| `.sql-analyzer.properties` | 신규 — 프로젝트 루트 (gitignore 추가 권장) |
