# mybatis-sql-analyzer 고도화 및 버그 수정

> **작업일:** 2026-05-08
> **대상 모듈:** `mybatis-sql-analyzer-core`, `mybatis-sql-analyzer-intellij`

---

## 목차

1. [고도화 기능](#1-고도화-기능)
   - 1-1. DB 설정 UI 버튼 (DbSettingsDialog)
   - 1-2. Mapper 파일 재귀 탐색 및 계층 구조 UI
   - 1-3. Mapper File 실시간 검색 필터
   - 1-4. Query ID 실시간 검색 필터
   - 1-5. AI 프롬프트 페르소나 문구 개선
2. [버그 수정](#2-버그-수정)
   - 2-1. MariaDB EXPLAIN `LIMIT null, null` 오류
   - 2-2. 테이블 메타데이터 중복 출력
   - 2-3. Mapper File 필터링 `IllegalStateException`
   - 2-4. Mapper File / Query ID Enter 자동완성 미작동 (1건)
   - 2-5. Mapper File / Query ID Enter 자동완성 미작동 (다중 결과)
3. [변경 파일 목록](#3-변경-파일-목록)

---

## 1. 고도화 기능

### 1-1. DB 설정 UI 버튼 (DbSettingsDialog)

#### 배경

기존에는 `.sql-analyzer.properties` 파일에 JDBC 접속 정보를 직접 작성해야 했다.
프로젝트마다 파일을 생성해야 하고, 비밀번호가 파일에 평문으로 남는 문제가 있었다.

#### 변경 내용

| 항목 | 이전 | 이후 |
|------|------|------|
| 설정 방식 | `.sql-analyzer.properties` 파일 | Tool Window 내 **[DB Settings]** 버튼 → 다이얼로그 입력 |
| 저장 위치 | 프로젝트 로컬 파일 | IntelliJ `PropertiesComponent`(프로젝트 레벨, IDE 재시작 유지) |
| 설정 키 | `sql.analyzer.jdbc.url` 등 | `sql-analyzer.jdbc.url` / `sql-analyzer.jdbc.user` / `sql-analyzer.jdbc.password` |

#### 추가/수정 파일

**`DbSettingsDialog.java`** (신규)
- IntelliJ `DialogWrapper` 기반 모달 다이얼로그
- JDBC URL / User / Password 입력 폼
- `PropertiesComponent.getInstance(project)`에 프로젝트 레벨 저장
- `static loadConfig(Project)` — 저장된 값을 `SqlAnalyzerConfig` 값 객체로 반환

```java
public static SqlAnalyzerConfig loadConfig(Project project) {
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    return new SqlAnalyzerConfig(
        props.getValue(KEY_JDBC_URL, ""),
        props.getValue(KEY_JDBC_USER, ""),
        props.getValue(KEY_JDBC_PASSWORD, "")
    );
}
```

**`SqlAnalyzerConfig.java`** (수정)
- `load(Path)` 파일 읽기 팩토리 메서드 제거
- 순수 값 객체(3 필드: jdbcUrl, jdbcUser, jdbcPassword) + `isConfigured()` 메서드만 보유

**`SqlAnalyzerService.java`** (수정)
- `analyze()` 시그니처에서 `.sql-analyzer.properties` 경로 제거
- `SqlAnalyzerConfig` 값 객체를 직접 파라미터로 수신

---

### 1-2. Mapper 파일 재귀 탐색 및 계층 구조 UI

#### 배경

기존 UI는 단일 flat 목록으로 XML 파일을 표시했으며, 1-depth 탐색만 지원했다.
`payment/approval/ApprovalMapper.xml` 처럼 중첩된 디렉토리 구조는 인식하지 못했다.

#### 변경 내용

**UI 흐름 (이전)**
```
[매퍼 파일 직접 선택] → [Query ID 선택] → [Analyze]
```

**UI 흐름 (이후)**
```
[DB Settings]  ← 신규
[Mapper Dir: 경로입력 ] [...]  ← 베이스 디렉토리 선택
[Mapper File: 상대경로 드롭다운]  ← 재귀 탐색 결과 (any-depth)
[Query ID: 드롭다운]
[Analyze]
```

**`SqlAnalyzerService.listXmlFiles()`** (수정)

기존 1-depth 탐색 → `Files.walk()` 재귀 탐색으로 교체.
베이스 디렉토리 기준 상대 경로(`payment/approval/ApprovalMapper.xml`)로 표시한다.

```java
public List<String> listXmlFiles(Path mapperBaseDir) throws IOException {
    try (Stream<Path> stream = Files.walk(mapperBaseDir)) {
        return stream.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".xml"))
                     .map(p -> mapperBaseDir.relativize(p).toString())
                     .sorted()
                     .collect(Collectors.toList());
    }
}
```

**`SqlAnalyzerPanel.browseMapperDir()`** (수정)

파일 선택 다이얼로그 초기 위치 결정 우선순위:
1. `mapperDirField`에 이미 입력된 경로가 유효한 경우 → 해당 경로
2. 현재 IntelliJ 프로젝트 루트 (`project.getBasePath()`)
3. 위 두 경우 모두 해당 없을 때 → IntelliJ 기본 동작

---

### 1-3. Mapper File 실시간 검색 필터

#### 배경

Mapper 파일이 많은 경우 드롭다운에서 원하는 파일을 찾기 어렵다.
파일명 타이핑으로 실시간 필터링되면 접근성이 크게 향상된다.

#### 동작 방식

| 사용자 행동 | 결과 |
|------------|------|
| Mapper Dir 선택 후 파일 로드 | 전체 목록 `allMapperFiles`에 캐시 + 드롭다운 표시 |
| 콤보박스에 키워드 타이핑 (`Approv`) | `contains` 방식 필터링 (대소문자 무시), 매칭 목록만 팝업 표시 |
| 드롭다운에서 항목 클릭 또는 Enter | Query ID 콤보 자동 갱신 (`reloadQueryIds()`) |
| 검색어 전체 삭제 | 전체 파일 목록 복원 |

#### 핵심 구현

**추가 필드**

```java
/** 검색 필터링 원본 캐시 */
private List<String> allMapperFiles = new ArrayList<>();

/**
 * removeAllItems()/addItem() 이 유발하는 ActionEvent 의 cascade 를 막는 가드 플래그.
 * true 인 동안 ActionListener 가 reloadQueryIds() 를 호출하지 않는다.
 */
private boolean isUpdatingFilter = false;
```

**`filterMapperFiles()` 핵심 로직**

```java
private void filterMapperFiles() {
    if (isUpdatingFilter) return;

    JTextField editor = (JTextField) mapperFileCombo.getEditor().getEditorComponent();
    String keyword = editor.getText();   // removeAllItems() 전에 미리 캡처
    String lower   = keyword.toLowerCase();

    isUpdatingFilter = true;
    try {
        mapperFileCombo.removeAllItems();

        List<String> filtered = lower.isBlank()
                ? allMapperFiles
                : allMapperFiles.stream()
                                .filter(f -> f.toLowerCase().contains(lower))
                                .collect(Collectors.toList());

        filtered.forEach(mapperFileCombo::addItem);

        // addItem() 이 에디터 텍스트를 첫 번째 항목으로 덮어쓰므로 키워드로 복원
        editor.setText(keyword);
        editor.setCaretPosition(keyword.length());

        if (!lower.isBlank() && !filtered.isEmpty()) {
            mapperFileCombo.showPopup();
        }
    } finally {
        isUpdatingFilter = false;
    }
}
```

---

## 2. 버그 수정

### 2-1. MariaDB EXPLAIN `LIMIT null, null` 오류

**파일:** `mybatis-sql-analyzer-core/.../JdbcAnalyzer.java`

#### 증상

```
(conn=55080490) You have an error in your SQL syntax;
... near 'null, null' at line 53
```

EXPLAIN 실행 시 `?` 파라미터에 `setNull(i, Types.NULL)`을 바인딩하면 MariaDB가 `LIMIT null, null`로 해석하여 구문 오류를 발생시킨다.

#### 원인

`PreparedStatement.setNull(index, Types.NULL)` — MariaDB Connector/J는 `LIMIT` 절의 파라미터에 NULL을 허용하지 않는다.

#### 수정

```java
// Before: MariaDB LIMIT 파라미터에 NULL 바인딩 → 구문 오류
pstmt.setNull(i, java.sql.Types.NULL);

// After: 더미 정수 1 바인딩 — 실행 계획(인덱스·조인 방식) 조회가 목적이므로 실제 값 불필요
pstmt.setObject(i, 1);
```

EXPLAIN은 인덱스·조인 방식 파악이 목적이므로 파라미터 실제 값은 중요하지 않다. 정수 1은 MariaDB/MySQL/PostgreSQL/H2 모든 DB에서 안전하게 동작한다.

---

### 2-2. 테이블 메타데이터 중복 출력

**파일:** `mybatis-sql-analyzer-core/.../JdbcAnalyzer.java`

#### 증상

동일 테이블 `TBTR_CRCT_REQ`의 컬럼·인덱스 정보가 수십 번 반복 출력되고, 컬럼 크기가 버전마다 달랐다.

#### 원인

`DatabaseMetaData.getColumns()` / `getIndexInfo()`의 첫 번째 파라미터(`catalog`)에 `null`을 전달하면 **JDBC 스펙상 서버의 전체 데이터베이스(catalog)**를 대상으로 조회한다.

MariaDB 서버에 동일 이름(`TBTR_CRCT_REQ`)의 테이블이 여러 DB(개발/스테이징/운영/버전별 스키마)에 존재하여 모두 중복 출력된 것이다.

```
catalog = null
  → DB_DEV.TBTR_CRCT_REQ  컬럼 목록 출력
  → DB_STG.TBTR_CRCT_REQ  컬럼 목록 출력   ← 중복!
  → DB_PRD.TBTR_CRCT_REQ  컬럼 목록 출력   ← 중복!
  ...
```

#### 수정

```java
// Before: 전체 catalog 대상 조회 → 중복 출력
metaData.getColumns(null, null, targetTable, null)
metaData.getIndexInfo(null, null, targetTable, false, false)

// After: 현재 접속 DB(catalog)로 범위 한정
String catalog = metaData.getConnection().getCatalog();
metaData.getColumns(catalog, null, targetTable, null)
metaData.getIndexInfo(catalog, null, targetTable, false, false)
```

---

### 2-3. Mapper File 필터링 `IllegalStateException`

**파일:** `mybatis-sql-analyzer-intellij/.../toolwindow/SqlAnalyzerPanel.java`

#### 증상

```
java.lang.IllegalStateException: Attempt to mutate in notification
    at javax.swing.text.AbstractDocument.writeLock(AbstractDocument.java:1372)
    ...
    at SqlAnalyzerPanel.filterMapperFiles(SqlAnalyzerPanel.java:349)
    at SqlAnalyzerPanel$2.removeUpdate(SqlAnalyzerPanel.java:194)
```

Mapper File 드롭다운에서 항목을 클릭하면 위 예외가 발생했다.

#### 원인 분석

`DocumentListener`는 Swing 문서의 **write-lock을 보유한 채로** 동기 호출된다. 그 안에서 콤보박스 모델을 변경(`addItem()` → `setText()`)하면 동일 write-lock 재획득을 시도하여 예외가 발생한다.

```
드롭다운 항목 클릭
  → JComboBox.setSelectedItem()
    → BasicComboBoxEditor.setItem()
      → editor.setText()          ← 문서 write-lock 획득
        → DocumentListener 동기 호출  ← write-lock 보유 중
          → filterMapperFiles()
            → addItem() → setText() ← write-lock 재획득 시도 → 💥 IllegalStateException
```

#### 수정: `DocumentListener` → `KeyAdapter` 교체

`KeyAdapter(keyReleased)`는 **실제 키보드 이벤트에만 반응**하며, 드롭다운 선택 시 내부적으로 호출되는 `setText()`에는 반응하지 않는다.

```java
// Before: DocumentListener — 문서 알림 컨텍스트에서 동기 호출 → 재진입 충돌
mapperFileEditor.getDocument().addDocumentListener(new DocumentListener() {
    @Override public void insertUpdate(DocumentEvent e)  { filterMapperFiles(); }
    @Override public void removeUpdate(DocumentEvent e)  { filterMapperFiles(); }
    @Override public void changedUpdate(DocumentEvent e) { }
});

// After: KeyAdapter — 실제 키 입력에만 반응 → 재진입 문제 없음
mapperFileEditor.addKeyListener(new KeyAdapter() {
    @Override
    public void keyReleased(KeyEvent e) {
        int keyCode = e.getKeyCode();
        // 드롭다운 탐색·선택·닫기 키는 ActionListener가 처리
        if (keyCode == KeyEvent.VK_ENTER  ||
            keyCode == KeyEvent.VK_ESCAPE ||
            keyCode == KeyEvent.VK_UP     ||
            keyCode == KeyEvent.VK_DOWN) {
            return;
        }
        filterMapperFiles();
    }
});
```

| 구분 | `DocumentListener` | `KeyAdapter` |
|------|--------------------|--------------|
| 발동 조건 | 문서 내용 변경 시 (키 입력 + 프로그래밍 `setText()` 모두) | 실제 키보드 이벤트 시에만 |
| 드롭다운 선택 시 | 🔥 발동 → 재진입 충돌 | ✅ 발동 안 함 |
| 호출 컨텍스트 | 문서 write-lock 보유 중 | 일반 EDT 이벤트 (락 없음) |

---

### 1-4. Query ID 실시간 검색 필터

#### 배경

Mapper File에 QueryId가 많은 경우 드롭다운에서 원하는 항목을 찾기 어렵다.
Mapper File과 동일하게 타이핑으로 실시간 필터링되도록 추가했다.

#### 동작 방식

| 사용자 행동 | 결과 |
|------------|------|
| Mapper File 선택 후 QueryId 로드 | 전체 목록 `allQueryIds`에 캐시 + 드롭다운 표시 |
| QueryId 콤보에 키워드 타이핑 | `contains` 방식 필터링 (대소문자 무시), 매칭 항목만 팝업 표시 |
| 결과 1건 상태에서 Enter | 해당 항목 자동 선택 |
| 검색어 전체 삭제 | 전체 QueryId 목록 복원 |
| 드롭다운에 없는 값 직접 입력 후 Analyze | 입력값 그대로 사용 (기존 editable 동작 유지) |

#### 핵심 구현

Mapper File 필터 구조(`allMapperFiles` / `isUpdatingFilter` / `filterMapperFiles()`)와 동일한 패턴을 **독립적으로** 적용한다.

```java
/** queryIdCombo 검색 필터링을 위한 전체 Query ID 목록 캐시 */
private List<String> allQueryIds = new ArrayList<>();

/** Query ID 필터링 중 이벤트 cascade를 막는 가드 플래그 */
private boolean isUpdatingQueryFilter = false;
```

```java
private void filterQueryIds() {
    if (isUpdatingQueryFilter) return;

    JTextField editor  = (JTextField) queryIdCombo.getEditor().getEditorComponent();
    String     keyword = editor.getText();
    String     lower   = keyword.toLowerCase();

    isUpdatingQueryFilter = true;
    try {
        queryIdCombo.removeAllItems();

        List<String> filtered = lower.isBlank()
                ? allQueryIds
                : allQueryIds.stream()
                             .filter(id -> id.toLowerCase().contains(lower))
                             .collect(Collectors.toList());

        filtered.forEach(queryIdCombo::addItem);

        // addItem()이 에디터를 첫 번째 항목으로 덮어쓰므로 키워드 복원
        editor.setText(keyword);
        editor.setCaretPosition(keyword.length());

        if (!lower.isBlank() && !filtered.isEmpty()) {
            queryIdCombo.showPopup();
        }
    } finally {
        isUpdatingQueryFilter = false;
    }
}
```

> Mapper File과 플래그(`isUpdatingFilter` / `isUpdatingQueryFilter`)를 분리한 이유:
> 두 콤보가 동시에 이벤트를 처리할 경우 공유 플래그를 사용하면 한쪽이 다른 쪽의 이벤트를 잘못 차단할 수 있다.

---

## 2. 버그 수정 (추가)

### 2-4. Mapper File / Query ID Enter 자동완성 미작동

**파일:** `mybatis-sql-analyzer-intellij/.../toolwindow/SqlAnalyzerPanel.java`

#### 증상

파일명(또는 QueryId)을 타이핑하여 필터링 결과가 1건으로 좁혀진 뒤 Enter를 눌러도 해당 항목이 선택되지 않았다. 이후 단계(QueryId 로드 또는 분석)가 진행되지 않았다.

#### 원인 분석

`KeyAdapter`에서 `VK_ENTER`를 단순 `return`으로 처리하여 아무 동작도 하지 않았다.
콤보박스 내부 ActionEvent는 에디터의 현재 텍스트(필터 키워드)를 선택값으로 커밋하므로, `getSelectedItem()`이 실제 파일 경로가 아닌 키워드를 반환했다.

```
사용자: "Approval" 입력 → 필터링 → 결과 1건: "payment/approval/ApprovalMapper.xml"
에디터 텍스트: "Approval"  ← editor.setText(keyword)로 복원된 값
Enter 키
  → KeyAdapter: VK_ENTER → return (아무것도 안 함)
  → 콤보 내부: editor text("Approval")를 selectedItem으로 커밋
  → getSelectedItem() == "Approval"  ← 실제 파일 경로가 아님!
  → getSelectedMapperFilePath() → basedir + "Approval" → 파일 없음
```

#### 수정

Enter 입력 시 필터링 결과가 **정확히 1건**이면 해당 항목을 명시적으로 선택한다.

```java
if (keyCode == KeyEvent.VK_ENTER) {
    if (mapperFileCombo.getItemCount() == 1) {          // Query ID는 queryIdCombo
        String singleItem = mapperFileCombo.getItemAt(0);
        isUpdatingFilter = true;                        // isUpdatingQueryFilter
        try {
            mapperFileCombo.setSelectedItem(singleItem);
            mapperFileEditor.setText(singleItem);       // 에디터를 실제 값으로 교체
            mapperFileCombo.hidePopup();
        } finally {
            isUpdatingFilter = false;
        }
        reloadQueryIds();   // Query ID 콤보: Analyze 트리거는 사용자가 직접
    }
    return;
}
```

| 필터 결과 건수 | Enter 동작 |
|--------------|-----------|
| 0건 | 아무것도 안 함 |
| **1건** | **해당 항목 자동 선택 → 다음 단계 cascade** |
| 2건 이상 | 아무것도 안 함 (목록에서 직접 선택 유도) ← **2-5에서 추가 수정** |

---

### 2-5. Mapper File / Query ID Enter 자동완성 미작동 (다중 결과)

**파일:** `mybatis-sql-analyzer-intellij/.../toolwindow/SqlAnalyzerPanel.java`

#### 증상

필터링 결과가 **2건 이상**일 때 Enter를 눌러도 첫 번째 항목이 선택되지 않았다.
다른 항목으로 커서를 옮겼다가 다시 첫 번째 항목으로 돌아와서 Enter를 눌러야만 정상 선택이 됐다.

#### 원인 분석

`keyReleased`는 JComboBox가 Enter를 처리하는 **`keyPressed` 이후**에 실행된다는 타이밍 문제였다.

```
[keyPressed] JComboBox 내부 처리
  → 팝업 닫기
  → editor 텍스트("approval" 등 키워드)를 setSelectedItem()으로 커밋
  → ActionEvent 발생 → ActionListener → reloadQueryIds("approval") → 실패

[keyReleased] 우리 KeyAdapter 처리 (이미 늦은 시점)
  → getItemCount() == 1 ? → false(다중) → 아무것도 안 함
  → 이미 커밋된 "approval"가 selectedItem으로 남음
```

2-4에서 1건 케이스는 `getItemAt(0)`으로 강제 교정했지만, **2건 이상은 교정 분기가 없어** 키워드가 선택값으로 그대로 남았다.

화살표 키로 다른 항목을 탐색한 후에야 동작하는 이유:
- 화살표 탐색 시 editor 텍스트가 **실제 항목명**으로 변경됨
- Enter 시 JComboBox가 해당 실제 항목명을 커밋 → `allMapperFiles`에 존재 → 올바르게 처리됨

#### 수정

`getItemCount() == 1` 조건을 `allMapperFiles.contains(editorText)` 검증으로 교체한다.
editor 텍스트가 유효한 파일 경로가 아닐 경우, 화살표 탐색 결과(selectedIndex) 또는 첫 번째 항목을 교정 선택한다.

```java
if (keyCode == KeyEvent.VK_ENTER) {
    // keyReleased는 JComboBox의 keyPressed(팝업 닫기 + selectedItem 커밋) 이후에 실행된다.
    // editor 텍스트가 allMapperFiles에 없는 키워드라면 → 유효한 항목으로 교정한다.
    //   1. 화살표 키로 탐색하여 selectedIndex가 유효한 경우 → 해당 항목
    //   2. 탐색 없이 바로 Enter(selectedIndex == -1) → 첫 번째 항목(index 0)
    String editorText = mapperFileEditor.getText();

    if (!allMapperFiles.contains(editorText) && mapperFileCombo.getItemCount() > 0) {
        int idx = mapperFileCombo.getSelectedIndex();
        String itemToSelect = (idx >= 0 && idx < mapperFileCombo.getItemCount())
                ? mapperFileCombo.getItemAt(idx)
                : mapperFileCombo.getItemAt(0);
        isUpdatingFilter = true;
        try {
            mapperFileCombo.setSelectedItem(itemToSelect);
            mapperFileEditor.setText(itemToSelect);
            mapperFileCombo.hidePopup();
        } finally {
            isUpdatingFilter = false;
        }
        reloadQueryIds();
    }
    return;
}
```

Query ID 콤보도 동일한 패턴(`allQueryIds.contains(editorText)`)으로 수정.

| 필터 결과 건수 | Enter 동작 |
|--------------|-----------|
| 0건 | 아무것도 안 함 |
| **1건** | **`allMapperFiles`에 없음 → index 0 항목 자동 선택** |
| **2건 이상** | **`allMapperFiles`에 없음 → 화살표 탐색 항목 or index 0 자동 선택** |
| 정확한 파일명 직접 입력 | `allMapperFiles`에 있음 → JComboBox 기본 처리로 위임 |

---

### 1-5. AI 프롬프트 페르소나 문구 개선

**파일:** `mybatis-sql-analyzer-core/.../core/PromptGenerator.java`

#### 변경 전 / 후

| 구분 | 내용 |
|------|------|
| **변경 전** | `"너는 10년 차 수석 DBA이자 쿼리 튜닝 전문가야."` |
| **변경 후** | `"실무 10년 차 DBA 수준을 가진 전문가의 견해를 이용해서 분석해줘."` |

#### 배경

`"너는 X야"` 방식은 AI를 특정 역할로 고정시키는 방식이다.
`"X 수준의 견해로 분석해줘"` 방식은 AI 본연의 추론 능력을 자연스럽게 활용하도록 유도하며, 보다 실용적인 관점의 분석 결과를 이끌어낸다.

---

## 3. 변경 파일 목록

### `mybatis-sql-analyzer-core`

| 파일 | 변경 유형 | 내용 |
|------|----------|------|
| `core/JdbcAnalyzer.java` | 버그 수정 | ① EXPLAIN `setNull` → `setObject(i, 1)` 교체<br>② `getColumns`/`getIndexInfo` catalog `null` → `getCatalog()` 교체 |
| `core/PromptGenerator.java` | 개선 | AI 프롬프트 페르소나 문구 변경 (`"너는 X야"` → `"X 수준의 견해로 분석해줘"`) |

### `mybatis-sql-analyzer-intellij`

| 파일 | 변경 유형 | 내용 |
|------|----------|------|
| `config/SqlAnalyzerConfig.java` | 수정 | 파일 기반 `load()` 제거 → 순수 값 객체화 |
| `toolwindow/DbSettingsDialog.java` | 신규 | JDBC 설정 다이얼로그 (PropertiesComponent 저장) |
| `toolwindow/SqlAnalyzerPanel.java` | 수정 | ① DB Settings 버튼 추가<br>② Mapper Dir → File → QueryId 계층 UI<br>③ `...` 버튼 초기 위치 프로젝트 루트 설정<br>④ Mapper File 실시간 검색 필터 (KeyAdapter)<br>⑤ `IllegalStateException` 수정 (DocumentListener → KeyAdapter)<br>⑥ Mapper File Enter 자동완성 버그 수정 (1건)<br>⑦ Query ID 실시간 검색 필터 추가 (allQueryIds / isUpdatingQueryFilter / filterQueryIds)<br>⑧ Query ID Enter 자동완성 추가<br>⑨ Enter 자동완성 다중 결과 버그 수정 (`allMapperFiles.contains()` 검증으로 교정 범위 확대) |
| `service/SqlAnalyzerService.java` | 수정 | ① `listXmlFiles()` Files.walk() 재귀 탐색<br>② `analyze()` 파라미터에서 properties 파일 제거 |
