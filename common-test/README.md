# Common Test Rules (Gemini CLI Custom Plugin)

**Common Test Rules**는 Java 및 MyBatis 환경에서 코드 품질과 SQL 안전성을 자동으로 검증하고 교정하기 위한 도구입니다. 이 프로젝트는 핵심 검증 엔진인 `rule-core`와 이를 Gradle 환경에 통합한 `rule-gradle-plugin`으로 구성되어 있습니다.

## 🚀 주요 기능

- **Java 정적 분석 (ArchUnit 기반):** 필드 주입 금지, System.out 사용 제한, 트랜잭션 내 예외 처리 누락 방지 등.
- **MyBatis SQL 검증 (SAX/JSqlParser 기반):** `${}` 사용 제한(SQL Injection 방지), 부등호 CDATA 처리, SELECT * 지양 등.
- **코드 포맷팅 자동화 (Spotless 통합):** Java 스타일 가이드 적용 및 SQL 예약어(SELECT, FROM 등) 자동 대문자 변환.
- **증분 검사 지원:** Git Diff를 활용하여 변경된 파일만 효율적으로 검사.

---

## 🛠 설치 및 설정

### 1. 플러그인 적용
프로젝트의 `build.gradle`에 플러그인을 설정합니다. (로컬 테스트 시 `mavenLocal()` 저장소 설정이 필요합니다.)

```gradle
plugins {
    id "com.company.rule" version "0.1.3"
}

repositories {
    mavenLocal()
    mavenCentral()
}

rule {
    // [필수] 검사 대상 베이스 패키지
    basePackage = "com.rule.commontest.sample" 
    
    // [선택] 실행할 규칙 그룹 (ALL(기본값) / JAVA_CRITICAL / SQL_CRITICAL)
    ruleGroupName = "ALL"
    
    // [선택] 변경된 파일만 검사할지 여부 (기본값: true)
    incremental = true
    
    // [선택] 위반 사항 발견 시 빌드 실패 처리 여부 (기본값: false)
    failOnViolation = false
    
    // [선택] Spotless 포맷터 적용 여부 (기본값: true)
    enableFormatter = true
    
    // [선택] MyBatis 매퍼 파일 위치 (기본값: ["src/main/resources/mapper"])
    mapperPaths = ["src/main/resources/mapper"]
    
    // [선택] 증분 검사 기준 브랜치/태그 (기본값: 'HEAD')
    ratchetFrom = 'main'
}
```

### 2. 로컬 Maven 배포
플러그인이나 코어를 수정 후 적용하려면 로컬 저장소에 먼저 배포해야 합니다.

```bash
# rule-core 배포
./gradlew :rule-core:publishToMavenLocal

# rule-gradle-plugin 배포
./gradlew :rule-gradle-plugin:publishToMavenLocal
```

---

## 📖 사용 방법

Gradle 태스크를 통해 포맷팅과 규칙 검사를 수행합니다.

| 명령어 | 설명 |
| --- | --- |
| `./gradlew checkAll` | **[추천]** 포맷팅 적용(`spotlessApply`) 후 규칙 검사(`ruleCheck`)를 통합 수행합니다. |
| `./gradlew ruleCheck` | 설정된 규칙 그룹에 따라 코드 및 SQL 위반 사항을 점검합니다. |
| `./gradlew spotlessApply` | 코드 스타일 정렬 및 SQL 예약어 대문자 변환을 즉시 적용합니다. |
| `./gradlew spotlessCheck` | 코드 스타일 위반 여부만 확인합니다. (CI 환경 권장) |

---

## 📦 제공되는 주요 규칙 (Rules)

### Java Rules
- **NoSystemOutRule:** `System.out.println` 등 표준 출력 사용 금지 (Logger 권장).
- **NoFieldInjectionRule:** `@Autowired`를 통한 필드 주입 금지 (생성자 주입 권장).
- **NoGenericCatchRule:** `Exception`, `RuntimeException` 등 포괄적 예외 catch 지양.
- **TransactionalSwallowExceptionRule:** `@Transactional` 메서드 내에서 예외를 삼키고(swallow) 다시 던지지 않는 패턴 탐지.

### SQL Rules
- **NoDollarExpressionRule:** MyBatis 내 `${}` 사용 금지 (SQL Injection 위험).
- **MyBatisIfRule:** `<if>` 태그가 `<where>`, `<set>` 등 안전한 태그 외부에 단독 사용되는 것 방지.
- **SqlBasicPerformanceRule:** `SELECT *` 사용 및 SELECT 절 내 스칼라 서브쿼리 사용 제한.
- **MyBatisXmlRule:** XML 내 부등호(`<`, `>`) 사용 시 `<![CDATA[ ]]>` 적용 여부 검사.

---

## 📊 검사 결과 리포트

`ruleCheck` 실행 시 `build/reports/rule/` (또는 프로젝트 루트의 `reports/rule/`) 경로에 결과 파일이 생성됩니다.

- **`summary-report_yyyyMMdd.json`:** 실행 시간, 성공 여부, 위반 개수 요약.
- **`fail-report_yyyyMMdd.json`:** 수정이 필수적인 `FAIL` 등급 위반 상세 내역.
- **`warn-report_yyyyMMdd.json`:** 권장 수정 사항인 `WARN` 등급 위반 상세 내역.

---

## 🏗 아키텍처 (Architecture)

1. **전략 패턴 (Strategy):** `Rule` 인터페이스를 통해 다양한 검증 전략을 유연하게 확장.
2. **템플릿 메서드 패턴:** `ArchUnitBasedRule`, `MybatisUnitBasedRule`을 통해 공통 실행 흐름 정의.
3. **증분 분석 (Git Integration):** `GitDiffUtil`을 통해 전체 스캔의 비효율성을 제거하고 변경분만 타겟팅.
4. **리포팅 엔진:** Jackson을 활용한 구조화된 JSON 리포트 생성.
