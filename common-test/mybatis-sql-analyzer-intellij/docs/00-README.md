# mybatis-sql-analyzer-intellij 학습 가이드

이 디렉토리는 `mybatis-sql-analyzer-intellij` 모듈의 소스 코드를 이해하기 위한 단계별 학습 문서를 담고 있다.

---

## 학습 순서

| 순서 | 문서 | 핵심 내용 |
|---|---|---|
| 1 | [01-intellij-plugin-basics.md](01-intellij-plugin-basics.md) | IntelliJ 플러그인이란? `plugin.xml` 구조, 빌드/설치 방법 |
| 2 | [02-intellij-api.md](02-intellij-api.md) | 코드에서 사용하는 IntelliJ API (`ToolWindow`, `AnAction`, `VirtualFile` 등) |
| 3 | [03-threading-model.md](03-threading-model.md) | EDT/BGT 스레드 모델, `ProgressManager`, `invokeAndWait` |
| 4 | [04-swing-ui.md](04-swing-ui.md) | Swing 레이아웃, `JPanel`, `JComboBox`, `GridBagLayout` 등 |
| 5 | [05-xml-and-jdbc.md](05-xml-and-jdbc.md) | DOM XML 파싱, JDBC 기초, XXE 보안 설정, `EXPLAIN` |
| 6 | [06-code-walkthrough.md](06-code-walkthrough.md) | 전체 실행 흐름, 클래스별 코드 상세 설명 |

---

## 이 플러그인이 하는 일 (한 줄 요약)

> MyBatis 매퍼 XML에서 queryId를 선택하면, 실제 DB의 `EXPLAIN`과 테이블 스키마 정보를 수집하여 AI 쿼리 튜닝 프롬프트를 자동 생성한다.

## 모듈 의존 관계

```
mybatis-sql-analyzer-intellij   ← 이 모듈 (IntelliJ Plugin)
  │
  └── mybatis-sql-analyzer-core  (분석 로직: SqlExtractor, JdbcAnalyzer, PromptGenerator)
```

## 사용 전 설정 (필수)

프로젝트 루트에 `.sql-analyzer.properties` 파일이 있어야 한다.

```properties
jdbc.url=jdbc:mariadb://localhost:3306/mydb
jdbc.user=root
jdbc.password=secret
mapper.base.dir=src/main/resources/mapper
```
