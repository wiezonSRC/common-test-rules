# 02. 코드에서 사용하는 IntelliJ Platform API

이 문서는 소스 코드에 등장하는 IntelliJ API 클래스를 하나씩 설명한다.

---

## 1. Tool Window 관련 API

### `ToolWindowFactory` 인터페이스

```java
public class SqlAnalyzerToolWindow implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project,
                                        @NotNull ToolWindow toolWindow) {
        // Tool Window가 처음 열릴 때 한 번 호출됨
        // 여기서 UI 컴포넌트를 생성하고 toolWindow에 등록한다
    }
}
```

- **역할**: IDE에 Tool Window를 추가하기 위해 구현해야 하는 인터페이스
- **호출 시점**: 사용자가 Tool Window를 처음 열거나, IDE 시작 시 (plugin.xml 설정에 따라)
- **주의**: 프로젝트(Project)가 열린 후에만 동작한다. Welcome Screen에서는 표시되지 않는다.

### `ToolWindow` 객체

```java
ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MyBatis SQL Analyzer");
toolWindow.show(() -> {
    // Tool Window가 열린 후 실행할 콜백
    Content content = toolWindow.getContentManager().getSelectedContent();
});
```

- `ToolWindowManager`: 현재 프로젝트의 모든 Tool Window를 관리하는 싱글턴
- `getToolWindow(id)`: plugin.xml에 등록된 id로 Tool Window 인스턴스를 가져옴
- `show(Runnable)`: Tool Window를 열고, 완전히 열린 후 콜백을 실행

### `Content` / `ContentFactory`

```java
ContentFactory contentFactory = ContentFactory.getInstance();
Content content = contentFactory.createContent(panel, "", false);  // (컴포넌트, 탭 제목, 잠금 여부)
toolWindow.getContentManager().addContent(content);
```

- Tool Window 안에는 여러 **탭(Content)**을 가질 수 있다
- `createContent(component, displayName, isLockable)`: UI 컴포넌트를 탭으로 감싸는 메서드

---

## 2. Action 관련 API

### `AnAction` 클래스

```java
public class AnalyzeSqlAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 메뉴 항목을 클릭했을 때 실행
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 메뉴 항목의 활성화/비활성화 여부를 결정
        // 이 메서드가 주기적으로 호출되어 메뉴 상태를 갱신함
        event.getPresentation().setEnabledAndVisible(true or false);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;  // update()를 백그라운드 스레드에서 실행
    }
}
```

- `AnAction`을 상속하면 IntelliJ 메뉴/단축키에서 실행 가능한 액션이 된다
- `update()`: 메뉴가 보일 때마다 호출되어 활성화 여부를 결정 (파일 타입 체크 등)
- `actionPerformed()`: 실제로 클릭했을 때 실행

### `AnActionEvent`와 `CommonDataKeys`

```java
Project project = event.getProject();                          // 현재 IntelliJ 프로젝트
VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE); // 현재 에디터에서 열린 파일
```

- `AnActionEvent`: 액션이 발생한 컨텍스트(프로젝트, 파일, 에디터 등) 정보를 담는 객체
- `CommonDataKeys`: 컨텍스트에서 꺼낼 수 있는 데이터 키 모음

### `VirtualFile`

```java
VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
String extension = file.getExtension();  // "xml"
InputStream input = file.getInputStream();  // 파일 내용 읽기
```

- IntelliJ의 **가상 파일 시스템(VFS)** 의 파일 표현
- 일반 `java.io.File`과 달리 IntelliJ가 캐싱·감시하는 추상 파일 객체

---

## 3. 파일 선택 다이얼로그

```java
VirtualFile chosen = FileChooser.chooseFile(
    FileChooserDescriptorFactory.createSingleFolderDescriptor(), // 폴더만 선택 가능
    project,
    null  // 초기 선택 위치 (null이면 최근 경로)
);
if (chosen != null) {
    String path = chosen.getPath();
}
```

- `FileChooser.chooseFile(...)`: IntelliJ 스타일의 파일/폴더 선택 다이얼로그
- `FileChooserDescriptorFactory`: 선택 대상을 제한하는 Descriptor 생성 팩토리
  - `createSingleFolderDescriptor()` → 폴더만 선택
  - `createSingleFileDescriptor()` → 단일 파일 선택

---

## 4. 메시지 다이얼로그

```java
Messages.showErrorDialog(project, "오류 내용", "다이얼로그 제목");
Messages.showWarningDialog(project, "경고 내용", "다이얼로그 제목");
int index = Messages.showChooseDialog(
    project,
    "선택하세요",        // 본문
    "파일 선택",         // 제목
    Messages.getQuestionIcon(),
    options,             // String[] 선택지
    options[0]           // 기본 선택
);
```

- IntelliJ 테마에 맞는 표준 다이얼로그를 쉽게 띄우는 유틸리티 클래스
- **반드시 EDT(Event Dispatch Thread)에서 호출**해야 한다 (→ 03 스레드 문서 참고)

---

## 5. `Project` 객체

```java
String basePath = project.getBasePath(); // 프로젝트 루트 디렉토리 절대 경로
```

- IntelliJ에서 현재 열린 프로젝트를 나타내는 핵심 객체
- 플러그인의 거의 모든 API는 `Project`를 첫 번째 파라미터로 받는다
- `getBasePath()`: `C:/work/my-project` 같은 프로젝트 루트 절대 경로 반환

---

## 요약 관계도

```
plugin.xml
  ├── <toolWindow factoryClass="SqlAnalyzerToolWindow">
  │       └── createToolWindowContent()
  │               └── SqlAnalyzerPanel (JPanel)
  │                       └── SqlAnalyzerService (비즈니스 로직)
  │
  └── <action class="AnalyzeSqlAction">
          ├── update()  →  XML 파일 여부 확인 → 메뉴 활성/비활성
          └── actionPerformed()  →  ToolWindowManager로 패널 열고 파일 경로 전달
```

> 다음 문서: [03-threading-model.md](03-threading-model.md) — IntelliJ의 EDT/BGT 스레드 모델
