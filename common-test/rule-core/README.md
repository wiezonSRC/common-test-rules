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

---

## 🚀 시작하기

### 1. 의존성 추가
이 모듈은 테스트 스코프에서 사용됩니다. (Gradle 예시)

```groovy
testImplementation project(':rule-core')
// 또는 라이브러리로 배포된 경우
// testImplementation 'com.example:rule-core:0.0.1'
```

### 2. 테스트 코드 작성 (Usage)
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