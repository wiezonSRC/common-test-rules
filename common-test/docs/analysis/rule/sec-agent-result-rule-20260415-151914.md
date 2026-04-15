# 보안 분석 결과 보고서

- 분析일시: 2026-04-15 15:19:14
- 분析 대상 프로젝트: rule-core + rule-gradle-plugin

---

<step1_code_analysis>

## [코드 목적 요약]

`rule-core` 모듈은 Java 소스코드 및 MyBatis XML Mapper 파일에 대한 정적 규칙 검사 엔진(Rule Engine)입니다. ArchUnit 기반의 Java 아키텍처 규칙(NoFieldInjection, NoGenericCatch, NoSystemOut, TransactionalSwallowException)과 SAX 파서 기반의 MyBatis XML 규칙(NoDollarExpression, MyBatisXml CDATA, MyBatisIf 위치, SqlBasicPerformance)을 실행하고, 결과를 JSON 리포트로 저장합니다.

`rule-gradle-plugin` 모듈은 위 rule-core를 Gradle 플러그인으로 감싸 `./gradlew ruleCheck` 명령으로 CI/CD 파이프라인 및 로컬 개발 환경에서 실행할 수 있도록 통합합니다. Spotless를 통한 코드 포맷팅(Java AOSP 스타일, XML SQL 키워드 대문자화)도 함께 제공합니다.

**기술 스택:** Java 17, ArchUnit 1.3.0, JSQLParser 4.8, ASM 9.6, Jackson 2.21, SAX Parser, Spotless 6.25, Gradle Plugin API

**데이터 민감도:** 이 코드 자체는 금융/개인정보를 직접 처리하지 않습니다. 그러나 다른 PG 도메인 프로젝트의 소스코드 경로, 패키지 구조, Git diff 내용을 입력으로 받아 처리하므로, **소스코드 구조 정보(내부 아키텍처 노출 위험)** 가 간접적 민감 데이터에 해당합니다. 또한 리포트 파일에 위반 위치(파일 경로, 라인 번호)가 기록되어 내부 코드 구조가 외부에 노출될 수 있습니다.

</step1_code_analysis>

---

<step2_architecture_analysis>

## [보안 및 권한 제어 구조 평가]

### 레이어드 아키텍처 관점

이 모듈은 Spring 웹 애플리케이션이 아닌 **라이브러리/플러그인** 형태이므로 Controller-Service-Repository 레이어 구조는 해당되지 않습니다. 대신 `Rule 인터페이스 → ArchUnitBasedRule/MybatisUnitBasedRule → 각 구체 규칙 클래스 → RuleRunner → RuleTask(Gradle)` 의 계층 구조를 가집니다.

- **Rule 인터페이스:** 추상화 계층이 명확하게 분리되어 있으며, 단일 책임 원칙이 비교적 잘 지켜집니다.
- **RuleRunner:** 규칙 실행과 결과 취합, 리포트 생성까지 담당하여 단일 책임이 다소 넓습니다.
- **RuleContext:** 불변 record 타입 사용으로 상태 오염 가능성이 낮습니다.

### 인증/인가 구조

Gradle 플러그인 특성상 별도의 인증/인가 레이어는 불필요합니다. 그러나 `GitDiffUtil`에서 외부 프로세스(git 명령) 실행 시 입력값 검증이 미흡하여 **Command Injection 벡터**가 존재합니다.

### 의존성 주입 방식

`RuleGroups` Enum에서 `new NoSystemOutRule()`, `new NoDollarExpressionRule()` 등을 **Enum 상수 초기화 시 직접 생성(new)** 합니다. Spring DI 컨테이너 외부에서 동작하는 라이브러리이므로 DI 자체는 문제 없지만, **Enum 싱글톤에 상태를 갖는 인스턴스를 공유**하는 패턴이 동시성 위험을 내포합니다(아래 상세 기술).

### 설정 파일 및 민감 정보 관리

하드코딩된 크레덴셜은 발견되지 않았습니다. `RuleExtension`을 통한 외부 설정 방식은 적절합니다. 다만 `baseRef`(Git 참조값)가 Extension에서 그대로 외부 프로세스에 전달되는 경로가 존재합니다.

</step2_architecture_analysis>

---

<step3_code_review>

## [OWASP Top 10 기반 보안 취약점 및 예외 처리 리뷰]

---

### 취약점 #1

```
🚨 [심각도: CRITICAL] - Race Condition / 스레드 비안전 공유 상태 (Enum 싱글톤 + 가변 인스턴스 필드)
- 위치: RuleGroups.java (전체) / MybatisUnitBasedRule.java (필드 선언부)
- 문제점:
  RuleGroups Enum은 JVM 내에서 싱글톤으로 존재합니다.
  JAVA_CRITICAL, SQL_CRITICAL, ALL 각 상수가 초기화 시점에 Rule 구현체
  인스턴스를 생성하여 List<Rule>로 보관합니다.
  MybatisUnitBasedRule의 하위 클래스(NoDollarExpressionRule,
  MyBatisXmlRule, MyBatisIfRule, SqlBasicPerformanceRule)는
  인스턴스 필드로 currentViolations, currentXmlPath, locator, context를
  가변 상태로 유지합니다.

  따라서 두 개 이상의 스레드 또는 Gradle 병렬 빌드(parallel=true)가
  동일한 RuleGroups.SQL_CRITICAL.getRules()를 동시에 사용하면
  동일한 Rule 인스턴스에 대한 경쟁 조건이 발생합니다.
  결과적으로 currentViolations 혼입, 잘못된 파일 경로 기록,
  NullPointerException 등이 발생할 수 있습니다.

- 취약 코드 스니펫:
  // RuleGroups.java
  SQL_CRITICAL(
      new TransactionalSwallowExceptionRule(),  // 싱글톤으로 공유됨
      new NoDollarExpressionRule(),
      ...
  );

  // MybatisUnitBasedRule.java
  protected Path currentXmlPath;              // 가변 인스턴스 필드
  protected List<RuleViolation> currentViolations; // 가변 인스턴스 필드
  protected Locator locator;                  // 가변 인스턴스 필드
  protected RuleContext context;              // 가변 인스턴스 필드

  // SqlBasicPerformanceRule.java
  private final StringBuilder sqlBuffer = new StringBuilder(); // 가변
  private boolean isSqlTag = false;                            // 가변

  // MyBatisIfRule.java
  private final Deque<String> tagStack = new ArrayDeque<>();   // 가변

- 권고 조치:
  RuleGroups에서 Rule 인스턴스를 미리 생성하지 말고,
  check() 호출 시마다 새 인스턴스를 생성하도록 Supplier<Rule> 패턴으로 변경합니다.
  또는 check() 메서드 진입 시 상태 필드를 초기화하고 메서드를 synchronized로 보호합니다.

- 수정 코드 예시:
  // RuleGroups.java - Supplier 패턴으로 변경
  @Getter
  public enum RuleGroups {
      SQL_CRITICAL(
          TransactionalSwallowExceptionRule::new,
          NoDollarExpressionRule::new,
          MyBatisXmlRule::new,
          MyBatisIfRule::new,
          SqlBasicPerformanceRule::new
      );

      private final List<Supplier<Rule>> ruleSuppliers;

      RuleGroups(Supplier<Rule>... suppliers) {
          this.ruleSuppliers = Arrays.asList(suppliers);
      }

      // 호출 시마다 새 인스턴스 반환
      public List<Rule> createRules() {
          return ruleSuppliers.stream()
              .map(Supplier::get)
              .collect(Collectors.toList());
      }
  }

  // RuleRunner 생성자도 변경
  public RuleRunner(RuleGroups group) {
      this.rules.addAll(group.createRules()); // 매번 새 인스턴스
  }

- 관련 OWASP: A04:2021 - Insecure Design
- SonarQube 규칙: squid:S2885 (Non-thread-safe fields used in Singletons)
```

---

### 취약점 #2

```
🚨 [심각도: HIGH] - OS Command Injection (간접 경로)
- 위치: GitDiffUtil.java:executeCommand() / GitDiffUtil.java:getAffectedFiles()
- 문제점:
  외부에서 전달된 baseRef 문자열을 검증 없이 ProcessBuilder 명령 인자로
  직접 사용합니다. ProcessBuilder는 셸을 경유하지 않으므로 셸 메타문자
  (;, |, &&)에 의한 직접적인 command injection은 어렵지만,
  git 명령어 자체가 특정 인자(예: --upload-pack=, --exec=)를 허용하거나
  경로 탐색 패턴(../../etc/passwd 등의 Git ref 형태)이 입력되면
  예상치 못한 동작이 발생할 수 있습니다.
  또한 gradle extension에서 ratchetFrom 값이 그대로 baseRef로 전달되며,
  이 값은 build.gradle 작성자가 자유롭게 설정 가능합니다.

- 취약 코드 스니펫:
  // RuleTask.java
  String baseRef = extension.getRatchetFrom().getOrElse("HEAD");
  affectedFiles = GitDiffUtil.getAffectedFiles(workspaceRoot, baseRef);

  // GitDiffUtil.java
  List<String> diffFiles = executeCommand(projectRoot,
      "git", "diff", "--name-only", targetRef);  // targetRef 미검증

- 권고 조치:
  baseRef 값을 화이트리스트 패턴으로 검증합니다.
  Git ref는 알파벳, 숫자, /, -, _, . 만 허용하는 정규식으로 제한합니다.

- 수정 코드 예시:
  private static final Pattern SAFE_GIT_REF = Pattern.compile(
      "^[a-zA-Z0-9/_\\-\\.~^@\\{\\}]+$"
  );

  public static List<Path> getAffectedFiles(Path projectRoot, String baseRef) {
      String targetRef = (baseRef == null || baseRef.isBlank()) ? "HEAD" : baseRef;
      if (!SAFE_GIT_REF.matcher(targetRef).matches()) {
          log.warn("[GIT] Invalid baseRef pattern detected: {}. Using HEAD.", targetRef);
          targetRef = "HEAD";
      }
      // ... 이후 로직
  }

- 관련 OWASP: A03:2021 - Injection
- SonarQube 규칙: squid:S2076 (OS Command Injection)
```

---

### 취약점 #3

```
🚨 [심각도: HIGH] - Fail-Open 예외 처리 (보안 로직 무력화)
- 위치: TransactionalSwallowExceptionRule.java:55 / MybatisUnitBasedRule.java:64
- 문제점:
  TransactionalSwallowExceptionRule의 notSwallowException() 내부에서
  발생하는 모든 예외가 `catch (Exception ignored) {}` 로 완전히 삼켜집니다.
  이는 ASM 클래스 로딩 실패, IO 오류 등이 발생할 때 해당 메서드 검사를
  조용히 건너뛰게 만듭니다(Fail-Open 패턴).
  보안 검사 도구에서 Fail-Open은 검사 대상을 통과시키는 것과 동일합니다.

  동일한 패턴이 MybatisUnitBasedRule.parseXml() 에서도 반복됩니다.
  XML 파일 파싱 중 오류가 발생하면 해당 파일 전체 검사를 건너뜁니다.
  악의적으로 구성된 XML 파일이 파싱 오류를 유발하여 NoDollarExpressionRule 등의
  검사를 회피하는 수단으로 활용될 수 있습니다.

- 취약 코드 스니펫:
  // TransactionalSwallowExceptionRule.java:55
  } catch (Exception ignored) {}

  // MybatisUnitBasedRule.java:64
  } catch (Exception ignored) {
      // 개별 파일 파싱 에러가 전체 검사를 멈추지 않도록 함
  }

- 권고 조치:
  예외를 삼키지 말고 WARN 등급 위반으로 기록하여 검사 결과에 반영합니다.
  검사 도구 관점에서는 Fail-Closed(오류 발생 시 위반으로 처리)가 더 안전합니다.

- 수정 코드 예시:
  // MybatisUnitBasedRule.java
  private void parseXml(Path xml, SAXParser saxParser) {
      this.currentXmlPath = xml;
      try {
          saxParser.parse(xml.toFile(), this);
      } catch (Exception e) {
          currentViolations.add(new RuleViolation(
              this.getClass().getSimpleName(),
              Status.WARN,
              "XML 파싱 오류로 검사를 건너뜁니다: " + e.getMessage(),
              xml.toAbsolutePath().toString(),
              xml.toString(),
              0
          ));
      }
  }

- 관련 OWASP: A05:2021 - Security Misconfiguration
- SonarQube 규칙: squid:S108 (Empty catch block), squid:S1166 (Exception swallowed)
```

---

### 취약점 #4

```
🚨 [심각도: HIGH] - XXE (XML External Entity) 불완전 방어
- 위치: MybatisUnitBasedRule.java:40-42
- 문제점:
  SAXParserFactory에 external DTD 로딩 차단 설정이 적용되었으나
  XXE 공격을 완전히 방어하려면 추가 피처 설정이 필요합니다.
  현재 코드는 `load-external-dtd` 하나만 비활성화하고 있으며,
  `external-general-entities`, `external-parameter-entities`,
  `disallow-doctype-decl` 설정이 누락되어 있습니다.
  이 코드가 사용자가 제공한 임의의 XML을 파싱하는 데 사용될 경우
  내부 파일 읽기 또는 SSRF 공격에 노출될 수 있습니다.

- 취약 코드 스니펫:
  // MybatisUnitBasedRule.java
  SAXParserFactory factory = SAXParserFactory.newInstance();
  factory.setFeature(
      "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
  );
  SAXParser saxParser = factory.newSAXParser();

- 권고 조치:
  OWASP XXE Prevention Cheat Sheet의 SAX 방어 설정 전체를 적용합니다.

- 수정 코드 예시:
  SAXParserFactory factory = SAXParserFactory.newInstance();
  factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
  factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
  factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
  factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
  // namespace-aware 설정으로 일관성 확보
  factory.setNamespaceAware(false);
  SAXParser saxParser = factory.newSAXParser();

- 관련 OWASP: A05:2021 - Security Misconfiguration (XXE)
- SonarQube 규칙: squid:S2755 (XML parsers should not be vulnerable to XXE attacks)
```

---

### 취약점 #5

```
🚨 [심각도: HIGH] - Path Traversal 가능성 (매퍼 경로 미검증)
- 위치: RuleTask.java:65-72 / MybatisUnitBasedRule.java:49-56
- 문제점:
  RuleExtension.getMapperPaths()로 받은 문자열 경로를 검증 없이
  projectRoot.resolve()를 통해 파일시스템 경로로 변환합니다.
  `../../etc` 같은 경로 탐색 패턴이 입력될 경우 projectRoot 바깥의
  파일을 읽을 수 있습니다.
  로컬 개발 환경에서는 영향이 제한적이지만, 플러그인이 CI 서버에서
  외부 파라미터를 받아 실행될 경우 서버 내 민감 파일이 SAX 파서에
  의해 처리될 수 있습니다.

- 취약 코드 스니펫:
  // RuleTask.java
  mapperDirs = customMapperPaths.stream()
      .map(projectRoot::resolve)   // "../../../etc" 등 미검증
      .filter(p -> p.toFile().exists())
      ...

- 권고 조치:
  resolve된 경로가 projectRoot 하위에 있는지 normalize() 후 startsWith()로 검증합니다.

- 수정 코드 예시:
  Path normalizedProject = projectRoot.toAbsolutePath().normalize();
  mapperDirs = customMapperPaths.stream()
      .map(p -> normalizedProject.resolve(p).normalize())
      .filter(resolved -> {
          if (!resolved.startsWith(normalizedProject)) {
              getLogger().warn("[RuleTask] Path traversal attempt blocked: {}", resolved);
              return false;
          }
          return resolved.toFile().exists();
      })
      .collect(Collectors.toList());

- 관련 OWASP: A01:2021 - Broken Access Control (Path Traversal)
- SonarQube 규칙: squid:S2083 (I/O function calls should not be vulnerable to path injection attacks)
```

---

### 취약점 #6

```
🚨 [심각도: MEDIUM] - Report 파일 경로 미정규화로 인한 Path Traversal
- 위치: Report.java:101 / RuleTask.java:97
- 문제점:
  saveReport() 메서드에서 outputDir.resolve(fileName)에
  toAbsolutePath().normalize()가 호출되지만, outputDir 자체가
  외부에서 주입된 경로(projectRoot 기반)이므로 fileName에
  경로 구분자가 포함될 경우 의도치 않은 경로에 파일이 생성될 수 있습니다.
  현재 fileName은 내부적으로 생성되므로 직접 위험도는 낮지만,
  향후 fileName을 외부화할 경우 취약점이 됩니다.

- 취약 코드 스니펫:
  // Report.java
  Path path = outputDir.resolve(fileName).toAbsolutePath().normalize();
  Files.write(path, ...);

- 권고 조치:
  outputDir 자체도 초기화 시 normalize하고, 최종 경로가
  outputDir 하위임을 검증하는 Guard를 추가합니다.

  Path normalizedOutput = outputDir.toAbsolutePath().normalize();
  Path path = normalizedOutput.resolve(fileName).normalize();
  if (!path.startsWith(normalizedOutput)) {
      throw new SecurityException("Report path escapes output directory: " + path);
  }

- 관련 OWASP: A01:2021 - Broken Access Control
- SonarQube 규칙: squid:S2083
```

---

### 취약점 #7

```
🚨 [심각도: MEDIUM] - ProcessBuilder 종료 코드 미검증 및 stderr 미처리
- 위치: GitDiffUtil.java:84-85
- 문제점:
  process.waitFor()의 반환값(exit code)을 확인하지 않습니다.
  git 명령어가 실패(non-zero exit)해도 정상 처리된 것으로 간주하고
  빈 결과를 반환합니다. 또한 stderr 스트림을 소비하지 않아
  일부 환경에서 프로세스 버퍼가 꽉 차 waitFor()가 무한 대기에
  빠질 수 있습니다(프로세스 교착 상태).

- 취약 코드 스니펫:
  process.waitFor();  // exit code 무시
  // stderr 미수집

- 권고 조치:
  ProcessBuilder에 redirectErrorStream(true) 설정 또는
  별도 스레드로 stderr 소비, exitCode 검증 로직을 추가합니다.

- 수정 코드 예시:
  Process process = new ProcessBuilder(command)
      .directory(workingDir.toFile())
      .redirectErrorStream(true)  // stderr를 stdout에 병합
      .start();

  // ... 결과 수집 ...

  int exitCode = process.waitFor();
  if (exitCode != 0) {
      throw new RuntimeException(
          "Git command failed with exit code " + exitCode + ": " + Arrays.toString(command)
      );
  }

- 관련 OWASP: A05:2021 - Security Misconfiguration
- SonarQube 규칙: squid:S2142 (Process.waitFor() should handle InterruptedException)
```

---

### 취약점 #8

```
🚨 [심각도: MEDIUM] - Report 클래스의 System.out/err 직접 사용
- 위치: Report.java:75, 80, 85, 93, 103, 106 / GitDiffUtil.java:54
- 문제점:
  Report 클래스와 GitDiffUtil이 System.out.println() 및
  System.err.println()을 직접 사용합니다. 이는 코드 자신이 강제하는
  NoSystemOutRule 규칙에 위반되는 자기 모순적 상황입니다.
  또한 Slf4j 없이 직접 출력하므로 로그 레벨 제어, 마스킹, 집계가
  불가능합니다. 에러 메시지에 파일 경로가 포함되어 내부 구조가
  노출될 수 있습니다.

- 취약 코드 스니펫:
  // Report.java
  System.out.println("\n[RULE CHECK] No violations found. Well done!\n");
  System.err.println("[REPORT] Failed to write report: " + e.getMessage());

  // GitDiffUtil.java
  System.err.println("[GIT] Failed to get diff for " + baseRef + ": " + e.getMessage());

- 권고 조치:
  @Slf4j 어노테이션을 적용하고 log.info(), log.warn(), log.error()로 전환합니다.
  Report 클래스는 Gradle 플러그인 API의 Logger를 주입받아 사용하는 것이
  더욱 적합합니다.

- 관련 OWASP: A09:2021 - Security Logging and Monitoring Failures
- SonarQube 규칙: squid:S106 (Standard outputs should not be used directly to log anything)
```

---

### 취약점 #9

```
🚨 [심각도: MEDIUM] - NoSystemOutRule의 과도한 범위 (false positive 위험)
- 위치: NoSystemOutRule.java:11-13
- 문제점:
  `ArchRuleDefinition.noClasses().should().accessClassesThat().belongToAnyOf(System.class)`
  는 System.out 뿐만 아니라 System.exit(), System.getenv(),
  System.currentTimeMillis(), System.arraycopy() 등 System 클래스의
  모든 접근을 금지합니다. 이는 의도보다 훨씬 넓은 범위이며,
  System.getProperty() 같은 정상적인 사용 패턴도 FAIL 처리됩니다.
  검사 도구의 신뢰도를 저하시키는 False Positive를 다수 발생시킵니다.

- 취약 코드 스니펫:
  return ArchRuleDefinition.noClasses()
      .should().accessClassesThat().belongToAnyOf(System.class)
      .as(" System.out 일반 출력 금지 ");

- 권고 조치:
  System.out 및 System.err 필드에 대한 접근만 금지하도록 규칙을 정교화합니다.

- 수정 코드 예시:
  return ArchRuleDefinition.noClasses()
      .should(new ArchCondition<JavaClass>("not use System.out or System.err") {
          @Override
          public void check(JavaClass javaClass, ConditionEvents events) {
              javaClass.getAccessesFromSelf().stream()
                  .filter(access -> access.getTargetOwner().isAssignableTo(System.class))
                  .filter(access -> access.getName().equals("out")
                      || access.getName().equals("err"))
                  .forEach(access -> events.add(
                      SimpleConditionEvent.violated(javaClass,
                          "System.out/err 사용 금지: " + access.getDescription())
                  ));
          }
      });

- 관련 OWASP: A05:2021 - Security Misconfiguration (over-broad policy)
- SonarQube 규칙: squid:S106
```

---

### 취약점 #10

```
🚨 [심각도: MEDIUM] - TransactionalSwallowExceptionRule 메서드 시그니처 기반 매칭 취약점
- 위치: TransactionalSwallowExceptionRule.java:44
- 문제점:
  ASM MethodNode와 ArchUnit JavaMethod를 매칭할 때 메서드 이름(mn.name)만
  비교하고 파라미터 디스크립터(mn.desc)를 비교하지 않습니다.
  동일한 클래스에 메서드 오버로딩이 있을 경우 의도하지 않은 메서드를
  검사하거나 대상 메서드를 누락할 수 있습니다.
  예: save(String) 와 save(Long) 이 모두 @Transactional이면
  첫 번째로 발견된 "save" 메서드만 분석되고 나머지는 건너뜁니다.

- 취약 코드 스니펫:
  if (mn.name.equals(method.getName())   // 이름만 비교, desc 미비교
      && mn.tryCatchBlocks != null
      && !mn.tryCatchBlocks.isEmpty()) {

- 권고 조치:
  ArchUnit JavaMethod에서 descriptor를 추출하여 ASM MethodNode.desc와 함께 비교합니다.

- 수정 코드 예시:
  String methodDescriptor = method.reflect().getDescriptor();
  // ASM과 ArchUnit의 descriptor 포맷 통일 후:
  if (mn.name.equals(method.getName())
      && mn.desc.equals(methodDescriptor)
      && mn.tryCatchBlocks != null
      && !mn.tryCatchBlocks.isEmpty()) {

- 관련 OWASP: A05:2021 - Security Misconfiguration (rule evasion)
- SonarQube 규칙: squid:S2184 (Logic error)
```

---

### 취약점 #11

```
🚨 [심각도: LOW] - RuleContext.Builder에서 null 허용으로 인한 NPE 위험
- 위치: RuleContext.java:57-59
- 문제점:
  Builder.build()에서 필수 필드(basePackage, projectRoot, workspaceRoot)의
  null 검증이 없습니다. 사용자가 basePackage를 생략한 채 build()를 호출하면
  ArchUnitBasedRule.check()의 importPackages(null) 호출 시
  NullPointerException이 발생합니다.
  RuleTask에서는 extension.getBasePackage().isPresent() 체크가 있지만,
  rule-core를 직접 사용하는 경우에는 보호 장치가 없습니다.

- 취약 코드 스니펫:
  public RuleContext build() {
      return new RuleContext(basePackage, projectRoot, workspaceRoot,
          mapperDirs, affectedFiles);
      // basePackage, projectRoot, workspaceRoot null 허용
  }

- 권고 조치:
  Objects.requireNonNull()로 필수 필드를 검증합니다.

- 수정 코드 예시:
  public RuleContext build() {
      Objects.requireNonNull(projectRoot, "projectRoot is required");
      Objects.requireNonNull(workspaceRoot, "workspaceRoot is required");
      return new RuleContext(basePackage, projectRoot, workspaceRoot,
          mapperDirs, affectedFiles);
  }

- 관련 OWASP: A03:2021 - Injection (NPE로 인한 예외 정보 노출)
- SonarQube 규칙: squid:S2637 (NullPointerException prevention)
```

---

### 취약점 #12

```
🚨 [심각도: LOW] - MyBatisXmlRule의 정규식이 CDATA 섹션 내부도 검사
- 위치: MyBatisXmlRule.java:27-30
- 문제점:
  SAX 파서의 characters() 콜백은 CDATA 섹션도 일반 텍스트와 동일하게
  전달합니다(DefaultHandler는 startCDATA/endCDATA를 구분하지 않음).
  따라서 올바르게 `<![CDATA[age < 30]]>` 로 감싼 코드도
  MyBatisXmlRule에 의해 FAIL로 오탐될 수 있습니다.
  CDATA 내부 상태를 추적하여 검사에서 제외해야 합니다.

- 취약 코드 스니펫:
  @Override
  public void characters(char[] ch, int start, int length){
      String text = new String(ch, start, length);
      if (hasRawInequality(text)) {  // CDATA 내부도 FAIL 처리됨
          ...
      }
  }

- 권고 조치:
  LexicalHandler를 구현하여 CDATA 구간을 추적하고,
  CDATA 내부에서는 규칙 검사를 건너뜁니다.

- 수정 코드 예시:
  // DefaultHandler + LexicalHandler 구현
  private boolean insideCData = false;

  @Override
  public void startCDATA() {
      insideCData = true;
  }

  @Override
  public void endCDATA() {
      insideCData = false;
  }

  @Override
  public void characters(char[] ch, int start, int length) {
      if (insideCData) return;  // CDATA 내부 스킵
      String text = new String(ch, start, length);
      if (hasRawInequality(text)) { ... }
  }

- 관련 OWASP: A05:2021 - Security Misconfiguration (false positive로 인한 신뢰도 저하)
- SonarQube 규칙: squid:S5852 (Regex engine performance / correctness)
```

---

### 취약점 #13

```
🚨 [심각도: LOW] - Jackson ObjectMapper 인스턴스 공유 (스레드 안전은 하나 설정 변경 위험)
- 위치: Report.java:22
- 문제점:
  ObjectMapper는 스레드 안전하게 설계되어 있으나, 외부 코드가
  동일 인스턴스를 참조하여 configure() 또는 registerModule()을
  호출하면 전역 설정이 변경됩니다.
  현재 코드에서는 Report가 외부에 objectMapper를 노출하지 않아
  직접적 위험은 낮지만, 인스턴스 필드로 두는 것보다
  지역 변수 또는 static final 상수로 관리하는 것이 더 안전합니다.

- 취약 코드 스니펫:
  private final ObjectMapper objectMapper = new ObjectMapper();

- 권고 조치:
  static final로 선언하거나 메서드 내 지역 변수로 사용합니다.

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

- 관련 OWASP: A04:2021 - Insecure Design
- SonarQube 규칙: squid:S2885
```

---

### 취약점 #14 (Code Smell)

```
🚨 [심각도: INFO] - 테스트 XML에 실제 ${}(SQL Injection 패턴) 존재
- 위치: BadSqlMapper.xml:21
- 문제점:
  테스트 용도의 BadSqlMapper.xml에 `${status}` 패턴이 존재합니다.
  이 파일이 테스트 소스 경로에 있으나, RuleTask가 affectedFiles로
  이 경로를 포함하면 FAIL 위반으로 정상 감지됩니다.
  그러나 이 파일은 의도적으로 나쁜 패턴을 담은 테스트 픽스처이므로,
  검사 제외 대상으로 명시적으로 문서화하거나 별도 경로로 분리해야 합니다.
  현재는 암묵적으로 mapperDirs 스캔 경로에서 제외되지만
  affectedFiles 모드에서는 포함될 수 있습니다.

- 권고 조치:
  테스트 픽스처 파일임을 XML 주석으로 명시하고,
  RuleContext 빌드 시 테스트 경로를 제외하는 필터를 추가합니다.

- 관련 OWASP: A05:2021 - Security Misconfiguration
- SonarQube 규칙: squid:S1068 (Dead code / unused patterns)
```

---

### 취약점 #15 (Code Smell)

```
🚨 [심각도: INFO] - CLAUDE.md 컨벤션 위반: @Slf4j 미사용, System.out/err 남용
- 위치: Report.java (전체), GitDiffUtil.java (전체)
- 문제점:
  프로젝트 코딩 컨벤션(CLAUDE.md)에서 System.out.println 금지 및
  @Slf4j 사용을 명시하고 있으나, 이 두 클래스가 직접 이를 위반합니다.
  특히 rule-core 라이브러리가 플러그인 형태로 배포될 때 System.out은
  Gradle 로그 체계와 통합되지 않아 출력이 누락되거나 순서가 깨질 수 있습니다.

- 권고 조치:
  Gradle 플러그인 컨텍스트에서는 Logger를 주입받아 사용하거나,
  rule-core 내부에서는 Slf4j Logger를 사용합니다.
  Report 생성자에 Logger를 주입하는 방식을 권장합니다.

- 관련 OWASP: A09:2021 - Security Logging and Monitoring Failures
- SonarQube 규칙: squid:S106
```

</step3_code_review>

---

<step4_pros_and_cons>

## [안전성 측면의 장단점]

### 잘 구현된 보안 요소 (Pros)

1. **XXE 부분 방어 적용:** `MybatisUnitBasedRule`에서 `load-external-dtd` 피처를 비활성화하여 가장 기본적인 XXE 벡터를 차단한 점은 긍정적입니다. 완전하지 않지만 의식적인 보안 처리가 이루어졌습니다.

2. **RuleContext 불변 설계:** `RuleContext`를 Java Record 타입으로 정의하여 한 번 생성된 검사 컨텍스트가 변조될 수 없도록 불변성을 확보했습니다. Builder 패턴으로 안전한 생성을 유도한 것도 좋은 설계입니다.

3. **NoDollarExpressionRule SQL Injection 탐지:** MyBatis `${}` 패턴을 SAX 파서 기반으로 정확히 탐지하여 SQL Injection 위험을 사전에 차단하는 규칙이 구현되어 있습니다. 이는 PG 도메인에서 매우 중요한 방어 레이어입니다.

4. **TransactionalSwallowExceptionRule의 ASM 기반 바이트코드 분석:** 단순 소스코드 정규식 매칭이 아닌 실제 바이트코드를 분석하여 @Transactional 메서드의 예외 삼킴을 탐지합니다. 트랜잭션 롤백 누락으로 인한 금융 데이터 정합성 오류를 방지하는 정교한 구현입니다.

5. **Path Traversal 부분 방어:** `ArchUnitBasedRule.resolveClassPath()`에서 `/src/main/java/` 경로를 split하여 예상 경로 외의 클래스 파일 접근을 부분적으로 제한하고 있습니다.

6. **RuleViolation의 절대경로/상대경로 분리:** JSON 리포트에는 상대경로(`@JsonProperty("filePath")`), IDE 링크에는 절대경로를 사용하고 `@JsonIgnore`로 절대경로의 JSON 직렬화를 차단한 것은 내부 구조 노출을 최소화하는 좋은 설계입니다.

### 보완이 필요한 취약 요소 (Cons)

**[즉시 수정 필요]**
- **CRITICAL: Enum 싱글톤 공유 가변 상태 (Race Condition)** - 병렬 Gradle 빌드 또는 테스트 병렬 실행 시 currentViolations 혼입 및 NPE 발생 가능. Rule 인스턴스를 Supplier 패턴으로 전환하여 매번 새 인스턴스 생성 필요.
- **HIGH: Fail-Open 예외 처리** - TransactionalSwallowExceptionRule과 parseXml()에서 모든 예외를 무음으로 삼켜 검사 대상을 통과시키는 Fail-Open 패턴. 보안 검사 도구에서 치명적인 설계 결함.
- **HIGH: XXE 불완전 방어** - external-general-entities, external-parameter-entities 등 추가 방어 설정 누락.

**[단기 개선]**
- **HIGH: GitDiffUtil baseRef 입력값 미검증** - ProcessBuilder 명령 인자로 전달되는 외부 입력값 화이트리스트 검증 필요.
- **HIGH: 매퍼 경로 Path Traversal 미방어** - 사용자 제공 경로의 projectRoot 하위 여부 검증 필요.
- **MEDIUM: System.out/err 직접 사용** - 자체 규칙(NoSystemOutRule)을 위반하는 자기 모순, Slf4j 전환 필요.
- **MEDIUM: NoSystemOutRule 과도한 금지 범위** - System 클래스 전체 금지로 인한 대규모 False Positive 발생 가능.

**[장기 개선]**
- **MEDIUM: TransactionalSwallowExceptionRule 메서드 오버로딩 매칭 오류** - 메서드 descriptor 비교 추가.
- **LOW: MyBatisXmlRule CDATA 오탐** - LexicalHandler로 CDATA 구간 추적 필요.
- **LOW: RuleContext Builder null 검증 미흡** - 필수 필드 requireNonNull 추가.

### 종합 보안 점수

**55 / 100점**

이 코드는 코드 품질 강제 도구로서 보안 의식이 반영된 구조(불변 컨텍스트, XXE 부분 방어, SQL Injection 탐지 규칙)를 갖추고 있습니다. 그러나 Enum 싱글톤에 가변 상태를 보관하는 Race Condition, Fail-Open 예외 처리 패턴, XXE 불완전 방어라는 세 가지 HIGH 이상 취약점이 복합적으로 존재하여 병렬 환경에서의 신뢰도와 보안 강도를 크게 저하시킵니다. 특히 이 도구가 다른 프로젝트의 보안을 검사하는 메타 도구라는 점에서, 도구 자체의 보안 결함은 검사 결과의 신뢰성 전체를 훼손합니다.

</step4_pros_and_cons>

---

<step5_interview_qna>

## [시큐어 코딩 및 스레드 안전성 관련 꼬리 질문 3개와 모범 답변]

---

```
Q1. RuleGroups Enum이 Rule 구현체 인스턴스를 싱글톤으로 보유할 때,
    MybatisUnitBasedRule의 currentViolations가 인스턴스 필드인 경우
    어떤 Race Condition 시나리오가 발생할 수 있으며, 이를 Supplier 패턴이
    아닌 다른 방법으로도 해결할 수 있다면 어떤 트레이드오프가 있습니까?

A1.
- 핵심 개념 설명:
  Java의 Enum 인스턴스는 JVM 내에서 딱 하나만 존재하는 싱글톤입니다.
  Thread A와 Thread B가 동시에 동일한 NoDollarExpressionRule 인스턴스의
  check()를 호출하면 this.currentViolations = allViolations 라인에서
  Thread A가 설정한 List를 Thread B가 덮어쓸 수 있습니다.
  결과적으로 Thread A의 위반 사항이 Thread B의 List에 추가되거나,
  Thread A가 빈 List를 반환하는 데이터 경쟁이 발생합니다.

- 이 코드에 적용했을 경우의 예시:
  Gradle parallel=true 환경에서 두 서브프로젝트가 동시에 ruleCheck를 실행하면
  RuleGroups.ALL.getRules()가 반환하는 동일한 NoDollarExpressionRule 인스턴스를
  공유합니다. A 프로젝트의 mapper1.xml 위반이 B 프로젝트 리포트에 섞이거나,
  synchronized 없이 ArrayList에 동시 add()가 발생하면
  ArrayIndexOutOfBoundsException이 발생할 수 있습니다.

- 대안 비교:
  1) Supplier 패턴(권장): 호출 시마다 새 인스턴스 생성. 가장 단순하고 안전.
     트레이드오프: 매 실행마다 객체 생성 비용(SAXParser 초기화 포함).
  2) synchronized check() 메서드: 순차 실행으로 경쟁 방지.
     트레이드오프: 병렬 처리 이점 소멸, 성능 저하.
  3) ThreadLocal<List<RuleViolation>>으로 violations를 스레드별 격리.
     트레이드오프: 복잡도 증가, ThreadLocal 누수 위험(clear() 필수).
  4) check() 내부에서만 상태 사용 후 즉시 정리(reset()).
     트레이드오프: reset() 호출 누락 시 여전히 위험, 구조적 보장 없음.
  Supplier 패턴이 가장 명시적이고 불변 설계 원칙에 부합합니다.
```

---

```
Q2. TransactionalSwallowExceptionRule에서 모든 검사 예외를 
    `catch (Exception ignored) {}` 로 처리하는 Fail-Open 패턴이
    보안 관점에서 왜 위험한지, 그리고 보안 검사 도구에서 Fail-Open과
    Fail-Closed 중 어느 것이 더 적합한지 논거와 함께 설명하세요.

A2.
- 핵심 개념 설명:
  Fail-Open은 오류 발생 시 검사를 통과시키는 정책입니다.
  Fail-Closed는 오류 발생 시 검사를 실패(또는 미결)로 처리합니다.
  일반적인 비즈니스 로직에서는 가용성을 위해 Fail-Open이 적합할 수 있지만,
  보안 게이트 역할을 하는 검사 도구에서는 Fail-Closed가 원칙입니다.
  이는 "알 수 없는 경우 허용하지 않는다"는 Default Deny 원칙과 동일합니다.

- 이 코드에 적용했을 경우의 예시:
  TransactionalSwallowExceptionRule이 ASM으로 클래스를 분석할 때
  내부 클래스, 람다, 제네릭 등 복잡한 바이트코드 구조에서 예외가 발생하면
  해당 @Transactional 메서드 전체 검사를 건너뜁니다.
  PG 도메인에서 결제 처리 메서드가 @Transactional을 갖고 예외를 삼켜
  트랜잭션 롤백이 되지 않더라도, 검사 도구가 예외를 삼키는 바람에
  이를 감지하지 못하고 CI를 통과시키는 상황이 발생합니다.
  즉, 규칙의 목적 자체(예외 삼킴 탐지)가 예외 삼킴으로 무력화됩니다.

- 올바른 구현 방법:
  보안 검사 도구는 다음 세 가지 결과만 반환해야 합니다:
  1) PASS: 검사 완료, 위반 없음
  2) FAIL: 검사 완료, 위반 발견
  3) ERROR/WARN: 검사 불가(오류 발생) - 이 경우 CI를 통과시켜서는 안 됨

  catch 블록에서 Status.WARN의 RuleViolation을 추가하거나,
  failOnViolation이 true일 때 Status.WARN도 빌드 실패로 처리해야 합니다.
  최소한 검사 불가 상태를 리포트에 기록하여 운영자가 인지할 수 있어야 합니다.
```

---

```
Q3. GitDiffUtil.getAffectedFiles()에서 사용자 제공 baseRef 값을
    ProcessBuilder 인자로 전달할 때, ProcessBuilder는 셸을 사용하지 않으므로
    전통적인 Shell Injection은 불가능합니다. 그럼에도 불구하고 이 코드가
    보안 취약점을 가질 수 있는 구체적인 공격 시나리오를 설명하고,
    어떤 방어 전략이 가장 효과적인지 설명하세요.

A3.
- 핵심 개념 설명:
  ProcessBuilder는 각 인자를 분리된 배열로 OS에 전달하므로
  `; rm -rf /` 같은 셸 메타문자 주입은 불가능합니다.
  그러나 ProcessBuilder를 통해 실행되는 프로그램(git) 자체가
  특정 인자 형식에 대해 예상치 못한 동작을 할 수 있습니다.

- 구체적 공격 시나리오:
  1) Git 옵션 주입: baseRef를 `--upload-pack=malicious_script` 형태로 설정하면
     git diff 명령의 --upload-pack 옵션으로 해석될 수 있습니다.
     이는 임의 명령 실행으로 이어질 수 있습니다(CVE-2017-1000117 유사 패턴).
     
  2) 경로 탐색 형태의 Git Ref: `../../.git/refs/heads/main` 형태의 ref는
     git이 의도치 않은 경로의 ref 파일을 읽도록 유도할 수 있습니다.
     
  3) Hook 트리거: 특정 Git 작업(예: git diff 특정 형태)이 .git/hooks를
     트리거하는 경우, 공격자가 제어하는 리포지토리에서 임의 코드가 실행됩니다.
     
  4) 대용량 출력으로 인한 DoS: baseRef를 매우 광범위한 범위로 설정하면
     git diff가 수백만 라인을 반환하여 메모리 고갈을 유발합니다.

- 가장 효과적인 방어 전략 (계층적 적용):
  1차: 화이트리스트 정규식 검증
     `^[a-zA-Z0-9/_\\-\\.~^@]+$` 패턴만 허용하여 특수문자(--옵션 형식) 차단.
  
  2차: `--` 인자 삽입으로 옵션/경로 구분
     `git diff --name-only -- targetRef` 형식으로 호출.
     `--` 이후 인자는 git이 무조건 파일/ref로 해석하여 옵션 주입 불가.
  
  3차: 출력 크기 제한
     BufferedReader에서 최대 라인 수(예: 10,000)를 제한하여 DoS 방어.
  
  4차: git 실행 파일 경로 고정
     PATH 환경변수가 변조된 경우를 대비해 /usr/bin/git 같이 절대경로 사용.

  수정 코드 예시:
  private static List<String> executeCommand(Path workingDir, String... command) {
      Process process = new ProcessBuilder(command)
          .directory(workingDir.toFile())
          .redirectErrorStream(true)
          .start();
      
      List<String> result = new ArrayList<>();
      int lineCount = 0;
      try (BufferedReader reader = new BufferedReader(
              new InputStreamReader(process.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null && lineCount < 10_000) {
              if (!line.isBlank()) {
                  result.add(line.trim());
                  lineCount++;
              }
          }
      }
      int exitCode = process.waitFor();
      if (exitCode != 0) {
          throw new RuntimeException("Git command failed: " + exitCode);
      }
      return result;
  }
```

</step5_interview_qna>
