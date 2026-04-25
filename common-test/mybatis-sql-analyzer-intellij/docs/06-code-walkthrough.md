# 06. 전체 코드 흐름 — 클래스별 상세 설명

앞의 1~5 문서를 읽었다면 이제 실제 소스 코드를 흐름 순서대로 읽을 수 있다.

---

## 전체 흐름 요약

```
[경로 A] 툴 윈도우에서 직접 분석
────────────────────────────────
사용자가 IDE 우측 패널 열기
→ SqlAnalyzerToolWindow.createToolWindowContent()
→ SqlAnalyzerPanel 생성 (UI 초기화)
→ 매퍼 디렉토리 직접 입력 or 찾아보기 버튼
→ queryId 직접 입력
→ [Analyze] 클릭
→ SqlAnalyzerPanel.runAnalysis() → BackgroundTask
→ SqlAnalyzerService.analyze()
→ PromptGenerator.generatePrompt()
→ 결과를 resultArea에 표시

[경로 B] XML 에디터 우클릭으로 시작
──────────────────────────────────────
매퍼 XML 파일 에디터에서 우클릭
→ AnalyzeSqlAction.update() → XML 파일 여부 확인
→ 메뉴에 "Analyze SQL with AI" 표시
→ 클릭 → AnalyzeSqlAction.actionPerformed()
→ 툴 윈도우 열기 + SqlAnalyzerPanel.setMapperFile(경로) 호출
→ 이후 경로 A와 동일
```

---

## 클래스별 상세 설명

### 1. `plugin.xml` — 플러그인 등록부

```xml
<toolWindow id="MyBatis SQL Analyzer" anchor="right"
            factoryClass="...SqlAnalyzerToolWindow"/>
```
→ IDE가 이 설정을 읽어 우측 사이드바에 탭을 추가한다. `id`는 `AnalyzeSqlAction`에서도 동일하게 사용되므로 반드시 일치해야 한다.

```xml
<action class="...AnalyzeSqlAction" text="Analyze SQL with AI">
    <add-to-group group-id="EditorPopupMenu" anchor="last"/>
</action>
```
→ 에디터 우클릭 메뉴(`EditorPopupMenu`)의 맨 끝에 이 액션을 추가한다.

---

### 2. `SqlAnalyzerToolWindow` — Tool Window 진입점

```java
public class SqlAnalyzerToolWindow implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        SqlAnalyzerPanel panel = new SqlAnalyzerPanel(project);  // ① UI 생성
        Content content = ContentFactory.getInstance()
                                        .createContent(panel, "", false);  // ② 탭 생성
        toolWindow.getContentManager().addContent(content);  // ③ Tool Window에 등록
    }
}
```

역할이 단순하다: UI 패널(`SqlAnalyzerPanel`)을 만들어서 Tool Window의 탭에 꽂아 넣는 것.

---

### 3. `AnalyzeSqlAction` — 우클릭 메뉴 액션

#### `update()` — 메뉴 표시 여부 결정

```java
@Override
public void update(@NotNull AnActionEvent event) {
    VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

    boolean isMapperXml = file != null
            && "xml".equalsIgnoreCase(file.getExtension())  // ① .xml 파일인가?
            && isMapperXmlContent(file);                     // ② 파일 안에 <mapper 태그가 있나?

    event.getPresentation().setEnabledAndVisible(isMapperXml);
}
```

`isMapperXmlContent()`는 파일 전체를 읽지 않고 `<mapper` 문자열을 찾는 즉시 반환한다.

```java
private boolean isMapperXmlContent(VirtualFile file) {
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
        return reader.lines().anyMatch(line -> line.contains("<mapper"));
    } catch (IOException e) {
        return false;  // 읽기 실패 시 안전하게 메뉴 비활성화
    }
}
```

#### `actionPerformed()` — 클릭 시 실행

```java
@Override
public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

    ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                                             .getToolWindow("MyBatis SQL Analyzer");

    // Tool Window를 열고, 열린 후 콜백 실행
    toolWindow.show(() -> {
        Content content = toolWindow.getContentManager().getSelectedContent();
        if (content.getComponent() instanceof SqlAnalyzerPanel panel) {
            panel.setMapperFile(file.getPath());  // 파일 경로를 패널에 전달
        }
    });
}
```

`instanceof SqlAnalyzerPanel panel`: Java 16+의 **Pattern Matching instanceof**  
→ 타입 확인과 캐스팅을 한 줄에 처리한다.

---

### 4. `SqlAnalyzerPanel` — 메인 UI

#### `setMapperFile()` — 우클릭에서 파일 경로를 받을 때

```java
public void setMapperFile(String mapperFilePath) {
    Path mapperPath = Path.of(mapperFilePath);
    mapperDirField.setText(mapperPath.getParent().toString()); // 부모 디렉토리를 입력창에 세팅
    loadQueryIds(mapperFilePath);  // 해당 XML의 DML id를 드롭다운에 로드
}

private void loadQueryIds(String mapperFilePath) {
    queryIdCombo.removeAllItems();
    List<String> ids = XmlQueryIdReader.readIds(Path.of(mapperFilePath));
    ids.forEach(queryIdCombo::addItem);
}
```

#### `runAnalysis()` — [Analyze] 버튼 클릭 시

```java
private void runAnalysis() {
    String queryId = (String) queryIdCombo.getSelectedItem();
    String mapperDir = mapperDirField.getText().trim();

    // ① UI 초기화
    analyzeButton.setEnabled(false);
    resultArea.setText("분석 중...");

    // ② 백그라운드 태스크 실행 (EDT 블로킹 방지)
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "분석 중...") {
        private String result;
        private Exception error;

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            // [BGT] DB 연결, 파일 탐색 등 무거운 작업
            List<Path> matchedFiles = service.findMatchingFiles(Path.of(mapperDir), queryId);

            // 매칭 파일이 여러 개면 사용자 선택 다이얼로그 (EDT로 전환 필요)
            Path selectedFile = matchedFiles.size() == 1
                    ? matchedFiles.get(0)
                    : selectFileFromDialog(matchedFiles);

            result = service.analyze(project.getBasePath(), selectedFile, queryId);
        }

        @Override
        public void onSuccess() {
            // [EDT] UI 갱신
            analyzeButton.setEnabled(true);
            resultArea.setText(error != null ? "오류: " + error.getMessage() : result);
            copyButton.setEnabled(error == null);
        }
    });
}
```

---

### 5. `XmlQueryIdReader` — 매퍼 XML에서 queryId 목록 추출

**패키지 프라이빗 클래스**: `SqlAnalyzerPanel`에서만 사용되므로 접근 제한자를 `class`(default)로 설정했다.

```java
static List<String> readIds(Path mapperFilePath) throws ... {
    // DOM 파싱 (DTD 검증 비활성화)
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

    Document doc = factory.newDocumentBuilder().parse(mapperFilePath.toFile());

    List<String> ids = new ArrayList<>();
    for (String tag : new String[]{"select", "insert", "update", "delete"}) {
        NodeList nodeList = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node idAttr = nodeList.item(i).getAttributes().getNamedItem("id");
            if (idAttr != null) ids.add(idAttr.getNodeValue());
        }
    }
    return ids;
}
```

---

### 6. `SqlAnalyzerConfig` — 설정 파일 로더

프로젝트 루트의 `.sql-analyzer.properties` 파일에서 DB 연결 정보를 읽는다.

```java
public static SqlAnalyzerConfig load(Path configPath) throws IOException {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(configPath)) {
        properties.load(input);
    }
    // 4개 키 모두 필수 (없으면 IllegalStateException)
    return new SqlAnalyzerConfig(
        requireKey(properties, "jdbc.url"),
        requireKey(properties, "jdbc.user"),
        requireKey(properties, "jdbc.password"),
        requireKey(properties, "mapper.base.dir")
    );
}
```

**생성자 private + static factory 패턴**:  
`new SqlAnalyzerConfig(...)` 직접 호출 불가 → `SqlAnalyzerConfig.load(path)`로만 생성 가능.  
→ 불완전한 상태의 객체가 만들어지는 것을 방지한다.

---

### 7. `SqlAnalyzerService` — core 모듈 오케스트레이터

```java
public String analyze(String projectBasePath, Path selectedMapperFile, String queryId)
        throws Exception {

    // ① 설정 로드
    Path configPath = Path.of(projectBasePath, ".sql-analyzer.properties");
    SqlAnalyzerConfig config = SqlAnalyzerConfig.load(configPath);

    // ② 매퍼 디렉토리 절대경로 변환
    Path mapperDir = Path.of(projectBasePath)
                         .resolve(config.getMapperBaseDir())
                         .normalize();  // ".." 등 상대 경로 제거

    // ③ JDBC 연결 후 프롬프트 생성 (try-with-resources로 자동 해제)
    try (Connection connection = DriverManager.getConnection(
            config.getJdbcUrl(), config.getJdbcUser(), config.getJdbcPassword())) {

        return PromptGenerator.generatePrompt(connection, queryId, selectedMapperFile, mapperDir)
                              .toString();
    }
}
```

`SqlAnalyzerService`는 **IntelliJ API에 의존하지 않는다**.  
→ 순수 Java이므로 H2 DB로 단위 테스트가 가능하다 (`SqlAnalyzerServiceTest`).

---

## core 모듈의 역할 (간략)

`mybatis-sql-analyzer-core`가 실제 분석 로직을 담당한다.

| 클래스 | 역할 |
|---|---|
| `SqlExtractor` | 매퍼 XML 파싱, MyBatis 동적 태그 → fakeSql 변환, `<sql>` 태그 캐싱 |
| `JdbcAnalyzer` | EXPLAIN 실행, 테이블 메타데이터(컬럼/인덱스) 수집 |
| `PromptGenerator` | 위 두 클래스의 결과를 조합해 AI 프롬프트 문자열 생성 |

**fakeSql이란?**  
MyBatis `<if>`, `<where>`, `<foreach>` 같은 동적 태그를 제거하고, `#{id}` 같은 파라미터를 `?`로 치환한 SQL.  
→ 이 형태여야 `EXPLAIN`으로 실행 계획을 조회할 수 있다.

---

## 사용 전 필수 설정: `.sql-analyzer.properties`

플러그인이 동작하려면 프로젝트 루트에 이 파일이 있어야 한다.

```properties
jdbc.url=jdbc:mariadb://localhost:3306/mydb
jdbc.user=root
jdbc.password=secret
mapper.base.dir=src/main/resources/mapper
```

> **보안 주의**: 이 파일은 비밀번호가 평문으로 담겨 있으므로 `.gitignore`에 반드시 추가한다.
