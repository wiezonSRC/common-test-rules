# 04. Swing UI 기초 — `SqlAnalyzerPanel` 코드 이해하기

IntelliJ 플러그인의 UI는 **Java Swing**으로 만든다.  
`SqlAnalyzerPanel`이 이 Swing API를 사용하므로, 기초 개념을 알아야 코드를 읽을 수 있다.

---

## Swing의 핵심 구조

모든 Swing UI는 **컴포넌트(Component)를 컨테이너(Container)에 담는** 계층 구조다.

```
JPanel (컨테이너)
  ├── JLabel      (텍스트 레이블)
  ├── JTextField  (한 줄 입력창)
  ├── JComboBox   (드롭다운)
  ├── JButton     (버튼)
  └── JScrollPane (스크롤 영역)
        └── JTextArea (여러 줄 텍스트)
```

`SqlAnalyzerPanel` 자체가 `JPanel`을 상속한다.
```java
public class SqlAnalyzerPanel extends JPanel { ... }
```

---

## 레이아웃 매니저

Swing에서 컴포넌트의 **배치 방식**을 결정하는 것이 레이아웃 매니저다.

### `BorderLayout` — 5방향 배치

```java
setLayout(new BorderLayout(8, 8));  // (수평 간격, 수직 간격)

add(buildInputPanel(), BorderLayout.NORTH);   // 상단 고정
add(buildResultPanel(), BorderLayout.CENTER); // 남은 공간 전부 차지
add(buildBottomPanel(), BorderLayout.SOUTH);  // 하단 고정
```

```
┌───────────── NORTH ─────────────┐
│  Mapper Dir / Query ID / 버튼   │
├─────────────────────────────────┤
│                                 │
│           CENTER                │  ← 남은 공간 전부
│        (결과 텍스트)              │
│                                 │
├───────────── SOUTH ─────────────┤
│       [Copy to Clipboard]       │
└─────────────────────────────────┘
```

### `GridBagLayout` — 세밀한 격자 배치

입력 폼처럼 "레이블 + 필드 + 버튼"을 한 행에 배치할 때 사용한다.

```java
JPanel panel = new JPanel(new GridBagLayout());
GridBagConstraints gbc = new GridBagConstraints();
gbc.insets = new Insets(4, 4, 4, 4);  // 셀 내부 여백
gbc.fill = GridBagConstraints.HORIZONTAL;  // 수평으로 늘림

// 행 0, 열 0: 레이블
gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;   // 고정 너비
panel.add(new JLabel("Mapper Dir:"), gbc);

// 행 0, 열 1: 텍스트 필드 (가로 확장)
gbc.gridx = 1; gbc.weightx = 1.0;  // 남은 공간 차지
panel.add(mapperDirField, gbc);

// 행 0, 열 2: 버튼
gbc.gridx = 2; gbc.weightx = 0;   // 고정 너비
panel.add(browseButton, gbc);
```

`weightx = 1.0`은 "가로 공간이 남으면 이 컴포넌트에 줘라"는 의미다.

### `FlowLayout` — 흐름 배치

```java
JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));  // 오른쪽 정렬
panel.add(copyButton);
```

---

## 주요 컴포넌트

### `JTextField` — 한 줄 입력창

```java
mapperDirField = new JTextField();
String text = mapperDirField.getText().trim();
mapperDirField.setText("/path/to/mapper");
```

### `JComboBox<String>` — 드롭다운

```java
queryIdCombo = new JComboBox<>();
queryIdCombo.setEditable(true);  // 드롭다운에 없는 값도 직접 입력 가능

queryIdCombo.removeAllItems();        // 기존 항목 초기화
queryIdCombo.addItem("findById");     // 항목 추가

String selected = (String) queryIdCombo.getSelectedItem();  // 선택된 값
```

### `JTextArea` — 여러 줄 텍스트 (결과 표시용)

```java
resultArea = new JTextArea();
resultArea.setEditable(false);                              // 읽기 전용
resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); // 고정폭 폰트
resultArea.setLineWrap(true);                               // 자동 줄 바꿈
resultArea.setWrapStyleWord(true);                          // 단어 단위 줄 바꿈
```

`JScrollPane`으로 감싸야 스크롤이 생긴다.
```java
return new JScrollPane(resultArea);  // CENTER에 배치
```

### `JButton` — 버튼

```java
analyzeButton = new JButton("Analyze");
analyzeButton.addActionListener(e -> runAnalysis());  // 클릭 이벤트 연결
analyzeButton.setEnabled(false);  // 비활성화 (회색)
```

---

## 클립보드 복사

```java
Toolkit.getDefaultToolkit()
       .getSystemClipboard()
       .setContents(new StringSelection(text), null);
```

- `Toolkit.getDefaultToolkit()`: JVM의 플랫폼 기능 접근점 (OS 클립보드 포함)
- `StringSelection`: 문자열을 클립보드에 복사 가능한 형태로 감싸는 클래스

---

## 타이머로 버튼 텍스트 일시 변경

```java
copyButton.setText("Copied!");

Timer timer = new Timer(2000, e -> copyButton.setText("Copy to Clipboard"));
timer.setRepeats(false);  // 한 번만 실행
timer.start();            // 2000ms 후에 원래 텍스트로 복원
```

`javax.swing.Timer`는 EDT에서 실행되므로 UI 조작이 안전하다.  
(`java.util.Timer`는 별도 스레드에서 실행되므로 UI 조작에 사용하면 안 된다.)

---

## `SqlAnalyzerPanel` 전체 레이아웃 요약

```
SqlAnalyzerPanel (BorderLayout)
  │
  ├── NORTH: buildInputPanel() (GridBagLayout)
  │     ├── [행 0] JLabel("Mapper Dir:") | JTextField | JButton("...")
  │     └── [행 1] JLabel("Query ID:")   | JComboBox  | JButton("Analyze")
  │
  ├── CENTER: buildResultPanel()
  │     └── JScrollPane → JTextArea (읽기 전용, 고정폭 폰트)
  │
  └── SOUTH: buildBottomPanel() (FlowLayout.RIGHT)
        └── JButton("Copy to Clipboard")
```

> 다음 문서: [05-xml-and-jdbc.md](05-xml-and-jdbc.md) — XML 파싱과 JDBC 기초
