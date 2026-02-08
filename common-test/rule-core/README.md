# Rule Core (Common Test Rules)

**Rule Core**는 프로젝트 내 코드 품질과 안정성을 유지하기 위한 **자동화된 규칙 검증 엔진**입니다.  
Java 코드 컨벤션, 아키텍처 원칙, SQL 작성 표준 및 안전성 규칙을 정의하고 검사합니다.

Spring Boot 및 MyBatis 환경에서 동작하도록 설계되었으며, JUnit 테스트 내에서 실행하여 CI/CD 파이프라인의 일부로 활용할 수 있습니다.

---

## 🏗 아키텍처 및 디자인 패턴

이 프로젝트는 확장성과 유연성을 위해 다음과 같은 디자인 패턴을 활용했습니다.

1.  **전략 패턴 (Strategy Pattern)**
    *   핵심 인터페이스인 `Rule`은 검증 전략을 추상화합니다.
    *   `RuleRunner`는 구체적인 규칙 구현체에 의존하지 않고 `Rule` 인터페이스를 통해 다양한 검증 로직을 실행합니다.
2.  **템플릿 메서드 패턴 (Template Method Pattern)**
    *   `ArchUnitBasedRule` 등 추상 클래스에서 공통 검증 흐름(준비 -> 검사 -> 결과 변환)을 정의하고, 세부 규칙 정의(`getDefinition`)는 하위 클래스에게 위임합니다.
3.  **컴포지트 패턴 (Composite Pattern)의 응용**
    *   `RuleGroups`를 통해 여러 개의 `Rule`을 하나의 그룹으로 묶어 관리합니다.
    *   사용자는 개별 규칙을 신경 쓸 필요 없이, `RuleGroups.ALL`과 같은 그룹 객체를 `RuleRunner`에 전달하여 일괄 실행할 수 있습니다.
4.  **리포팅 엔진 (Reporting Engine)**
    *   검사 결과는 콘솔뿐만 아니라 `json` 파일로 저장되어, 외부 시스템(CI 도구 등)에서 결과를 파싱하기 용이하도록 설계되었습니다.

## 🛠 Gradle Plugin 관리 및 배포

Rule Core는 단순한 라이브러리를 넘어, 프로젝트 전반의 표준을 강제하고 자동화하기 위해 **Gradle Plugin** 형태로 제공됩니다.

### 1. Plugin 도입 배경 (Introduction Background)
*   **표준화 (Standardization):** 각 프로젝트마다 서로 다른 코드 스타일 설정을 방지하고, 전사 공통 표준을 단 한 줄의 설정으로 강제합니다.
*   **자동화 (Automation):** ArchUnit을 통한 논리적 검증뿐만 아니라, Spotless를 이용한 물리적 코드 포맷팅(들여쓰기, 줄바꿈, SQL 대문자 등)을 자동화합니다.
*   **유지보수성 (Maintenance):** 새로운 규칙이 추가되거나 기존 규칙이 변경될 때, 각 프로젝트의 설정을 수정할 필요 없이 플러그인 버전 업데이트만으로 일괄 적용이 가능합니다.

### 2. Plugin 관리 방식 (Management Method)
플러그인은 다음과 같은 기능을 수행합니다.
*   **Spotless 통합 관리:** `com.diffplug.spotless` 플러그인을 내장하여 Java(Google Style) 및 XML/SQL 포맷팅 룰을 주입합니다.
*   **SQL 자동 변환:** MyBatis XML 내의 SQL 예약어(SELECT, FROM 등)를 자동으로 대문자로 변환하는 커스텀 룰을 포함합니다.
*   **의존성 관리:** 향후 ArchUnit 등 규칙 실행에 필요한 필수 라이브러리들을 플러그인 적용 시 자동으로 프로젝트에 포함시키도록 확장 가능합니다.

### 3. 배포 절차 (Deployment Procedure)

#### 로컬 테스트 배포
개발 중인 플러그인을 로컬 환경에서 테스트하려면 아래 명령어를 실행합니다.
```bash
# rule-core 프로젝트 루트에서 실행
./gradlew :rule-core:publishToMavenLocal
```
이 명령은 로컬 Maven 저장소(`~/.m2/repository`)에 플러그인을 배포합니다.

#### 프로젝트 적용 방법
플러그인을 적용할 프로젝트의 `build.gradle`에 다음과 같이 선언합니다.

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal() // 로컬 테스트 시 필수
        gradlePluginPortal()
        mavenCentral()
    }
}

// build.gradle
plugins {
    id "com.company.rule-core" version "0.1.1"
}
```

---

## 🚀 시작하기

### 1. 플러그인 적용 (Apply Plugin)

이 도구는 Gradle 플러그인 형태로 제공됩니다. `settings.gradle`과 `build.gradle`에 아래 설정을 추가하세요.

**settings.gradle**
```groovy
pluginManagement {
    repositories {
        mavenLocal() // 개발/테스트 시
        gradlePluginPortal()
        mavenCentral()
    }
}
```

**build.gradle**
```groovy
plugins {
    id "com.company.rule-core" version "0.1.2"
}
```

### 2. 플러그인 설정 (Configuration)

플러그인의 기능을 커스텀할 수 있습니다. 예를 들어, 자동 포맷팅(Spotless) 기능을 끄고 싶을 경우 아래와 같이 설정합니다.

```groovy
ruleCore {
    // 자동 포맷팅 기능을 활성화할지 여부 (기본값: true)
    enableFormatter = false 
}
```

### 3. 테스트 코드 작성 (Usage)
통합 테스트(`@SpringBootTest`) 내에서 `RuleRunner`를 실행하여 프로젝트 전체를 검사합니다.

```java
@SpringBootTest
class ProjectRuleCheckTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private SqlSessionFactory sqlSessionFactory; // MyBatis 사용 시
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("프로젝트 정적 분석 및 규칙 검사")
    void checkRules() {
        // 1. 검사 대상 컨텍스트 설정
        Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
        List<Path> mapperDirs = List.of(
            projectRoot.resolve("src/main/resources/mapper")
        );

        RuleContext context = new RuleContext(
                "com.your.package", // 검사할 Base Package
                projectRoot,
                mapperDirs,
                sqlSessionFactory,
                dataSource
        );

        // 2. 실행 (실패 시 예외 발생 및 리포트 생성)
        // RuleGroups.ALL: 모든 규칙 실행
        // RuleGroups.JAVA_STANDARD: Java 컨벤션만 실행
        // RuleGroups.SQL_SAFETY_AND_STYLE: SQL 관련 규칙만 실행
        new RuleRunner(RuleGroups.ALL).runOrFail(context);
    }
}
```

### 4. 규칙 검사 및 포맷팅 실행 (Execution)

플러그인과 테스트 코드가 설정되었다면, 대상 프로젝트의 터미널에서 아래 명령어를 통해 검사를 수행할 수 있습니다.

#### A. 자동 코드 포맷팅 (Fix)
코드 스타일, 공백, SQL 예약어 대문자 변환 등을 자동으로 적용합니다.
```bash
./gradlew spotlessApply
```

#### B. 규칙 위반 검사 (Check)
작성한 JUnit 테스트를 실행하여 ArchUnit 및 SQL 규칙 위반 여부를 확인합니다.
```bash
./gradlew test
```
*위반 사항이 있을 경우 `fail-report.json`이 생성되며 빌드가 실패합니다.*

#### C. 포맷팅 상태 확인 (CI/CD용)
코드를 수정하지 않고 스타일 가이드 위반 여부만 확인합니다. 주로 배포 파이프라인(CI)에서 사용합니다.
```bash
./gradlew spotlessCheck
```

---

## 📦 제공되는 규칙 (Available Rules)

규칙은 성격에 따라 `Status.FAIL`(빌드 실패)과 `Status.WARN`(경고 로그)으로 구분됩니다.

### Java Rules
| 규칙 클래스 | 설명 | 상태 |
| --- | --- | --- |
| `NoSystemOutRule` | `System.out`, `System.err` 사용을 금지합니다. (로거 사용 권장) | 🔴 FAIL |
| `NoFieldInjectionRule` | `@Autowired` 필드 주입을 금지합니다. (생성자 주입 권장) | 🔴 FAIL |
| `TransactionalSwallowExceptionRule` | 트랜잭션 내에서 예외를 잡고(catch) 다시 던지지 않는 코드를 탐지합니다. | 🔴 FAIL |
| `JavaNamingRule` | 클래스(PascalCase), 메서드/변수(camelCase) 등 네이밍 컨벤션을 검사합니다. | 🟠 WARN |

### SQL Rules
| 규칙 클래스 | 설명 | 상태 |
| --- | --- | --- |
| `NoDollarExpressionRule` | MyBatis Mapper에서 `${}`(Statement) 사용을 금지합니다. (`#{}` 권장) | 🔴 FAIL |
| `BoundSqlGenerationRule` | 실제 MyBatis 구문을 로딩하여 문법 오류나 바인딩 문제를 사전에 체크합니다. | 🔴 FAIL |
| `SqlStyleRule` | `SELECT *`, 암시적 조인(`,`) 등 안티 패턴을 검사합니다. | 🔴 FAIL |
| `SelectExplainRule` | `SELECT` 쿼리에 대해 `EXPLAIN`을 실행하여 Full Scan 등을 경고합니다. | 🟠 WARN |

---

## 📊 리포트 (Reporting)

`runOrFail()` 메서드가 실행되면, 프로젝트 루트 경로에 두 개의 JSON 파일이 생성됩니다.

1.  **`fail-report.json`**: 수정이 필수적인 `FAIL` 등급의 위반 사항 목록입니다. 이 파일이 비어있지 않으면 테스트는 실패(AssertionError)합니다.
2.  **`warn-report.json`**: 권장 사항이나 스타일 가이드 위반(`WARN`) 목록입니다. 테스트 실패를 유발하지 않지만, 주기적인 확인이 필요합니다.

**JSON 형식 예시:**
```json
[
  {
    "ruleName": "NoSystemOutRule",
    "status": "FAIL",
    "message": "Method <com.exam.Service.do()> calls <System.out.println>",
    "location": "Service.java:25"
  }
]
```

---

## ➕ 규칙 추가 방법 (How to Contribute)

새로운 규칙을 추가하려면 `Rule` 인터페이스를 구현하세요.

### 1. ArchUnit 기반 규칙 (Java 정적 분석)
`ArchUnitBasedRule`을 상속받아 `getDefinition()`만 구현하면 됩니다.

```java
public class MyNewRule extends ArchUnitBasedRule {
    @Override
    protected ArchRule getDefinition() {
        return ArchRuleDefinition.classes()
                .that().resideInAPackage("..controller..")
                .should().haveSimpleNameEndingWith("Controller");
    }
}
```

### 2. 일반 규칙 (커스텀 로직)
`Rule` 인터페이스를 직접 구현하여 자유롭게 로직을 작성합니다.

```java
public class MyCustomRule implements Rule {
    @Override
    public List<RuleViolation> check(RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();
        // ... 로직 수행 ...
        if (violationFound) {
            violations.add(new RuleViolation(
                "MyCustomRule",
                Status.FAIL,
                "Something went wrong",
                "File.java:10"
            ));
        }
        return violations;
    }
}
```

작성한 규칙은 `RuleGroups` Enum에 추가하여 그룹으로 관리하거나, 테스트 코드에서 직접 사용할 수 있습니다.