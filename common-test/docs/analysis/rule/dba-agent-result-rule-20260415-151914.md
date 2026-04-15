# DBA Agent 분석 결과

분析일시: 2026-04-15 15:19:14
분析 대상 프로젝트: rule-core + rule-gradle-plugin

---

<step1_code_analysis>

## 1단계: 쿼리 및 로직 요약

### 분석 대상 개요

본 분석은 MyBatis XML 매퍼 정적 분석 도구인 `rule-core`와 이를 Gradle 빌드 파이프라인에 통합하는 `rule-gradle-plugin` 두 모듈을 대상으로 합니다.

이 시스템은 애플리케이션 코드의 **SQL 안티패턴 및 Java 코드 컨벤션 위반**을 빌드 시점에 자동 탐지하는 목적을 가집니다.

---

### rule-core 모듈 구성

#### SQL 규칙 계층 구조

| 클래스 | 역할 |
|---|---|
| `MybatisUnitBasedRule` | SAX 파서 기반 추상 클래스. XML 파일 순회 및 파싱 담당 |
| `SqlBasicPerformanceRule` | `SELECT *`, `t.*`, 스칼라 서브쿼리 탐지 |
| `NoDollarExpressionRule` | `${}` SQL Injection 위험 표현식 탐지 |
| `MyBatisXmlRule` | 부등호 연산자의 CDATA 감싸기 누락 탐지 |
| `MyBatisIfRule` | `<if>` 태그가 `<where>`, `<set>`, `<trim>` 외부에 위치 시 탐지 |

#### Java 규칙 계층 구조

| 클래스 | 역할 |
|---|---|
| `ArchUnitBasedRule` | ArchUnit 기반 Java 바이트코드 분석 추상 클래스 |
| `NoSystemOutRule` | `System.out` 사용 금지 탐지 |
| `NoFieldInjectionRule` | `@Autowired` 필드 주입 금지 탐지 |
| `NoGenericCatchRule` | `Exception`, `Throwable` 등 범용 예외 catch 탐지 |
| `TransactionalSwallowExceptionRule` | `@Transactional` 메서드 내 예외 삼킴(swallow) 탐지 (ASM 바이트코드 분석) |

#### 규칙 그룹 (`RuleGroups` Enum)

- `JAVA_CRITICAL`: NoSystemOut, NoFieldInjection, NoGenericCatch
- `SQL_CRITICAL`: TransactionalSwallowException, NoDollarExpression, MyBatisXmlRule, MyBatisIfRule, SqlBasicPerformanceRule
- `ALL`: 위 모든 규칙 통합

#### 주목할 점: SQL_CRITICAL 그룹에 Java 규칙 혼입

`SQL_CRITICAL` 그룹에 `TransactionalSwallowExceptionRule`(Java 바이트코드 규칙)이 포함되어 있습니다. 이는 논리적 분류 오류로, SQL 규칙 그룹에서 Java 분석 도구(ArchUnit)를 실행하는 의도치 않은 비용을 야기합니다.

---

### rule-gradle-plugin 모듈 구성

| 클래스 | 역할 |
|---|---|
| `RuleExtension` | Gradle DSL Extension. 사용자 설정값 정의 (basePackage, incremental 등) |
| `RulePlugin` | Plugin 진입점. Task 등록, Spotless 포맷터 조건부 구성 |
| `RuleTask` | 실제 규칙 검사 실행. GitDiffUtil로 증분 대상 수집 후 RuleRunner 호출 |

---

### 테스트 대상 매퍼 파일 분석 (`BadSqlMapper.xml`)

```xml
<!-- 쿼리 1: BadSqlSelectStar -->
SELECT tsu.ID, tsm.TEST, SUM(tsm.COUNT), ...
FROM (SELECT * FROM TBSI_USR) AS tsu    -- 서브쿼리 내 SELECT *
INNER JOIN TBSI_MBS AS tsm ON ...       -- AS 키워드 사용 (테이블에 AS 금지)
INNER JOIN (SELECT *, COUNT1 + COUNT2 ... FROM TBSI_VGRP) tsv ...
WHERE 1 = 1
  <if test="ID != null and ID != ''">   -- <where> 없이 <if> 직접 사용
    AND tsu.ID = #{ID}
  </if>

<!-- 쿼리 2: findBadSql -->
SELECT *, (SELECT count(*) FROM TB_LOG) AS log_count   -- SELECT *, 스칼라 서브쿼리
FROM TB_USER u, TB_DEPT d                               -- 암시적 JOIN
WHERE ... AND u.status = ${status}                     -- ${}  사용
  AND u.age &lt; 30                                    -- XML 엔티티(&lt;) 사용
```

이 매퍼는 각 규칙이 탐지해야 할 안티패턴을 의도적으로 담은 **테스트 픽스처**로 사용됩니다. 그러나 실제 규칙 엔진이 이 파일의 모든 안티패턴을 정확히 탐지하는지에 대한 검증 격차가 존재합니다.

</step1_code_analysis>

<step2_architecture_analysis>

## 2단계: DB 트랜잭션 경계 분석

본 모듈은 DB에 직접 접근하는 서비스 레이어가 아닌 **정적 분석 도구**입니다. 따라서 전통적인 `@Transactional` 범위 분석 대신, 이 도구가 검사하는 **트랜잭션 관련 규칙의 정확성**을 중심으로 분석합니다.

---

### TransactionalSwallowExceptionRule의 정밀도 이슈

`TransactionalSwallowExceptionRule`은 ASM 바이트코드를 직접 읽어 `@Transactional` 메서드 내 예외 삼킴(Exception Swallowing)을 탐지합니다.

**현재 탐지 로직:**

```java
// TransactionalSwallowExceptionRule.java - isSwallowing()
for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
    AbstractInsnNode current = tcb.handler;
    while (current != null) {
        int opcode = current.getOpcode();
        if (opcode == Opcodes.ATHROW) break; // 정상: 다시 던짐
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
            return true; // 위반: 다시 던지지 않고 리턴함
        }
        current = current.getNext();
    }
}
```

**문제점 1: 거짓 양성(False Positive) 발생 위험**

catch 블록 내에서 `log.error()`를 호출하고 `return false`로 빠져나가는 완전히 합법적인 패턴도 위반으로 탐지됩니다.

```java
// 합법적이지만 탐지됨 (예: 결제 실패를 FAIL 상태로 저장하고 false 반환)
@Transactional
public boolean processPayment(PaymentRequest req) {
    try {
        return paymentAdapter.pay(req);
    } catch (PaymentException e) {
        log.error("결제 실패 처리", e);
        paymentRepository.updateStatus(req.getTxId(), Status.FAIL);
        return false; // <-- 위반 오탐
    }
}
```

**문제점 2: 메서드 시그니처 매칭 부재**

```java
for (MethodNode mn : cn.methods) {
    if (mn.name.equals(method.getName()) && ...) { // 메서드명만 비교, 파라미터 타입 미검증
```

오버로딩된 메서드가 존재할 경우 잘못된 MethodNode를 분석하여 오탐 또는 미탐이 발생할 수 있습니다.

**문제점 3: `@Transactional(noRollbackFor = ...)` 속성 미고려**

`noRollbackFor` 속성으로 특정 예외를 롤백 대상에서 제외한 경우, 예외를 삼키더라도 의도된 설계일 수 있습니다. 현재 로직은 이를 전혀 고려하지 않습니다.

---

### MybatisUnitBasedRule 내 SAXParser 재사용 문제 (핵심 구조적 버그)

```java
// MybatisUnitBasedRule.java
public List<RuleViolation> check(RuleContext context) throws Exception {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    SAXParser saxParser = factory.newSAXParser();
    // ...
    files.filter(...).forEach(xml -> parseXml(xml, saxParser)); // 동일 saxParser 재사용
}

private void parseXml(Path xml, SAXParser saxParser) {
    this.currentXmlPath = xml;             // 인스턴스 상태 변경
    saxParser.parse(xml.toFile(), this);   // this(DefaultHandler)를 ContentHandler로 재사용
}
```

`SAXParser`는 `reset()` 없이 동일 `ContentHandler` 인스턴스를 재사용할 경우 **이전 파싱 상태가 잔류**할 수 있습니다. 특히 `SqlBasicPerformanceRule`의 `isSqlTag`, `sqlBuffer`와 `MyBatisIfRule`의 `tagStack`은 파일 간 파싱 상태가 오염될 위험이 있습니다.

**더 심각한 문제: `RuleGroups` Enum의 싱글턴 규칙 인스턴스**

```java
// RuleGroups.java
ALL(
    new NoSystemOutRule(),          // Enum 초기화 시 딱 1개만 생성
    new NoDollarExpressionRule(),
    new MyBatisXmlRule(),
    new MyBatisIfRule(),            // tagStack이 싱글턴으로 관리됨
    new SqlBasicPerformanceRule()   // sqlBuffer, isSqlTag가 싱글턴으로 관리됨
);
```

Enum 상수는 JVM 내에서 **싱글턴**으로 동작합니다. `MyBatisIfRule`의 `tagStack`과 `SqlBasicPerformanceRule`의 `sqlBuffer`/`isSqlTag`는 인스턴스 필드이지만, 해당 인스턴스 자체가 싱글턴이므로 **연속된 `ruleCheck` 실행 간 상태가 초기화되지 않고 누적**됩니다. 이는 결과 오염을 야기하는 심각한 버그입니다.

</step2_architecture_analysis>

<step3_code_review>

## 3단계: EXPLAIN/ANALYZE 기반 성능 튜닝 및 인덱스 리뷰

본 모듈은 SQL 실행 엔진이 아닌 정적 분석 도구이므로, EXPLAIN 분석 대신 **각 규칙의 탐지 정확도(Precision/Recall)**와 **MyBatis 규칙 로직의 구조적 결함**을 성능 및 정확도 관점에서 심층 분석합니다.

---

### [버그 1] SqlBasicPerformanceRule - 서브쿼리 내 SELECT * 미탐지

```java
// 현재 로직: SAX 이벤트로 수집된 텍스트를 파싱
String cleanSql = sqlBuffer.toString().replaceAll("<[^>]*>", " ").trim();
```

`FROM (SELECT * FROM TBSI_USR) AS tsu` 구조에서 서브쿼리 내 `SELECT *`는 jsqlparser의 `PlainSelect`가 **외부 SELECT의 SelectItems만 검사**하기 때문에 탐지되지 않습니다. `BadSqlMapper.xml`의 `BadSqlSelectStar` 쿼리가 이 케이스에 해당합니다.

**개선안: 재귀적 서브쿼리 순회**

```java
private void inspectPlainSelect(PlainSelect plainSelect) {
    checkSelectItems(plainSelect);

    // FROM 절 서브쿼리 재귀 검사
    if (plainSelect.getFromItem() instanceof ParenthesedSelect subSelect) {
        if (subSelect.getPlainSelect() != null) {
            inspectPlainSelect(subSelect.getPlainSelect());
        }
    }

    // JOIN 절 서브쿼리 재귀 검사
    if (plainSelect.getJoins() != null) {
        for (Join join : plainSelect.getJoins()) {
            if (join.getRightItem() instanceof ParenthesedSelect joinSub) {
                if (joinSub.getPlainSelect() != null) {
                    inspectPlainSelect(joinSub.getPlainSelect());
                }
            }
        }
    }
}
```

---

### [버그 2] MyBatisXmlRule - CDATA 섹션 내부 텍스트 오탐

SAX 파서는 `<![CDATA[ ... ]]>` 내부 내용도 `characters()` 콜백으로 전달합니다. 따라서 **이미 올바르게 CDATA로 감싸진 `age < 30`** 표현식도 `MyBatisXmlRule`이 위반으로 탐지합니다.

```java
// 현재: CDATA 내부인지 외부인지 구분 없이 characters() 에서 검사
public void characters(char[] ch, int start, int length) {
    String text = new String(ch, start, length);
    if (hasRawInequality(text)) {
        // CDATA 내부도 여기서 탐지됨 -> 오탐
    }
}
```

**개선안: CDATA 섹션 감지 플래그 도입**

```java
public class MyBatisXmlRule extends MybatisUnitBasedRule {

    // SAX2 LexicalHandler를 통해 CDATA 구간 추적
    private boolean inCdata = false;

    @Override
    public void startCDATA() {
        this.inCdata = true;
    }

    @Override
    public void endCDATA() {
        this.inCdata = false;
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (inCdata) return; // CDATA 내부는 검사 제외
        String text = new String(ch, start, length);
        if (hasRawInequality(text)) {
            // 실제 위반만 탐지
        }
    }
}
```

단, `LexicalHandler` 활성화를 위해 `SAXParserFactory`에 설정이 필요합니다:

```java
// MybatisUnitBasedRule.check() 내에서
SAXParserFactory factory = SAXParserFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
SAXParser saxParser = factory.newSAXParser();
// LexicalHandler 등록
saxParser.getXMLReader().setProperty(
    "http://xml.org/sax/properties/lexical-handler", this
);
```

---

### [버그 3] MyBatisIfRule - tagStack 상태 누적 버그 (싱글턴 오염)

앞서 설명한 `RuleGroups` Enum 싱글턴 문제로 인해 `MyBatisIfRule`의 `tagStack`은 첫 번째 XML 파일 파싱 이후 비워지지 않은 채 다음 파일 파싱에 사용됩니다.

**개선안: `check()` 메서드 진입 시 상태 초기화**

각 규칙 클래스에 `reset()` 훅을 추가하거나, `MybatisUnitBasedRule`의 `parseXml()` 진입 시 초기화 메서드를 호출합니다:

```java
// MybatisUnitBasedRule.java
private void parseXml(Path xml, SAXParser saxParser) {
    this.currentXmlPath = xml;
    resetState(); // 파일 단위 상태 초기화 훅
    try {
        saxParser.parse(xml.toFile(), this);
    } catch (Exception ignored) {}
}

// 하위 클래스에서 재정의 가능한 상태 초기화 훅
protected void resetState() {
    // 기본값: no-op (하위 클래스에서 override)
}
```

```java
// MyBatisIfRule.java
@Override
protected void resetState() {
    tagStack.clear();
}

// SqlBasicPerformanceRule.java
@Override
protected void resetState() {
    sqlBuffer.setLength(0);
    isSqlTag = false;
}
```

**근본적 해결: RuleGroups에서 Supplier 패턴으로 매 실행마다 새 인스턴스 생성**

```java
// 개선된 RuleGroups.java
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

    public List<Rule> getRules() {
        // 매 호출마다 새 인스턴스 생성 -> 상태 오염 방지
        return ruleSuppliers.stream()
                .map(Supplier::get)
                .collect(Collectors.toList());
    }
}
```

---

### [버그 4] NoDollarExpressionRule - `${}`가 XML 주석 내에 있을 때도 탐지

SAX `characters()` 콜백은 XML 주석(`<!-- -->`) 내부 텍스트는 전달하지 않으므로 이 부분은 안전합니다. 그러나 **`<if test="...">` 속성값 내의 `${}`**는 `characters()`가 아닌 `startElement()`의 `Attributes`로 전달되므로 현재 로직에서 **미탐**이 발생합니다.

**개선안: `startElement()`에서 속성값도 검사**

```java
// NoDollarExpressionRule.java
@Override
public void startElement(String uri, String localName, String qName, Attributes attributes) {
    for (int i = 0; i < attributes.getLength(); i++) {
        String attrValue = attributes.getValue(i);
        if (attrValue != null && attrValue.contains("${")) {
            currentViolations.add(new RuleViolation(
                "NoDollarExpressionRule",
                Status.FAIL,
                "Found ${} usage in attribute '" + attributes.getQName(i) + "' (SQL Injection risk). Use #{} instead.",
                getCurrentAbsolutePath(),
                getCurrentRelativePath(),
                getLineNumber()
            ));
        }
    }
}
```

---

### [버그 5] SqlBasicPerformanceRule - 암시적 JOIN 미탐지

`BadSqlMapper.xml`의 `findBadSql` 쿼리에는 `FROM TB_USER u, TB_DEPT d`라는 암시적 JOIN이 있습니다. 현재 `SqlBasicPerformanceRule`은 이를 탐지하는 로직이 없습니다.

**개선안: FROM 절의 암시적 JOIN 탐지 추가**

```java
// SqlBasicPerformanceRule.java - inspectPlainSelect() 내 추가
private void checkImplicitJoin(PlainSelect plainSelect) {
    // jsqlparser에서 FROM A, B 형식은 getJoins()가 아닌
    // getFromItem()과 별도 Join 리스트로 파싱됨
    // FROM 절 뒤에 콤마로 연결된 테이블은 Cross Join으로 인식
    if (plainSelect.getJoins() != null) {
        for (Join join : plainSelect.getJoins()) {
            if (join.isSimple()) { // isSimple() == 암시적 JOIN (FROM A, B)
                reportViolation("암시적 JOIN(FROM A, B) 사용 금지. 명시적 JOIN 키워드를 사용하세요.");
            }
        }
    }
}
```

---

### [버그 6] GitDiffUtil - `System.err` 직접 사용

```java
// GitDiffUtil.java
} catch (Exception e) {
    System.err.println("[GIT] Failed to get diff for " + baseRef + ": " + e.getMessage());
}
```

이 코드는 Gradle 플러그인 컨텍스트에서 실행되므로 `System.err`가 아닌 Gradle Logger를 사용해야 하나, `GitDiffUtil`이 Gradle 의존성 없이 `rule-core`에 위치하는 구조적 제약이 있습니다. 최소한 콘솔 출력 대신 예외를 던지거나 빈 리스트를 반환하면서 로깅 콜백을 주입받는 방식으로 개선이 필요합니다.

---

### [누락 규칙] 탐지되지 않는 SQL 안티패턴 목록

현재 규칙 엔진이 탐지하지 못하는 중요 패턴들이 있습니다:

| 안티패턴 | 위험도 | 설명 |
|---|---|---|
| 암시적 JOIN (`FROM A, B`) | 높음 | 카테시안 곱 위험, 실수 유발 |
| `WHERE 1=1` 패턴 | 중간 | 동적 쿼리 구성 시 불필요한 조건 잔류 |
| 인덱스 무력화 함수 (`UPPER(col) = ...`) | 높음 | 컬럼에 함수 적용 시 인덱스 스캔 불가 |
| `LIKE '%keyword%'` 패턴 | 높음 | 전방 와일드카드로 인한 Full Table Scan |
| `NOT IN` 서브쿼리 | 중간 | NULL 처리 불안정 및 성능 저하 |
| 페이지네이션 없는 대량 조회 | 높음 | LIMIT 없는 SELECT의 OOM 위험 |

</step3_code_review>

<step4_pros_and_cons>

## 4단계: 잘한 점과 개선점

### 잘한 점

**1. SAX 기반 스트리밍 XML 파싱 선택**
DOM 파서 대신 SAX를 채택하여 메모리 효율성을 확보했습니다. 수천 개의 MyBatis XML 파일이 존재하는 대형 PG 프로젝트에서도 전체 파일을 메모리에 올리지 않고 스트리밍 방식으로 처리할 수 있습니다.

**2. DTD 외부 로딩 비활성화**
```java
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
```
MyBatis DTD를 외부 네트워크에서 로딩하지 않도록 차단하여 **오프라인 환경 및 CI/CD 파이프라인에서의 신뢰성**을 확보했습니다. 실무에서 자주 놓치는 설정입니다.

**3. jsqlparser를 통한 AST 기반 SQL 분석**
단순 정규식 대신 `CCJSqlParserUtil.parse()`로 SQL 추상 구문 트리(AST)를 생성하여 분석합니다. `SELECT *` 탐지 시 문자열 패턴 매칭보다 훨씬 정확한 의미론적 분석이 가능합니다.

**4. 증분 검사(Incremental Check) 설계**
`GitDiffUtil`을 통해 `git diff` 결과를 기반으로 변경된 파일만 검사하는 증분 모드를 구현했습니다. 전체 검사 대비 로컬 개발 시 피드백 루프를 크게 단축시킵니다.

**5. `TransactionalSwallowExceptionRule`의 ASM 바이트코드 분석**
소스 코드 파싱이 아닌 컴파일된 바이트코드를 직접 분석하여 리플렉션 기반보다 정확하고 빠른 예외 삼킴 탐지를 구현했습니다. `@Transactional` 메서드에서 롤백 실패를 야기하는 패턴을 빌드 단계에서 차단하는 것은 PG 도메인에서 매우 중요합니다.

**6. RuleExtension을 통한 유연한 Gradle DSL 설계**
`basePackage`, `mapperPaths`, `ruleGroupName`, `failOnViolation` 등 다양한 설정을 DSL로 노출하여 프로젝트별 커스터마이징이 용이합니다.

**7. Spotless 포맷터 통합**
SQL 키워드 대문자 변환을 위한 커스텀 `uppercaseSqlKeywords` 로직을 정규식 기반으로 구현하여 XML 태그, MyBatis 변수(`#{}`), 주석을 보호하면서 SQL 예약어만 선택적으로 변환합니다.

---

### 개선이 필요한 점

| 우선순위 | 항목 | 문제 설명 | 개선 방향 |
|---|---|---|---|
| 🔴 High | RuleGroups 싱글턴 오염 | Enum으로 생성된 Rule 인스턴스가 JVM 생애주기 동안 상태를 유지. `tagStack`, `sqlBuffer`, `isSqlTag` 등이 연속 실행 시 오염됨 | `Supplier<Rule>` 패턴으로 매 실행마다 새 인스턴스 생성 또는 `resetState()` 훅 도입 |
| 🔴 High | MyBatisXmlRule CDATA 오탐 | SAX `characters()`는 CDATA 섹션 내부도 전달하므로 올바르게 감싼 `<![CDATA[age < 30]]>`도 위반으로 탐지 | `LexicalHandler`의 `startCDATA()`/`endCDATA()` 훅으로 CDATA 구간 플래그 관리 |
| 🔴 High | SQL_CRITICAL 그룹 분류 오류 | `SQL_CRITICAL`에 Java 바이트코드 규칙인 `TransactionalSwallowExceptionRule`이 포함됨 | `JAVA_CRITICAL` 그룹으로 이동 또는 별도 `TRANSACTION_CRITICAL` 그룹 신설 |
| 🔴 High | 서브쿼리 내 SELECT * 미탐 | `SqlBasicPerformanceRule`이 외부 SELECT의 SelectItems만 검사. `FROM (SELECT * FROM T)` 패턴 누락 | `inspectPlainSelect()`를 재귀 호출하여 중첩 서브쿼리까지 순회 |
| 🟡 Medium | TransactionalSwallowExceptionRule 오탐 | catch 블록에서 로깅 후 `return false` 반환하는 합법적 패턴도 위반 탐지 | ATHROW 탐지 전 INVOKEVIRTUAL(logger) + 상태 업데이트 패턴 허용 조건 추가 |
| 🟡 Medium | 메서드 오버로딩 시 바이트코드 잘못 매칭 | `mn.name.equals(method.getName())`만 비교하여 오버로딩 메서드가 있을 때 잘못된 MethodNode 분석 | 메서드 디스크립터(`mn.desc`)까지 비교하여 정확한 매핑 |
| 🟡 Medium | NoDollarExpressionRule 속성값 미탐 | `<if test="${condition}">` 등 XML 속성값 내 `${}`는 `characters()`가 아닌 `startElement()`로 전달되어 탐지 불가 | `startElement()` 오버라이드 추가하여 속성값도 검사 |
| 🟡 Medium | 암시적 JOIN 탐지 규칙 부재 | `FROM TB_USER u, TB_DEPT d` 형식의 카테시안 곱 위험 패턴이 탐지되지 않음 | `SqlBasicPerformanceRule`에 `join.isSimple()` 체크 추가 |
| 🟡 Medium | GitDiffUtil System.err 사용 | `System.err.println()` 직접 호출은 Gradle 로그 시스템과 통합되지 않음 | 예외를 호출자에게 전파하거나 `Consumer<String>` 로거 콜백 주입 |
| 🟢 Low | parseXml 예외 완전 무시 | `catch (Exception ignored)` 사용으로 파싱 실패 파일이 조용히 스킵됨 | WARN 등급 `RuleViolation` 생성하여 어떤 파일이 파싱 실패했는지 리포트에 기록 |
| 🟢 Low | Report 클래스 System.out 사용 | `printConsoleReport()`와 `saveReport()`에서 `System.out.println()` 직접 사용 | 자체 컨벤션 위반. Gradle 플러그인에서 호출 시 로그 레벨 제어 불가. SLF4J 또는 `PrintStream` 주입으로 교체 |
| 🟢 Low | NoSystemOutRule 과도한 범위 | `System.class`에 접근하는 모든 클래스를 금지하므로 `System.currentTimeMillis()`, `System.exit()` 등도 탐지 | `accessesField(System.class, "out")` 또는 `accessesField(System.class, "err")`로 범위 한정 |
| 🟢 Low | BadSqlMapper.xml 테이블에 AS 사용 | `FROM TBSI_USR AS tsu` 형식 사용. 프로젝트 컨벤션상 테이블 별칭에 AS 키워드 금지 | 테이블 별칭 AS 키워드 제거. 이를 자동 탐지하는 규칙 추가 고려 |

</step4_pros_and_cons>

<step5_interview_qna>

## 5단계: DB 성능 튜닝 관련 꼬리 질문 3개와 모범 답변

**Q1. SAX 파서 재사용 시 상태 오염 문제**
> `MybatisUnitBasedRule`은 하나의 `SAXParser` 인스턴스와 `DefaultHandler`(this) 인스턴스를 여러 XML 파일에 걸쳐 재사용합니다. 이 구조가 왜 위험하고, 어떻게 안전하게 개선할 수 있습니까?

**A1. 모범 답변:**

SAX 파서는 이벤트 기반 스트리밍 파서로, 파싱 중에 내부 상태(Locator, 파싱 위치, ContentHandler 상태 등)를 유지합니다. 동일한 `SAXParser`와 `ContentHandler` 인스턴스를 여러 파일에 재사용할 때 발생하는 문제는 두 가지입니다.

첫째, `SAXParser` 자체의 내부 상태입니다. `saxParser.reset()`을 호출하지 않으면 이전 파싱의 잔류 상태가 다음 파싱에 영향을 줄 수 있습니다.

둘째, `ContentHandler`인 `this`(규칙 클래스 인스턴스)의 인스턴스 필드 상태입니다. `MyBatisIfRule`의 `tagStack`은 파싱 중 태그 중첩을 추적하는 자료구조인데, 첫 번째 XML 파일 파싱이 비정상 종료되면 스택이 비워지지 않은 채로 두 번째 파일 파싱에 진입합니다. 결과적으로 두 번째 파일의 `<if>` 태그는 첫 번째 파일에 존재했던 `<where>` 태그가 스택에 있다고 잘못 판단하여 위반을 탐지하지 못하는 미탐(False Negative)이 발생합니다.

안전한 개선 방법은 세 가지입니다.

방법 1(권장): 파일마다 새로운 파서와 핸들러 인스턴스 생성.
```java
private void parseXml(Path xml) {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    SAXParser parser = factory.newSAXParser();
    parser.parse(xml.toFile(), this.createFreshHandler(xml));
}
```

방법 2: `parseXml()` 진입 시 `resetState()` 훅 호출하고 하위 클래스에서 재정의.

방법 3(근본적 해결): `RuleGroups` Enum이 `Supplier<Rule>`을 저장하도록 변경하여 `getRules()` 호출 시마다 새 인스턴스를 생성. Enum 싱글턴 문제와 상태 오염 문제를 동시에 해결합니다.

---

**Q2. @Transactional 메서드에서 예외를 삼키는 것이 왜 PG 도메인에서 특히 위험한가**
> `TransactionalSwallowExceptionRule`이 탐지하려는 "예외 삼킴(Exception Swallowing)" 패턴이 결제 게이트웨이 시스템에서 구체적으로 어떤 데이터 정합성 문제를 일으킬 수 있는지 설명하세요.

**A2. 모범 답변:**

Spring의 `@Transactional`은 기본적으로 `RuntimeException`과 `Error` 발생 시에만 자동 롤백을 수행합니다. 예외를 `catch`하여 삼키면 Spring AOP 프록시의 롤백 트리거 조건이 만족되지 않아 트랜잭션이 정상 커밋됩니다.

PG 도메인에서의 구체적 피해 시나리오는 다음과 같습니다.

**시나리오 1: 승인-원장 불일치**

```java
@Transactional
public void processApproval(ApprovalRequest req) {
    try {
        approvalRepository.insert(req);           // 승인 테이블 INSERT
        ledgerRepository.insert(req.toLedger());  // 원장 INSERT (여기서 예외)
    } catch (DataAccessException e) {
        log.error("원장 저장 실패", e);
        // 예외 삼킴 -> 트랜잭션 커밋
        // 결과: 승인 테이블에는 데이터가 있고 원장에는 없는 불일치 상태
    }
}
```

승인 건은 존재하지만 원장에 기록되지 않아 정산 시 손실이 발생합니다.

**시나리오 2: 취소-환불 이중 처리**

취소 요청 처리 중 환불 API 호출은 성공했으나 DB 상태 업데이트 중 예외 발생, 예외를 삼킴으로써 커밋. 이후 재시도 로직이 동일한 트랜잭션을 다시 실행하여 이중 환불이 발생합니다.

올바른 설계는 예외를 반드시 재전파하거나, 보상 트랜잭션(Compensating Transaction) 패턴을 명시적으로 구현하는 것입니다. 또한 `@Transactional(rollbackFor = Exception.class)`을 명시하여 Checked Exception도 롤백 대상에 포함시켜야 합니다.

---

**Q3. jsqlparser 파싱 실패 시 Heuristic 대체 전략의 한계**
> `SqlBasicPerformanceRule.analyzeSql()`은 jsqlparser 파싱 실패 시 `if (sql.toUpperCase().contains("SELECT *"))`로 대체 탐지합니다. 이 Heuristic 전략의 구체적인 한계와, 동적 SQL 환경에서 더 나은 대안을 제시하세요.

**A3. 모범 답변:**

현재 Heuristic 전략의 한계는 세 가지입니다.

**한계 1: 오탐(False Positive)**
SQL 주석 내에 `SELECT *`가 포함된 경우에도 탐지됩니다.
```sql
/* SELECT * FROM TB_OLD -- 레거시 쿼리 참고용 */
SELECT id, name FROM TB_USER
```

**한계 2: 미탐(False Negative)**
소문자 또는 대소문자 혼용 `select *`는 `.toUpperCase()`로 변환하므로 탐지되지만, 실제 파싱 실패 원인을 알 수 없어 다른 안티패턴은 전혀 탐지하지 못합니다.

**한계 3: MyBatis 동적 태그가 파싱 실패의 주원인**
```java
String cleanSql = sqlBuffer.toString().replaceAll("<[^>]*>", " ").trim();
```
`<if>`, `<foreach>`, `<choose>` 태그를 공백으로 치환한 후 파싱을 시도하지만, `<foreach collection="ids" item="id">#{id}</foreach>`가 `   #{id}  `로 변환되면 jsqlparser가 `#{id}`를 파싱하지 못합니다.

**더 나은 대안:**

1단계: MyBatis 전처리 정규화 강화. `#{}` 표현식을 `'placeholder'` 등 jsqlparser가 인식할 수 있는 리터럴로 대체.
```java
String normalizedSql = rawSql
    .replaceAll("#\\{[^}]+\\}", "'__PARAM__'")
    .replaceAll("\\$\\{[^}]+\\}", "'__PARAM__'")
    .replaceAll("<[^>]+>", " ")
    .trim();
```

2단계: 파싱 실패 시 빈 결과 반환 후 WARN 등급 위반 기록. 무조건 Heuristic으로 낙관적 탐지를 하는 것보다 "파싱 불가 쿼리 존재" 사실을 리포트에 명시하는 것이 신뢰도를 높입니다.

3단계: 향후 계획으로 MyBatis용 전처리 파서(`MybatisSqlPreprocessor`) 모듈 분리. `<foreach>`를 `IN ('p1', 'p2', 'p3')`으로 변환하는 등 MyBatis 특화 정규화 로직을 전담 컴포넌트로 분리하면 jsqlparser 파싱 성공률을 높일 수 있습니다.

</step5_interview_qna>
