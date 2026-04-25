# 03. IntelliJ 스레드 모델 (EDT vs BGT)

플러그인 개발에서 가장 자주 실수하는 부분이다.  
이 개념을 이해하지 않으면 코드에서 `invokeAndWait`, `BGT`, `Backgroundable` 같은 표현이 왜 필요한지 알 수 없다.

---

## EDT(Event Dispatch Thread)란?

Java Swing은 **단 하나의 전담 스레드(EDT)**에서만 UI를 그리고 업데이트한다.

- **EDT에서만**: `JLabel.setText()`, `JComboBox.addItem()`, `JPanel.repaint()` 등 모든 UI 조작
- **EDT에서 절대 금지**: 파일 I/O, DB 연결, 네트워크 요청 등 시간이 오래 걸리는 작업

```
 EDT (UI 스레드)                 Background Thread
 ─────────────────               ──────────────────
 화면 그리기                      파일 읽기
 버튼 클릭 처리                   DB 쿼리 실행
 콤보박스 갱신                    네트워크 요청
 다이얼로그 표시
```

### EDT가 블로킹되면?

EDT에서 DB 연결(수 초 소요)을 직접 실행하면:
```
사용자 클릭 → [EDT 블로킹 중... DB 응답 대기]
            → 이 시간 동안 IDE 전체가 응답 없음(Freezing)
```

---

## IntelliJ의 스레드 전환 방법

### 1. 백그라운드 스레드로 전환: `ProgressManager`

```java
ProgressManager.getInstance().run(new Task.Backgroundable(project, "분석 중...") {
    private String result;

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        // 이 블록은 백그라운드 스레드에서 실행 → DB 연결, 파일 I/O 가능
        indicator.setText("DB 조회 중...");
        result = service.analyze(...);  // 수 초 걸리는 작업
    }

    @Override
    public void onSuccess() {
        // 이 블록은 다시 EDT에서 실행 → UI 갱신 가능
        resultArea.setText(result);
        copyButton.setEnabled(true);
    }

    @Override
    public void onCancel() {
        // 사용자가 취소 버튼을 눌렀을 때 EDT에서 실행
        resultArea.setText("취소되었습니다.");
    }
});
```

`SqlAnalyzerPanel.runAnalysis()`가 이 패턴을 사용한다.

### 2. EDT로 다시 전환: `ApplicationManager.invokeAndWait`

백그라운드 스레드에서 다이얼로그를 띄울 때 필요하다.

```java
// 백그라운드 스레드 내부에서 다이얼로그를 띄우려면 EDT로 전환해야 함
int[] choiceIndex = {-1};

ApplicationManager.getApplication().invokeAndWait(() -> {
    // 이 람다는 EDT에서 실행됨
    choiceIndex[0] = Messages.showChooseDialog(...);
});

// invokeAndWait이 끝나면 다시 백그라운드 스레드로 돌아옴
// choiceIndex[0]에 사용자 선택값이 들어 있음
Path selected = files.get(choiceIndex[0]);
```

**`invokeLater` vs `invokeAndWait` 차이:**

| 메서드 | 동작 | 사용 시점 |
|---|---|---|
| `invokeLater` | EDT에 작업을 예약하고 즉시 반환 (비동기) | 결과를 기다릴 필요 없을 때 |
| `invokeAndWait` | EDT 작업이 완료될 때까지 현재 스레드 블로킹 (동기) | 다이얼로그 선택값처럼 결과가 필요할 때 |

`invokeAndWait`을 잘못 사용하면 데드락이 발생할 수 있으므로 EDT에서는 절대 호출하지 않는다.

### 3. Action의 `update()` 스레드 지정: `ActionUpdateThread`

```java
@Override
public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;  // BGT = Background Thread
}

@Override
public void update(@NotNull AnActionEvent event) {
    // BGT를 지정했으므로 여기서 파일 I/O 가능
    boolean isXml = isMapperXmlContent(file);  // 파일 내용 읽기
    event.getPresentation().setEnabledAndVisible(isXml);
}
```

- IntelliJ 2022.3+부터 `update()`의 실행 스레드를 명시적으로 지정해야 한다
- `EDT`: UI 상태만 읽고 변경할 때 (I/O 없음)
- `BGT`: 파일 읽기, 느린 계산이 필요할 때 (우리 코드의 경우)

---

## 코드에서 EDT 규칙 위반이 발생하는 흔한 실수

```java
// ❌ 잘못된 예: 버튼 클릭 핸들러(EDT)에서 DB 직접 연결
analyzeButton.addActionListener(e -> {
    String result = service.analyze(...);  // DB 연결 → EDT 블로킹!
    resultArea.setText(result);
});

// ✅ 올바른 예: 백그라운드 태스크에서 DB 연결
analyzeButton.addActionListener(e -> {
    ProgressManager.getInstance().run(new Task.Backgroundable(...) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            result = service.analyze(...);  // 백그라운드에서 DB 연결
        }

        @Override
        public void onSuccess() {
            resultArea.setText(result);     // EDT에서 UI 갱신
        }
    });
});
```

---

## 요약

```
사용자 클릭 (EDT)
    │
    ▼
ProgressManager.run(Backgroundable)
    │
    ├──► run() [BGT] → DB 연결, 파일 읽기, 분석
    │         │
    │         ▼ (결과 저장)
    │
    └──► onSuccess() [EDT] → UI 갱신 (setText, setEnabled 등)
```

> 다음 문서: [04-swing-ui.md](04-swing-ui.md) — 코드에서 사용하는 Swing UI 컴포넌트
