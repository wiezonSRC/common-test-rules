# 01. IntelliJ 플러그인 개발 기초

## IntelliJ 플러그인이란?

IntelliJ IDEA는 JetBrains가 만든 Java IDE다.  
플러그인은 이 IDE에 새로운 기능(메뉴, 패널, 단축키 등)을 추가하는 확장 모듈이다.

우리 플러그인(`mybatis-sql-analyzer-intellij`)은 다음 두 가지 기능을 IDE에 추가한다.

| 기능 | 설명 |
|---|---|
| Tool Window | 우측 사이드바에 "MyBatis SQL Analyzer" 패널 고정 |
| Context Menu Action | XML 에디터 우클릭 시 "Analyze SQL with AI" 메뉴 항목 추가 |

---

## 플러그인의 진입점: `plugin.xml`

IntelliJ 플러그인은 반드시 `src/main/resources/META-INF/plugin.xml` 파일을 가져야 한다.  
이 파일이 플러그인의 **설계도**이며, IDE가 이 파일을 읽어 플러그인을 로드한다.

```xml
<idea-plugin>
    <!-- 플러그인 고유 ID (마켓플레이스 등록 시에도 이 ID를 사용) -->
    <id>com.example.mybatis-sql-analyzer-intellij</id>
    <name>MyBatis SQL Analyzer</name>
    <version>0.1.0</version>

    <!-- 이 플러그인이 의존하는 IntelliJ 모듈 (모든 IDE에서 동작하는 최소 의존성) -->
    <depends>com.intellij.modules.platform</depends>

    <!-- 확장 포인트: IDE의 기존 기능을 확장하는 방법 -->
    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="MyBatis SQL Analyzer"
                    anchor="right"
                    factoryClass="...SqlAnalyzerToolWindow"/>
    </extensions>

    <!-- 액션: 메뉴나 단축키로 실행되는 동작 등록 -->
    <actions>
        <action id="..."
                class="...AnalyzeSqlAction"
                text="Analyze SQL with AI">
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
        </action>
    </actions>
</idea-plugin>
```

### 핵심 개념: 확장 포인트(Extension Point)

IntelliJ는 IDE 내부 기능을 **확장 포인트**로 공개한다.  
플러그인은 이 확장 포인트에 자신의 구현체를 등록함으로써 기능을 추가한다.

```
IDE가 정의한 확장 포인트          플러그인이 등록하는 구현체
─────────────────────────         ──────────────────────────
com.intellij.toolWindow      →    SqlAnalyzerToolWindow (factoryClass)
com.intellij.action          →    AnalyzeSqlAction (class)
```

---

## 프로젝트 빌드 시스템

IntelliJ 플러그인은 `org.jetbrains.intellij.platform` Gradle 플러그인으로 빌드한다.

```groovy
// build.gradle
plugins {
    id 'org.jetbrains.intellij.platform' version '2.6.0'
}

intellijPlatform {
    intellijIdeaCommunity '2024.1'  // 빌드 대상 IDE 버전
}
```

이 Gradle 플러그인이 제공하는 주요 태스크:

| 태스크 | 설명 |
|---|---|
| `runIde` | 플러그인이 설치된 샌드박스 IDE를 실행하여 개발 중 테스트 |
| `buildPlugin` | 배포 가능한 `.zip` 파일 생성 (Settings → Install Plugin from Disk에서 설치) |
| `verifyPlugin` | JetBrains 가이드라인 위반 여부 검사 |

---

## 플러그인 배포 및 설치 방법

### 개발 중 테스트
```bash
./gradlew :mybatis-sql-analyzer-intellij:runIde
```
→ 별도의 IntelliJ 샌드박스 창이 열리며, 플러그인이 자동으로 로드된다.

### 실제 IDE에 설치
```bash
./gradlew :mybatis-sql-analyzer-intellij:buildPlugin
```
→ `build/distributions/mybatis-sql-analyzer-intellij-0.1.0.zip` 생성  
→ IntelliJ **Settings → Plugins → ⚙️ → Install Plugin from Disk** 에서 zip 파일 선택

---

## 모듈 구성

```
mybatis-sql-analyzer-intellij/
├── build.gradle                          # Gradle 빌드 설정
└── src/main/
    ├── java/com/example/sqlanalyzer/intellij/
    │   ├── action/
    │   │   └── AnalyzeSqlAction.java     # 우클릭 메뉴 액션
    │   ├── config/
    │   │   └── SqlAnalyzerConfig.java    # .properties 설정 파일 로더
    │   ├── service/
    │   │   └── SqlAnalyzerService.java   # core 모듈 오케스트레이터
    │   └── toolwindow/
    │       ├── SqlAnalyzerToolWindow.java # Tool Window 팩토리 (진입점)
    │       ├── SqlAnalyzerPanel.java      # Tool Window의 실제 UI
    │       └── XmlQueryIdReader.java      # 매퍼 XML에서 queryId 목록 읽기
    └── resources/META-INF/
        └── plugin.xml                    # 플러그인 설계도
```

> 다음 문서: [02-intellij-api.md](02-intellij-api.md) — 코드에서 사용하는 IntelliJ API 상세
