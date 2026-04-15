# 코드 리뷰 분석 결과

- 분析일시: 2026-04-15 15:19:14
- 분析 대상 프로젝트: rule-core + rule-gradle-plugin

---

<step1_code_analysis>

## 🔍 Step 1. 코드 핵심 로직 파악

### 전체 아키텍처 목적

이 프로젝트는 **사내 코드 품질 자동화 도구**입니다. 두 모듈이 역할을 분리하여 협력합니다.

- `rule-core`: 실제 규칙 검사 엔진 라이브러리 (도메인 로직)
- `rule-gradle-plugin`: Gradle 빌드 시스템과 연결하는 어댑터 레이어

### rule-core 핵심 흐름

```
RuleGroups (Enum) → RuleRunner → Rule[] → check(RuleContext) → RuleViolation[]
                                                                      ↓
                                                                   Report
```

1. **Rule (인터페이스)**: `check(RuleContext): List<RuleViolation>` 하나의 계약만 정의합니다.
2. **RuleContext (Record + Builder)**: 검사에 필요한 환경 정보(패키지, 경로, 영향받은 파일 목록)를 불변 객체로 담습니다.
3. **RuleGroups (Enum)**: 규칙들을 논리적 묶음(`JAVA_CRITICAL`, `SQL_CRITICAL`, `ALL`)으로 관리합니다. Enum 생성자가 Rule 인스턴스를 직접 보유합니다.
4. **RuleRunner**: 등록된 모든 규칙을 순차 실행하고 결과를 취합합니다. 개별 규칙 실패가 전체 실행을 멈추지 않도록 예외를 흡수하는 방어 로직이 있습니다.

#### Java 규칙 계층 (ArchUnit 기반)

`ArchUnitBasedRule` (추상 클래스) → `NoFieldInjectionRule`, `NoGenericCatchRule`, `NoSystemOutRule`, `TransactionalSwallowExceptionRule`

- ArchUnit의 `ClassFileImporter`로 `.class` 바이트코드를 로딩하여 검사합니다.
- **증분 검사 모드**: `affectedFiles`가 있으면 변경된 `.java` 파일에 대응하는 `.class`만 임포트합니다.
- **전수 검사 모드**: `build/classes/java/main`을 직접 스캔합니다.
- `TransactionalSwallowExceptionRule`은 ArchUnit에서 포착한 메서드를 ASM 바이트코드로 재분석하여 예외 삼킴(swallowing) 여부를 바이트코드 레벨에서 정밀 판단합니다. (2중 분석 구조)

#### SQL 규칙 계층 (SAX 기반)

`MybatisUnitBasedRule` (추상 클래스, DefaultHandler 상속) → `NoDollarExpressionRule`, `MyBatisIfRule`, `MyBatisXmlRule`, `SqlBasicPerformanceRule`

- SAX 이벤트 드리븐 방식으로 MyBatis XML을 메모리 효율적으로 파싱합니다.
- `SqlBasicPerformanceRule`은 `endElement`에서 SQL 텍스트를 수집한 뒤 JSQLParser로 AST를 구성, `SELECT *`/`SELECT t.*`/스칼라 서브쿼리를 감지합니다.

### rule-gradle-plugin 핵심 흐름

```
RulePlugin.apply()
  ├─ RuleExtension 등록 (convention 기본값 설정)
  ├─ RuleTask 등록 → check 태스크에 dependsOn
  ├─ checkAll 태스크 등록
  └─ (afterEvaluate) Spotless 설정 (조건부)

RuleTask.execute()
  ├─ 증분/전수 모드 결정
  ├─ GitDiffUtil.getAffectedFiles() → affectedFiles 수집
  ├─ mapperDirs 수집
  ├─ RuleContext 빌드
  ├─ RuleRunner 실행
  └─ failOnViolation 옵션에 따라 GradleException 발생
```

- Spotless(구글 Java 포맷터 + Eclipse WTP XML 포맷터)를 내장하여 포맷팅과 규칙 검사를 하나의 플러그인으로 제공합니다.
- XML 포맷터에는 SQL 키워드 대문자 변환 커스텀 스텝이 정규식으로 구현되어 있습니다.

</step1_code_analysis>

<step2_architecture_analysis>

## 📐 Step 2. 알고리즘 / 자료구조 복잡도 분석

### 사용된 알고리즘 및 자료구조

| 구성 요소 | 사용 알고리즘/자료구조 |
|---|---|
| RuleRunner | Sequential scan (List 순회) |
| ArchUnitBasedRule | ClassFileImporter (파일 시스템 탐색 + 바이트코드 파싱) |
| MybatisUnitBasedRule | SAX 이벤트 파싱 (스트리밍) |
| MyBatisIfRule | ArrayDeque (태그 중첩 추적 스택) |
| SqlBasicPerformanceRule | JSQLParser (LL(*) 파서, AST 구성) |
| TransactionalSwallowExceptionRule | ASM Tree API (바이트코드 선형 순회) |
| ArchUnitBasedRule.findFile | Files.walk (DFS 파일 시스템 탐색) |
| RulePlugin (Spotless XML) | Regex (NFA 기반 패턴 매칭) |
| GitDiffUtil | OS 프로세스 실행 (git 명령) |

### 시간 복잡도 (Time Complexity)

- **전체 실행 (전수 검사 모드)**: O(R × (C + F × L))
  - R: 등록된 규칙 수
  - C: 클래스 파일 수 (ArchUnit 기반 규칙)
  - F: XML 파일 수
  - L: 파일당 평균 라인 수

- **주요 연산별 분석**:
  - `ArchUnitBasedRule.check()`: O(C) — ClassFileImporter가 build 디렉토리를 선형 스캔 후 ArchUnit 내부적으로 규칙 평가
  - `ArchUnitBasedRule.findFile()`: **O(N)** — `Files.walk(projectRoot)` 전체 파일 트리를 매 위반 이벤트마다 순회. **규칙 위반이 M건 발생하면 O(M × N)이 되는 핫스팟**
  - `MybatisUnitBasedRule.check()`: O(F × L) — 전체 XML을 스트리밍
  - `SqlBasicPerformanceRule.analyzeSql()`: O(L × T) — JSQLParser LL(*) 파서, L: SQL 길이, T: 토큰 수
  - `TransactionalSwallowExceptionRule.isSwallowing()`: O(I) — try 블록당 명령어 수 I만큼 선형 순회
  - `RulePlugin.uppercaseSqlKeywords()`: O(N × K) — 파일 내용 N 글자 × 정규식 패턴 K개 분기

### 공간 복잡도 (Space Complexity)

- **전체**: O(C + F × L)
  - ArchUnit ClassFileImporter가 모든 클래스 파일을 메모리에 로딩: O(C)
  - SAX는 스트리밍이므로 DOM 전체를 메모리에 올리지 않음: O(현재 파싱 중인 요소)
  - `SqlBasicPerformanceRule.sqlBuffer` (StringBuilder): O(L) — 하나의 `<select>` 블록 크기

- **상세 분석**:
  - ArchUnit은 `JavaClasses` 객체에 전체 클래스를 보유하므로 클래스 수에 비례하는 힙 사용 발생
  - `MyBatisIfRule.tagStack` (ArrayDeque): O(D) — D는 XML 중첩 깊이

### 최적화 가능성

1. **findFile() O(M×N) 문제 (가장 중요)**: `ArchUnitBasedRule.check()` 내에서 위반 이벤트 하나당 `Files.walk(projectRoot)`를 독립적으로 호출합니다. 파일 탐색 결과를 `Map<String, Path>` (파일명 → 경로)로 **1회 캐싱**하면 O(M×N) → O(N + M)으로 개선됩니다.

2. **RuleGroups Enum 중복 인스턴스**: `ALL` 그룹이 `JAVA_CRITICAL`과 `SQL_CRITICAL`의 모든 규칙을 재선언합니다. 두 그룹을 조합하여 ALL을 구성하면 유지보수 단일 수정 포인트를 확보할 수 있습니다.

3. **SAX 파서 재사용 불가 문제**: `check()` 호출마다 `SAXParserFactory`와 `SAXParser`를 새로 생성합니다. `SAXParser`는 스레드 안전하지 않아 재사용이 어렵지만, `SAXParserFactory`는 상위에서 공유할 수 있습니다.

</step2_architecture_analysis>

<step3_code_review>

## 🧐 Step 3. 가독성 & 엣지 케이스 리뷰

### 가독성 평가

**긍정적인 점**
- `RuleContext`를 `record` + 내부 `Builder`로 구현한 것은 Java 17 모던 스타일을 잘 활용했습니다.
- `RuleGroups` Enum이 규칙 그룹을 선언적으로 관리하여 새 그룹 추가가 직관적입니다.
- `RuleViolation`에서 `filePath`(절대경로, IDE 클릭용)와 `relativePath`(JSON 출력용)를 Jackson 애노테이션으로 분리한 설계는 용도별 직렬화 요구사항을 명확히 인지한 흔적입니다.
- `RulePlugin` 내 `RuleTaskName` 내부 Enum은 마법 문자열(magic string)을 제거하는 좋은 습관입니다.

**개선이 필요한 부분**
- `MybatisUnitBasedRule`의 `currentViolations`와 `currentXmlPath`가 `protected` 인스턴스 변수입니다. SAX 핸들러 자체가 상태를 가지므로 동일 인스턴스를 다른 스레드에서 사용하면 레이스 컨디션이 발생합니다. (이는 메모리에 별도 기록된 기존 패턴과 일치하는 문제입니다)
- `RuleRunner.executeRules()`의 예외 처리에서 `e.getMessage()`만 저장하고 스택 트레이스를 버립니다. 라이브러리 레벨 코드에서는 디버깅이 매우 어려워집니다.
- `Report.printConsoleReport()`가 `System.out.println`을 사용합니다. 이 코드 자체가 `NoSystemOutRule`의 검사 대상이 되는 **자가당착** 문제입니다. (메모리에 기록된 "규칙 도구의 자가당착 패턴"과 동일합니다)
- `GitDiffUtil`에서도 `System.err.println`을 사용합니다. `@Slf4j` 로거로 교체해야 합니다.
- `TransactionalSwallowExceptionRule.notSwallowException()`의 catch 블록이 `Exception ignored`로 모든 예외를 무시합니다. ASM 파싱 실패 시 오탐(false negative)이 발생해도 아무런 신호를 주지 않습니다.
- `NoSystemOutRule`의 `belongToAnyOf(System.class)` 조건은 `System.err`, `System.in`, `System.setProperty()` 등 `System` 클래스의 모든 접근을 금지합니다. 의도보다 범위가 넓습니다.

### 엣지 케이스 처리 여부

| 엣지 케이스 | 처리 여부 | 비고 |
|---|---|---|
| affectedFiles가 null인 경우 | ✅ | `hasAffectedFiles()`에서 null 체크 |
| mapperDir가 존재하지 않는 경우 | ✅ | `Files.exists()` 체크 후 스킵 |
| XML 파싱 도중 SAXException 발생 | ✅ | `parseXml()` 내 catch(Exception ignored) |
| ArchUnit 검사 대상 클래스가 0개인 경우 | ✅ | `classes.isEmpty()` 체크 |
| .java 파일에 대응하는 .class가 없는 경우 | ✅ | `p.toFile().exists()` 필터링 |
| RuleGroups 이름이 잘못된 경우 | ✅ | `IllegalArgumentException` catch 후 ALL 폴백 |
| MyBatisIfRule: 파일 간 tagStack 초기화 | ❌ | `tagStack`이 인스턴스 변수이고 파일 간 초기화 없음. 한 파일 파싱이 비정상 종료되면 스택 잔재가 다음 파일에 영향을 줍니다 |
| SqlBasicPerformanceRule: 파일 간 sqlBuffer/isSqlTag 초기화 | ❌ | `sqlBuffer`와 `isSqlTag`가 인스턴스 변수이며 `check()` 시작 시 초기화하지 않습니다. 이전 파일의 상태가 유입될 수 있습니다 |
| TransactionalSwallowExceptionRule: 오버로드 메서드 구분 | ❌ | `mn.name.equals(method.getName())`만 비교하고 descriptor(파라미터 타입)를 비교하지 않습니다. 동명의 오버로드 메서드가 있으면 잘못된 메서드를 분석할 수 있습니다 (메모리에 기록된 "ASM MethodNode descriptor 미비교 오탐 패턴"과 동일) |
| MyBatisXmlRule: CDATA 내부 텍스트 처리 | ❌ | SAX의 `characters()`는 CDATA 내부도 동일하게 호출됩니다. 이미 CDATA로 감싼 부등호가 있으면 오탐이 발생합니다 |
| failOnViolation=false이면 WARN도 무시 | ✅ | 의도된 동작 |
| Git 저장소가 아닌 환경에서 증분 모드 실행 | △ | `System.err`로만 출력하고 빈 리스트 반환. 전수 검사로 자동 전환되지 않아 검사가 누락됩니다 |
| workspaceRoot와 projectRoot가 같은 경우 relativize | ✅ | 빈 문자열이 되지만 동작은 정상 |

### 면접관 시점의 개선 제안

1. **왜 `MybatisUnitBasedRule`이 `DefaultHandler`를 상속하나요?** SAX 핸들러와 Rule 도메인 인터페이스를 동시에 구현하면 상속 계층이 외부 프레임워크(SAX)에 종속됩니다. 핸들러를 별도 내부 클래스로 분리하면 `MybatisUnitBasedRule`이 순수한 도메인 객체로 남을 수 있습니다.

2. **`RuleGroups.ALL` 중복 선언**: `ALL`은 `JAVA_CRITICAL.getRules()`와 `SQL_CRITICAL.getRules()`를 합쳐서 구성하는 것이 DRY 원칙에 맞습니다. 현재는 새 규칙을 추가할 때 두 곳을 수정해야 합니다.

3. **`RuleTask`에 `@InputFiles`/`@OutputDirectory` 미선언**: Gradle의 UP-TO-DATE 최적화가 동작하지 않습니다. 소스 파일이 변경되지 않아도 항상 태스크가 재실행됩니다. (메모리에 기록된 "Gradle Task Input/Output 미선언 패턴"과 동일)

4. **`MyBatisIfRule.tagStack` 파일 간 미초기화**: `parseXml()` 호출 전에 `tagStack.clear()`가 호출되어야 합니다.

</step3_code_review>

<step4_pros_and_cons>

## ⚖️ Step 4. 엔지니어 역량 장단점 평가

### ✅ 강점 (Strengths)

- **다층 분석 전략에 대한 이해**: ArchUnit(클래스 구조 분석) + ASM(바이트코드 정밀 분석)을 조합하는 `TransactionalSwallowExceptionRule`은 단순 텍스트 매칭이나 단일 도구에 의존하지 않고 두 계층의 도구를 목적에 맞게 조합하는 사고력을 보여줍니다. 이런 설계 결정은 실무 경험 없이는 나오기 어렵습니다.

- **증분 검사(Incremental Check) 설계**: `affectedFiles`를 `RuleContext`에 주입하고 `ArchUnitBasedRule`과 `MybatisUnitBasedRule` 모두에서 분기 처리하는 구조는 대규모 코드베이스에서 검사 시간을 줄이기 위한 실용적 접근입니다. CI/CD 파이프라인 경험이 반영된 설계입니다.

- **Record + Builder 패턴**: `RuleContext`가 Java 17 `record`의 불변성을 활용하면서도 `Builder`로 점진적 구성을 지원합니다. 프레임워크 의존 없이 순수 Java로 Fluent API를 구현한 것이 인상적입니다.

- **Gradle Extension의 Convention 활용**: `extension.getIncremental().convention(...)` 안에서 프로젝트 속성(`rule.incremental`)을 동적으로 읽어오는 Provider 체인 구성은 Gradle의 Lazy Configuration 모델을 정확히 이해하고 활용한 것입니다.

- **Spotless XML 커스텀 스텝의 방어적 정규식**: SQL 키워드 대문자 변환 시 XML 주석, 태그 내부, MyBatis 변수(`#{}`, `${}`), SQL 문자열 리터럴을 모두 예외 처리하는 정규식을 작성했습니다. 오탐 시나리오를 사전에 고민한 흔적입니다.

- **RuleViolation의 이중 경로 설계**: 절대경로(`filePath`)는 콘솔 IDE 링크용으로, 상대경로(`relativePath`)는 JSON 리포트용으로 분리하고 Jackson 애노테이션으로 직렬화를 제어한 것은 실제 사용자 경험(IDE 클릭 이동)을 고려한 세심한 설계입니다.

### ⚠️ 개선 필요 영역 (Areas for Improvement)

- **SAX Handler 상태 관리 취약점**: `MyBatisIfRule.tagStack`, `SqlBasicPerformanceRule.sqlBuffer/isSqlTag`가 파일 간에 초기화되지 않는 문제는 단순 버그를 넘어 **상태형(Stateful) 객체를 인스턴스 재사용하는 패턴에서 반드시 고려해야 할 생명주기 문제**입니다. 면접에서 "이 코드가 2번 파일 검사할 때 결과가 달라질 수 있나요?"라고 물으면 즉각 답해야 하는 포인트입니다.

- **`ArchUnitBasedRule.findFile()`의 O(M×N) 성능 문제**: 위반이 많을수록 파일 시스템을 반복 탐색합니다. 이것이 단순 실수인지, 아니면 위반이 적을 것을 가정한 의도적 트레이드오프인지 면접관은 반드시 묻습니다.

- **`TransactionalSwallowExceptionRule`의 오버로드 미구분**: `mn.name.equals(method.getName())`으로만 비교하면 동일 클래스에 같은 이름의 오버로드 메서드가 있을 때 잘못된 메서드를 검사할 수 있습니다. ASM을 도입한 정밀도의 장점이 이 한 줄로 훼손됩니다.

- **`Report.printConsoleReport()`의 자가당착**: `NoSystemOutRule`이 `System.out.println`을 금지하는 라이브러리 안에 `System.out.println`이 존재합니다. 도구 자신이 자신의 규칙을 위반하는 상황은 신뢰성 문제로 이어집니다.

- **테스트 코드 부재**: `rule-core`에 단 하나의 학습 테스트(`MybatisBadSqlSelectStarTest`)만 존재합니다. `NoFieldInjectionRule`, `NoGenericCatchRule`, `MyBatisIfRule` 등 핵심 규칙들에 대한 단위 테스트가 없으면 리팩토링 시 안전망이 없습니다.

### 📊 종합 평가

- **기술 역량**: ⭐⭐⭐⭐☆
- **문제 해결력**: ⭐⭐⭐⭐☆
- **코드 품질**: ⭐⭐⭐☆☆
- **총평**: ArchUnit, ASM, SAX, JSQLParser, Gradle Plugin API, Spotless라는 서로 다른 레이어의 도구들을 하나의 일관된 아키텍처로 조립하는 능력은 시니어 수준의 역량을 보여줍니다. 특히 증분 검사 설계와 Gradle Provider/Convention 활용은 카카오/네이버 수준의 기술 면접에서도 인상적으로 보일 부분입니다. 그러나 SAX Stateful Handler의 파일 간 상태 미초기화, ASM 오버로드 미구분, findFile()의 반복 탐색, 자가당착 `System.out.println` 사용 등 **"동작은 하지만 엣지 케이스에서 조용히 틀리는" 버그들이 다수** 존재합니다. 이런 버그들이 테스트 부재로 검증되지 않은 채 남아 있다는 점이 합격을 보류하게 만드는 핵심 요인입니다. 설계 사고력은 충분히 검증되었으므로, 코드 완결성(엣지 케이스 + 테스트)을 보강하면 시니어 포지션 합격 가능성이 높습니다.

</step4_pros_and_cons>

<step5_interview_qna>

## 💬 Step 5. 기술 면접 예상 Q&A

---

### 🔗 CS 꼬리 질문 (심화)

**Q1. `TransactionalSwallowExceptionRule`에서 ArchUnit과 ASM을 함께 쓴 이유가 뭔가요? ArchUnit만으로는 왜 안 되나요?**

> 💡 **모범 답변 가이드라인**:
> - ArchUnit은 바이트코드 구조(클래스 계층, 애노테이션 유무, 메서드 시그니처)를 선언적으로 검사합니다. TryCatchBlock의 존재는 감지할 수 있지만, "catch 블록 안에서 예외를 다시 던지는지"라는 **제어 흐름(control flow) 시맨틱**은 ASM이 아니면 표현하기 어렵습니다.
> - ArchUnit이 제공하는 `TryCatchBlock.getCaughtThrowables()`는 잡은 예외 타입만 알려줄 뿐, catch 블록 내부의 실행 흐름(ATHROW 명령 존재 여부)은 알려주지 않습니다.
> - 핵심 키워드: `ATHROW opcode`, `IRETURN~RETURN opcode`, 제어 흐름 분석(CFG), ArchUnit의 선언적 한계.
> - 꼬리 질문: "ASM의 Tree API와 Visitor API 중 Tree API를 선택한 이유는요?" (Tree API는 전체 메서드를 메모리에 올려서 랜덤 접근 가능, Visitor API는 스트리밍이라 메모리 효율적이나 역방향 탐색 불가)

**Q2. SAX 파서를 선택한 이유가 뭔가요? DOM 파서 대신 SAX를 쓸 때 어떤 트레이드오프가 있나요?**

> 💡 **모범 답변 가이드라인**:
> - DOM: 전체 XML을 파싱하여 트리를 메모리에 유지. 임의 노드 접근이 자유롭고 XPath 사용 가능. 파일 크기에 비례하는 메모리 사용.
> - SAX: 이벤트 드리븐, 순방향 스트리밍. 메모리 사용이 O(1)에 가깝지만 상태를 직접 관리해야 하고 역방향 탐색이 불가합니다.
> - 이 코드의 선택이 합리적인 이유: MyBatis XML은 규칙 검사 목적으로 순방향 1회 스캔이면 충분하고, 수백 개의 매퍼 파일을 순차적으로 처리하므로 메모리 효율이 중요합니다.
> - 면접관이 기대하는 추가 언급: "그렇다면 StAX(Streaming API for XML)와는 어떻게 다른가요?" — StAX는 풀(pull) 방식이라 파서 호출 제어권이 코드에 있어 복잡한 상태 관리 시 SAX보다 코드가 명확해질 수 있습니다.

**Q3. Gradle의 `@InputFiles`/`@OutputDirectory`를 선언하지 않으면 어떤 문제가 생기나요?**

> 💡 **모범 답변 가이드라인**:
> - Gradle의 증분 빌드(Incremental Build)는 태스크의 입력과 출력 변경 여부를 추적해서 UP-TO-DATE 판단합니다. Input/Output을 선언하지 않으면 Gradle이 변경 여부를 알 수 없어 **항상 태스크를 재실행(never UP-TO-DATE)**합니다.
> - 구체적 영향: 소스 파일이 전혀 바뀌지 않아도 `./gradlew check`를 실행할 때마다 전체 규칙 검사가 수행됩니다. 대규모 프로젝트에서는 빌드 시간이 수 분씩 낭비됩니다.
> - 해결 방법: `@InputFiles`로 소스 디렉토리와 XML 경로를, `@OutputDirectory`로 reports 경로를 선언해야 합니다.
> - 심화: Gradle 빌드 캐시(Build Cache)도 Input/Output 선언이 없으면 비활성화됩니다. 분산 빌드 캐시(원격 캐시)를 쓰는 대형 조직에서는 더 큰 영향을 줍니다.

---

### 🔥 압박 면접 질문 (Stress Interview)

**Q. `MyBatisIfRule`과 `SqlBasicPerformanceRule`은 각각 `tagStack`, `sqlBuffer`라는 인스턴스 변수를 가집니다. 그런데 `MybatisUnitBasedRule.check()`를 보면 같은 핸들러 인스턴스로 여러 XML 파일을 순차 파싱합니다. 두 번째 파일 파싱 결과가 첫 번째 파일의 잔류 상태에 오염될 수 있지 않나요? 직접 재현 시나리오를 설명해보세요.**

> 💡 **대응 가이드라인**:
>
> **당황하지 않는 법**: 이 질문은 "몰랐나요?"가 아니라 "알고 있었나요, 그리고 어떻게 고칠 건가요?"를 묻습니다.
>
> **재현 시나리오**:
> - `MyBatisIfRule` 시나리오: 첫 번째 XML 파일에서 `<select>` 태그를 만나 push했는데 SAXParseException이 발생해서 `endElement`가 호출되지 않았습니다. `tagStack`에 "select"가 남아있는 상태로 두 번째 XML 파일 파싱이 시작됩니다. 두 번째 파일의 `<if>` 태그가 실제로는 `<where>` 밖에 있어도 `tagStack.stream().anyMatch(SAFETY_TAGS::contains)`가 첫 번째 파일 잔재 때문에 오탐(false positive를 missed, 즉 false negative)을 냅니다.
>
> **수정 방법**: `MybatisUnitBasedRule.parseXml()` 호출 전 또는 `startDocument()` SAX 콜백에서 서브클래스의 상태를 초기화하는 `reset()` 훅 메서드를 제공하고 각 서브클래스가 오버라이드하도록 설계해야 합니다.
>
> **모르는 경우 대처법**: "지금 보니 `parseXml()` 전에 상태 초기화가 빠져 있네요. `startDocument()` 콜백을 활용해서 파일 단위 초기화를 보장하는 것이 올바른 설계라고 생각합니다. 라이브 코딩으로 바로 수정해보겠습니다." — 솔직하게 인정하고 해결 방향을 즉시 제시하는 것이 최선입니다.

---

### 📌 면접 준비 To-Do

- [ ] ASM 바이트코드 분석 심화 학습: `ClassVisitor` vs `ClassNode` 차이, `MethodDescriptor` 형식 (`(Ljava/lang/String;I)V` 파싱), `Opcodes.ATHROW`/`IRETURN`~`RETURN` 범위 이해
- [ ] Gradle Task 증분 빌드 메커니즘 학습: `@InputFiles`, `@OutputDirectory`, `@Incremental` 애노테이션 및 `InputChanges` API, 빌드 캐시(Build Cache) 동작 원리
- [ ] SAX vs DOM vs StAX 비교 및 Stateful SAX Handler의 생명주기 관리 패턴 (파서 재사용, 핸들러 상태 초기화, 스레드 안전성) 학습

</step5_interview_qna>
