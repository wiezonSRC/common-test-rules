# 아키텍처 분석 보고서

- 분析일시: 2026-04-15 15:19:14
- 분析 대상 프로젝트: rule-core + rule-gradle-plugin

---

<step1_code_analysis>

## 비즈니스 흐름 및 도메인 목적 요약

### 시스템 목적

`rule-core`와 `rule-gradle-plugin`은 **전사 공통 코드 품질 게이트(Quality Gate) 프레임워크**입니다. 개발자가 작성한 Java 코드와 MyBatis XML SQL에 대해 정적 분석을 수행하고, 위반 시 리포트를 생성하거나 빌드를 실패시키는 것이 핵심 목적입니다.

### 주요 도메인 객체

| 객체 | 역할 |
|------|------|
| `Rule` | 단일 검증 전략을 정의하는 최상위 인터페이스 |
| `RuleContext` | 검사에 필요한 환경 정보 (패키지 경로, 프로젝트 루트, 변경 파일 목록 등) |
| `RuleViolation` | 위반 사항 1건의 상세 정보 (규칙명, 상태, 메시지, 파일 경로, 라인 번호) |
| `RuleResult` | 전체 실행 결과 집계 (위반 목록 + 실행 시간) |
| `RuleRunner` | 규칙 목록을 순회 실행하고 결과를 Report로 위임하는 실행기 |
| `RuleGroups` | 관련 규칙을 그룹으로 묶어 관리하는 Enum |
| `Report` | 콘솔 출력 및 JSON 파일 저장을 담당하는 출력 모듈 |
| `RuleTask` | Gradle Task로서 Extension 설정을 읽고 RuleRunner를 호출 |
| `RuleExtension` | Gradle DSL 설정 클래스 (basePackage, incremental, failOnViolation 등) |

### 주요 유즈케이스

1. **전수 검사 (Full Scan)**: CI/CD 환경에서 프로젝트 전체 `.class` 또는 Mapper XML을 대상으로 모든 규칙 실행
2. **증분 검사 (Incremental Scan)**: 로컬 개발 시 `git diff` 기반으로 변경된 파일만 대상으로 빠른 검사 수행
3. **포맷팅 자동화**: Spotless를 통해 Java(Google Style) 및 XML/SQL 예약어 대문자화 자동 처리
4. **빌드 게이트 연동**: `./gradlew check` 실행 시 ruleCheck가 자동 선행 실행

### 데이터 흐름

```
Gradle Build (./gradlew ruleCheck)
    └── RuleTask.execute()
          ├── GitDiffUtil.getAffectedFiles()   // 증분 대상 수집
          ├── RuleContext.builder().build()    // 환경 정보 조립
          ├── RuleGroups.valueOf(groupName)    // 규칙 그룹 선택
          └── RuleRunner.run(runner, context, reportDir)
                ├── ArchUnitBasedRule.check()  // Java 정적 분석
                ├── MybatisUnitBasedRule.check() // SAX XML 파싱
                └── Report.printConsoleReport / createFailReport / createWarnReport
```

### PG 도메인 연관성

본 모듈은 도메인 독립적인 횡단 관심사(Cross-cutting Concern) 도구이지만, PG 도메인 관점에서 다음 규칙들이 특히 중요합니다:

- **TransactionalSwallowExceptionRule**: 결제 트랜잭션에서 예외를 삼키면 롤백이 발생하지 않아 데이터 정합성이 깨지는 치명적 버그 방지
- **NoDollarExpressionRule**: MyBatis `${}`는 SQL Injection 취약점으로, 결제 데이터 조작 공격 방지
- **NoFieldInjectionRule**: 생성자 주입을 통한 불변 객체 보장으로 멀티스레드 안전성 향상

</step1_code_analysis>

---

<step2_architecture_analysis>

## 구조적 설계 평가

### 관심사 분리(SoC) 평가

**전반적으로 잘 분리되어 있으나, 두 가지 경계 위반이 존재합니다.**

| 계층 | 클래스 | 평가 |
|------|--------|------|
| 엔진(Engine) | `Rule`, `RuleRunner`, `RuleContext`, `RuleResult`, `RuleViolation` | 양호 - 실행 흐름만 담당 |
| 규칙(Rules) | `ArchUnitBasedRule`, `MybatisUnitBasedRule`, 각 fail 클래스 | 양호 - 검증 로직만 담당 |
| 리포팅(Report) | `Report` | **위반** - 콘솔 출력과 파일 저장이 한 클래스에 혼재 |
| 플러그인(Plugin) | `RuleTask`, `RulePlugin`, `RuleExtension` | 양호 - Gradle 생명주기에 집중 |

**핵심 위반 1 - Report 클래스 이중 책임:**
`Report` 클래스가 콘솔 출력(`printConsoleReport`)과 파일 I/O(`createFailReport`, `saveReport`)를 동시에 담당합니다. 이는 SRP(단일 책임 원칙) 위반입니다. 콘솔 출력 포맷과 파일 저장 포맷이 독립적으로 변경될 수 있기 때문입니다.

**핵심 위반 2 - RuleRunner의 Report 직접 생성:**
`RuleRunner.run()`이 `new Report(outputDir)`를 직접 생성합니다. 이는 실행기가 출력 방식에 강결합되는 구조로, 리포터를 교체하거나 테스트에서 출력을 억제하기 어렵습니다.

### 의존성 역전(DIP) 평가

**Rule 인터페이스 측면은 우수하나, RuleGroups Enum이 심각한 DIP 위반을 내포합니다.**

```java
// 문제 코드: RuleGroups.java
public enum RuleGroups {
    JAVA_CRITICAL(
            new NoSystemOutRule(),       // 구체 클래스를 Enum 상수 초기화 시점에 new
            new NoFieldInjectionRule(),  // Enum 로딩과 동시에 인스턴스 생성
            new NoGenericCatchRule()
    ),
    ALL(
            new NoSystemOutRule(),       // NoSystemOutRule이 JAVA_CRITICAL과 ALL에 중복 인스턴스화
            ...
    );
}
```

**문제점:**
1. `RuleGroups` Enum이 `rule-core/rules/java/fail` 패키지와 `rule-core/rules/sql/fail` 패키지의 구체 구현 클래스들을 직접 참조합니다. 엔진 레이어의 Enum이 구현 레이어에 역방향 의존성을 가집니다.
2. `ALL` 그룹에서 `NoSystemOutRule` 등이 `JAVA_CRITICAL`에서와 별개의 인스턴스로 중복 생성됩니다.
3. 새 규칙 추가 시 반드시 Enum 소스 코드를 수정해야 하므로 OCP(개방-폐쇄 원칙)도 위반합니다.

### 모듈 간 결합도 평가

```
rule-gradle-plugin
    └── api project(':rule-core')          // 적절 - api 선언으로 전이적 의존성 제공
        ├── archunit (정적 분석 엔진)       // 적절
        ├── jsqlparser (SQL 파싱)           // 적절
        ├── spring-context / spring-tx     // 경고 - 규칙 검사용인데 런타임 Spring 의존성 포함
        └── mybatis-spring-boot-starter    // 경고 - 스타터 전체 의존성 포함, 과도한 범위
```

`rule-core`가 `spring-context`, `spring-tx`, `mybatis-spring-boot-starter`를 `implementation`으로 선언합니다. 이 라이브러리들은 각각 `@Autowired`, `@Transactional` 어노테이션 타입 참조 및 SAX 파싱을 위한 것이지만, `mybatis-spring-boot-starter` 전체를 끌어오는 것은 과도합니다. 어노테이션 타입만 참조한다면 `compileOnly`로도 충분합니다.

### 응집도 평가

| 클래스 | 응집도 평가 |
|--------|------------|
| `RuleContext` | 높음 - 검사 환경 정보만 보유 |
| `RuleViolation` | 높음 - 위반 1건의 데이터만 보유, 단 `format()`과 Jackson 직렬화가 혼재 |
| `MybatisUnitBasedRule` | **낮음** - SAX 상태(currentXmlPath, currentViolations, locator, context)를 인스턴스 필드로 관리, 파일간 상태 오염 위험 |
| `SqlBasicPerformanceRule` | **낮음** - `sqlBuffer`, `isSqlTag` 필드를 인스턴스 상태로 가짐, 파일 재사용 시 초기화 누락 위험 |
| `RulePlugin` | 보통 - Spotless 설정 로직이 비대해져 별도 클래스 분리 검토 필요 |

</step2_architecture_analysis>

---

<step3_code_review>

## 시스템 확장성 및 안전성 리뷰

### 시스템 확장성

**Stateless/Stateful 분석:**

`RuleRunner`는 Stateless하게 설계되어 있어 병렬 실행이 가능하지만, `MybatisUnitBasedRule`과 `SqlBasicPerformanceRule`은 **Stateful** 설계로 인해 심각한 동시성 문제를 내포합니다.

```java
// MybatisUnitBasedRule.java - 인스턴스 필드가 상태를 가짐
public abstract class MybatisUnitBasedRule extends DefaultHandler implements Rule {
    protected Path currentXmlPath;           // 현재 파싱 중인 파일 경로
    protected List<RuleViolation> currentViolations;  // 공유 위반 목록
    protected Locator locator;               // SAX 위치 정보
    protected RuleContext context;           // 실행 컨텍스트
}
```

SAXParser를 재사용할 때 동일한 핸들러 인스턴스(this)를 전달하므로, 파일 파싱 간 상태가 누적됩니다. 여러 파일을 순차 파싱하는 경우 `currentXmlPath`와 `locator`가 정확히 교체되지만, 만약 병렬 스트림으로 확장된다면 경쟁 상태(Race Condition)가 발생합니다.

**SAXParser 인스턴스 재사용 위험:**

```java
// MybatisUnitBasedRule.check() - 하나의 SAXParser를 여러 파일에 재사용
SAXParser saxParser = factory.newSAXParser();
// ...
files.filter(p -> p.toString().endsWith(".xml"))
     .forEach(xml -> parseXml(xml, saxParser)); // 동일 saxParser 인스턴스 반복 사용
```

SAXParser는 내부적으로 `reset()` 없이 재사용되면 상태가 남을 수 있습니다. 특히 `DefaultHandler`를 상속한 핸들러의 내부 상태(`tagStack` in `MyBatisIfRule`, `sqlBuffer`/`isSqlTag` in `SqlBasicPerformanceRule`)가 이전 파일의 잔여 상태를 다음 파일 파싱에 오염시킬 수 있습니다.

**구체적 버그 - SqlBasicPerformanceRule의 tagStack 미초기화:**

```java
// MyBatisIfRule.java
public class MyBatisIfRule extends MybatisUnitBasedRule {
    private final Deque<String> tagStack = new ArrayDeque<>(); // 파일 간 초기화 없음
```

`check()` 메서드가 여러 XML 파일을 순서대로 파싱할 때 `tagStack`이 파일 경계에서 초기화되지 않습니다. 파일 A에서 `<where>` 태그가 열린 상태로 파싱이 종료되면, 파일 B의 `<if>` 태그가 SAFETY_TAGS 내부에 있다고 잘못 판단합니다.

### 스레드 안전성(Thread-safety)

**TransactionalSwallowExceptionRule의 예외 무시:**

```java
// TransactionalSwallowExceptionRule.java
} catch (Exception ignored) {} // 분석 실패 시 조용히 무시
```

ASM 바이트코드 분석 중 발생한 예외를 완전히 억제합니다. 분석 대상 메서드에 실제 예외를 삼키는 패턴이 있어도 ASM 로딩 자체가 실패하면 이 규칙은 해당 클래스를 검사하지 않고 통과시킵니다. 보안/품질 관점에서 false negative(오탐 누락)가 발생합니다.

**RuleRunner의 Exception catch 범위:**

```java
// RuleRunner.executeRules()
} catch (Exception e) {
    all.add(new RuleViolation(
            rule.getClass().getSimpleName(),
            Status.FAIL,
            "Exception during rule execution: " + e.getMessage(),
            ...
    ));
}
```

`Exception`을 catch하는 것은 의도적이지만, `e.getMessage()`가 null을 반환하는 경우(일부 예외는 메시지가 없음) `"Exception during rule execution: null"` 이 저장됩니다.

### 코드 컨벤션 위반 사항

**1. System.out.println 사용 (컨벤션 위반 - @Slf4j 사용 필수)**

`Report.java`에서 `System.out.println`과 `System.err.println`을 직접 사용합니다. 이 클래스 자체가 `NoSystemOutRule`의 적용 대상이 됩니다.

```java
// Report.java - 위반
System.out.println("\n[RULE CHECK] No violations found. Well done!\n");
System.out.println(violation.format());
System.err.println("[REPORT] Failed to write report: " + e.getMessage());
System.out.println("[REPORT] Report written to: " + path.toUri());
```

**2. GitDiffUtil.java의 System.err.println 사용**

```java
// GitDiffUtil.java - 위반
System.err.println("[GIT] Failed to get diff for " + baseRef + ": " + e.getMessage());
```

**3. 중복 import - MybatisBadSqlSelectStarTest.java**

```java
import java.util.ArrayList;
import java.util.List;
// Stack import가 존재하나 실제로는 ArrayDeque 사용
import java.util.Stack; // 미사용 import
```

**4. RuleViolation 생성자 호출 시 하드코딩된 빈 문자열**

```java
// RuleRunner.executeRules() - 의미 없는 빈 문자열 전달
all.add(new RuleViolation(
        rule.getClass().getSimpleName(),
        Status.FAIL,
        "Exception during rule execution: " + e.getMessage(),
        "",  // filePath: 빈 문자열 - null이 더 명확
        "",  // relativePath: 빈 문자열 - null이 더 명확
        0    // lineNumber: 0 - 의미 없는 매직 넘버
));
```

**5. 라인 길이 및 변수명 컨벤션**

`ArchUnitBasedRule.check()` 내부의 변수 `cn`, `mn`은 한두 글자 무의미 축약어 사용 금지 규칙에 위반합니다. 각각 `classNode`, `methodNode`로 변경해야 합니다.

**6. NoSystemOutRule의 과도한 범위**

```java
// NoSystemOutRule.java
return ArchRuleDefinition.noClasses()
        .should().accessClassesThat().belongToAnyOf(System.class)
        .as(" System.out 일반 출력 금지 ");
```

`System.class` 전체를 금지하므로 `System.getProperty()`, `System.currentTimeMillis()`, `System.exit()` 등 정상적인 System 클래스 사용도 모두 위반으로 탐지합니다. 의도는 `System.out`/`System.err`만 차단하는 것인데 범위가 지나치게 넓습니다.

**7. README와 실제 코드 불일치**

README는 `RuleContext` 생성자에 `sqlSessionFactory`, `dataSource`를 받는 오래된 API를 기준으로 작성되어 있으나, 실제 코드는 `record` 기반 Builder 패턴으로 변경되어 있습니다. 플러그인 ID도 README에서 `com.company.rule-core`로 표기하지만 실제는 `com.company.rule`입니다.

</step3_code_review>

---

<step4_pros_and_cons>

## 구조적 장단점 분석

### 장점

**1. 전략 패턴 + 템플릿 메서드 패턴의 적절한 조합**

`Rule` 인터페이스를 통해 검증 전략을 추상화하고, `ArchUnitBasedRule`과 `MybatisUnitBasedRule`이 공통 파싱 흐름을 템플릿 메서드로 구현합니다. 새 규칙 추가 시 `getDefinition()`만 오버라이드하면 되므로 확장 비용이 낮습니다.

**2. Java record 기반 불변 값 객체 설계**

`RuleContext`, `RuleResult`, `RuleViolation`을 Java record로 선언하여 불변성을 보장합니다. Builder 패턴도 포함되어 있어 선택적 필드 조립이 명확하고, 멀티스레드 환경에서 공유해도 안전합니다.

**3. 증분 검사(Incremental Scan) 설계**

`RuleContext.hasAffectedFiles()`를 통해 전수 검사와 증분 검사를 동일한 인터페이스로 분기합니다. `GitDiffUtil`이 `git diff`와 `git ls-files`를 모두 수집하여 미추적 파일도 포함하는 것은 실용적인 설계입니다.

**4. Gradle Extension을 abstract class로 선언**

`RuleExtension`을 `abstract class`로 선언하여 Gradle이 `Property<T>`를 자동으로 구현합니다. `convention()`을 통한 기본값 설정과 `project.getProviders()`를 활용한 지연 평가(Lazy Evaluation)가 올바르게 구현되어 있습니다.

**5. JSON 리포트의 이중 경로 설계**

`RuleViolation`에서 절대 경로(`filePath`)는 콘솔 출력용으로 IDE 클릭 이동을 지원하고, 상대 경로(`relativePath`)는 JSON 리포트용으로 분리한 설계는 CI 환경의 경로 절대화 문제를 사전에 방지합니다.

**6. RulePlugin의 태스크 순서 제어**

`mustRunAfter`를 사용해 `spotlessApply`가 먼저 실행된 후 `ruleCheck`가 동작하도록 순서를 보장합니다. `checkAll` 태스크로 포맷팅과 규칙 검사를 단일 명령으로 통합한 것도 개발자 경험(DX) 측면에서 우수합니다.

### 단점 및 개선 필요 사항

**단점 1. RuleGroups Enum의 DIP 위반 및 OCP 위반 (심각도: 높음)**

현재 구조에서 새 규칙을 추가하려면 반드시 `RuleGroups.java` 소스를 수정해야 합니다. 엔진 레이어의 Enum이 구현 레이어를 직접 의존하는 역방향 의존성입니다.

**개선 방향:** `RuleGroups`에서 직접 `new`로 생성하는 대신, 팩토리 메서드 또는 ServiceLoader 패턴으로 전환합니다.

```java
// 개선안: RuleGroups에서 구체 클래스를 제거하고 팩토리로 위임
public enum RuleGroups {
    JAVA_CRITICAL {
        @Override
        public List<Rule> getRules() {
            return JavaRuleFactory.createCriticalRules();
        }
    },
    SQL_CRITICAL {
        @Override
        public List<Rule> getRules() {
            return SqlRuleFactory.createCriticalRules();
        }
    };

    public abstract List<Rule> getRules();
}
```

트레이드오프: 팩토리 클래스가 추가되어 간접 계층이 늘어나지만, 새 규칙 추가 시 `RuleGroups` 수정 없이 팩토리 클래스만 수정하면 됩니다.

**단점 2. MybatisUnitBasedRule의 인스턴스 상태 오염 (심각도: 높음)**

SAX 파싱 중 인스턴스 필드를 상태 저장소로 사용하므로, 파일 간 파싱에서 `tagStack`, `sqlBuffer`, `isSqlTag` 등의 상태가 누적됩니다.

**개선 방향:** 파일 단위 파싱 시작 시 상태를 명시적으로 초기화하거나, 파일별로 새 핸들러 인스턴스를 생성합니다.

```java
// 개선안: parseXml()에서 파일 파싱 전 상태 리셋 훅 제공
private void parseXml(Path xml, SAXParser saxParser) {
    this.currentXmlPath = xml;
    resetState(); // 파일 파싱 전 상태 초기화 훅 (하위 클래스 오버라이드 가능)
    try {
        saxParser.parse(xml.toFile(), this);
    } catch (Exception ignored) {
    }
}

// 하위 클래스에서 오버라이드
protected void resetState() {
    // 기본 구현: no-op
}
```

트레이드오프: `resetState()`를 오버라이드하는 것을 잊으면 동일한 버그가 재발합니다. 더 안전한 방법은 파일별 새 인스턴스 생성이지만 객체 생성 비용이 증가합니다.

**단점 3. Report 클래스의 SRP 위반 및 System.out 사용 (심각도: 중간)**

`Report`가 콘솔 출력과 파일 I/O를 모두 담당하며, 정작 자신이 강제하는 `@Slf4j` 규칙(`NoSystemOutRule`)을 스스로 위반합니다.

**개선 방향:** `ConsoleReporter`와 `FileReporter` 인터페이스로 분리하고, 로거 사용으로 전환합니다.

```java
// 개선안
public interface Reporter {
    void report(RuleResult result);
}

@Slf4j
public class ConsoleReporter implements Reporter {
    @Override
    public void report(RuleResult result) {
        log.info("RULE CHECK RESULTS: {} violations", result.violations().size());
        // ...
    }
}
```

트레이드오프: `System.out.println`은 Gradle 빌드 출력(standard output)으로 바로 전달되어 사용자가 콘솔에서 확인하기 편리합니다. `@Slf4j`로 교체하면 Gradle 로그 레벨 설정에 따라 출력이 숨겨질 수 있습니다. Gradle 태스크 내에서는 `getLogger()` 사용이 더 적절합니다.

**단점 4. NoSystemOutRule의 탐지 범위 과잉 (심각도: 중간)**

`belongToAnyOf(System.class)` 조건은 `System.currentTimeMillis()`, `System.getProperty()`, `System.exit()` 등 정상적인 System 클래스 사용까지 모두 차단합니다.

**개선 방향:**

```java
// 개선안: System.out과 System.err만 차단
@Override
protected ArchRule getDefinition() {
    return ArchRuleDefinition.noClasses()
            .should().accessClassesThat().haveFullyQualifiedName("java.io.PrintStream")
            .allowEmptyShould(true)
            .as("System.out / System.err 직접 출력 금지 - @Slf4j 사용");
}
```

트레이드오프: `PrintStream`을 차단하면 파일로 출력하는 `PrintStream` 사용도 함께 차단됩니다. `callMethod(System.class, "println")` 방식이 더 정밀하지만 ArchUnit API가 복잡해집니다.

**단점 5. ALL 그룹에서 규칙 인스턴스 중복 생성 (심각도: 낮음)**

`JAVA_CRITICAL`과 `ALL` 그룹에 동일한 규칙 클래스가 별개 인스턴스로 두 번 생성됩니다. 규칙 인스턴스 자체는 가볍지만, 향후 규칙 수가 늘어나면 관리 부담이 증가합니다.

**개선 방향:** `ALL`을 `JAVA_CRITICAL` + `SQL_CRITICAL`의 합집합으로 구성합니다.

```java
ALL {
    @Override
    public List<Rule> getRules() {
        List<Rule> combined = new ArrayList<>(JAVA_CRITICAL.getRules());
        combined.addAll(SQL_CRITICAL.getRules());
        return combined;
    }
}
```

**단점 6. RuleTask의 Gradle 구성 캐시(Configuration Cache) 미대응 (심각도: 낮음)**

`RuleTask`가 `@TaskAction`에서 `getProject()`를 호출합니다. Gradle Configuration Cache 활성화 환경에서는 태스크 실행 단계에서 `Project` 객체 접근이 금지됩니다.

**개선 방향:** `@InputDirectory`, `@Input` 어노테이션을 사용해 입력 값을 태스크 프로퍼티로 선언하고, 빌드 구성 단계에서 값을 확정합니다.

</step4_pros_and_cons>

---

<step5_interview_qna>

## 시스템 아키텍처 심화 인터뷰 Q&A

**Q1. RuleGroups Enum이 구체 Rule 클래스를 직접 참조하는 현재 구조의 문제점은 무엇이며, 어떻게 개선하시겠습니까?**

> **모범 답변:**
> 현재 `RuleGroups` Enum은 `ruleEngine.enums` 패키지에 위치하면서 `rules.java.fail`과 `rules.sql.fail` 패키지의 구체 클래스를 직접 `new`로 생성합니다. 이는 두 가지 원칙을 위반합니다.
>
> 첫째, **DIP(의존성 역전 원칙)** 위반입니다. 엔진 계층의 Enum이 구현 계층을 향해 아래 방향으로 의존합니다. Rule 인터페이스를 만든 이유인 "구체 클래스에 의존하지 않겠다"는 원칙을 Enum 자신이 깨고 있습니다.
>
> 둘째, **OCP(개방-폐쇄 원칙)** 위반입니다. 새 규칙 추가 시마다 Enum 소스를 직접 수정해야 하며, `ALL` 그룹에 동일 규칙이 중복 인스턴스화되는 관리 부담도 생깁니다.
>
> 개선 방향으로는 두 가지를 검토합니다. 단기적으로는 `RuleGroups`에서 구체 클래스 참조를 제거하고, 각 그룹이 팩토리 메서드(`JavaRuleFactory`, `SqlRuleFactory`)에 위임하는 구조로 전환합니다. 장기적으로는 `ServiceLoader`나 Spring의 `ApplicationContext`를 활용해 `Rule` 구현체를 자동 수집하고, `@RuleGroup` 어노테이션으로 그룹 소속을 선언하는 방식으로 확장합니다. 다만 후자는 rule-core가 Spring에 의존하는 현재 구조에서만 가능하며, 라이브러리 특성상 Spring 의존성을 최소화하는 방향이 더 바람직합니다.

---

**Q2. MybatisUnitBasedRule이 인스턴스 필드로 SAX 상태를 관리하는 구조에서 발생할 수 있는 동시성 문제와 실제 버그를 설명하고, 해결 방법을 제시하세요.**

> **모범 답변:**
> `MybatisUnitBasedRule`은 `DefaultHandler`를 상속하며 `currentXmlPath`, `currentViolations`, `locator`, `context`를 인스턴스 필드로 갖습니다. 이 구조에서 두 가지 문제가 발생합니다.
>
> **실제 버그 - 파일 간 상태 오염:** `MyBatisIfRule`의 `tagStack`은 파일 파싱이 완료될 때 초기화되지 않습니다. 예를 들어 파일 A의 `<where>` 태그 안에서 파싱이 비정상 종료(예외 무시)되면, `tagStack`에 `"where"`가 남습니다. 이어서 파일 B를 파싱할 때 `<if>` 태그를 만나면 `tagStack`에 `"where"`가 남아 있어 해당 `<if>`를 정상으로 판단합니다. 즉, 위반 탐지가 누락됩니다(false negative).
>
> **잠재적 동시성 버그:** 현재 파일을 순차 파싱하므로 실제 경쟁 상태는 발생하지 않지만, 향후 `parallelStream()`으로 전환하거나 여러 프로젝트를 병렬 검사하는 시나리오에서 `currentViolations` 리스트에 동시 접근이 발생합니다.
>
> **해결 방법:** 가장 안전한 방법은 파일 파싱 시마다 새 핸들러 인스턴스를 생성하는 것입니다. `check()` 메서드에서 각 XML 파일별로 `this`가 아닌 `createHandler()`로 생성된 새 핸들러를 사용하면 상태 공유가 원천 차단됩니다. 성능이 중요하다면 `resetState()` 훅을 추상 메서드로 정의하고 각 파일 파싱 전에 호출하되, 컴파일러가 구현을 강제하도록 `abstract`로 선언하는 방법도 있습니다.

---

**Q3. 이 품질 게이트 프레임워크를 수천 개의 매퍼 XML과 수십만 라인의 Java 클래스를 가진 대규모 프로젝트에 적용할 때 발생할 수 있는 성능 병목과 그 해결 전략을 설명하세요.**

> **모범 답변:**
> 대규모 프로젝트에서 두 가지 병목이 예상됩니다.
>
> **첫 번째 병목 - ArchUnit의 ClassFileImporter 성능:** `ArchUnitBasedRule`은 전수 검사 시 `build/classes/java/main` 전체를 `ClassFileImporter`로 로딩합니다. 수만 개의 클래스 파일을 로딩하면 수십 초에서 수 분의 시간이 소요될 수 있으며, ArchUnit은 로딩된 클래스를 JVM 힙에 보관하므로 OOM(Out of Memory) 위험도 있습니다. 해결 방법은 두 가지입니다. 단기적으로는 증분 검사(`affectedFiles` 기반)를 기본값으로 강제하고 CI에서만 전수 검사를 실행합니다. 장기적으로는 ArchUnit의 `ClassFileImporter`에 필터(`importPackages` + `ignoreClassesMatching`)를 적용해 검사 범위를 핵심 패키지로 제한합니다.
>
> **두 번째 병목 - SAX 파싱의 순차 처리:** 현재 `MybatisUnitBasedRule`은 매퍼 XML 파일을 순차적으로 파싱합니다. 수천 개의 파일이 있을 경우 I/O 병목이 됩니다. 단, 앞서 언급한 인스턴스 상태 오염 문제를 먼저 해결해야 병렬화가 가능합니다. 파일별 새 핸들러 인스턴스를 생성하는 구조로 변경한 후, `files.parallel().forEach()`로 전환하거나 `ExecutorService`로 CPU 코어 수에 맞는 스레드 풀을 구성합니다.
>
> **추가 고려 사항 - 증분 검사의 한계:** `GitDiffUtil`이 `git diff HEAD`를 기준으로 변경 파일을 수집하는데, 브랜치 병합(merge)이 잦은 환경에서는 `HEAD` 기준이 아닌 `origin/main` 대비 변경분을 수집해야 실제 PR 변경 범위와 일치합니다. CI에서는 `extension.getRatchetFrom()`에 `origin/main`을 설정하고, 로컬에서는 `HEAD`를 유지하는 이중 전략이 실용적입니다.

</step5_interview_qna>
