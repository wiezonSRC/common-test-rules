# Project: MyBatis SQL AI Performance Analyzer (Pivot from Common-Test-Rules)

## 1. 프로젝트 배경 및 피벗 동기
- **기존 목표:** SonarQube를 대체하는 범용 정적 분석 툴 (Java + SQL).
- **문제점:** SonarQube 도입으로 범용 규칙(Java)의 효용성 감소, 복잡한 SQL 성능 규칙을 코드로 일일이 구현하는 노가다 발생.
- **해결책:** SonarQube가 못하는 '컨텍스트 기반 SQL 성능 진단'에 집중. AI가 분석하기 최적인 "완벽한 프롬프트"를 자동 생성하는 도구로 피벗.

## 2. 핵심 아키텍처 및 전략
- **Git Diff 연동:** 수정된 MyBatis XML 파일과 특정 SQL ID만 식별하여 분석 대상을 최소화.
- **DB 직접 연동 (JDBC):** `schema.txt` 같은 정적 파일 대신, 실제 개발 DB에 연결하여 최신 스키마 및 인덱스 정보를 수집.
- **EXPLAIN 활용:** MyBatis의 동적 태그를 제거하고 파라미터(`#{...}`)를 더미 값으로 치환하여 DB에서 직접 `EXPLAIN`을 실행, 그 결과(실행 계획)를 확보.
- **AI 프롬프트 생성:** [수정된 SQL + 테이블 스키마 + 인덱스 정보 + EXPLAIN 결과]를 조합하여 개발자가 AI(ChatGPT, Gemini 등)에게 바로 던질 수 있는 Markdown 프롬프트 생성.

## 3. 기술적 세부 사항
- **`rule-core`:**
    - `GitDiffUtil`: 변경된 파일/SQL 식별.
    - `JdbcMetadataCollector`: JDBC를 통한 테이블/인덱스 메타데이터 추출.
    - `SqlSimplifier`: MyBatis 동적 쿼리 평탄화 및 더미 파라미터 주입.
    - `JdbcExplainRunner`: 실시간 `EXPLAIN` 실행 및 결과 파싱.
- **`rule-gradle-plugin`:**
    - DB 연결 정보(URL, User, Pwd)를 받는 Extension 구현.
    - `generateSqlPrompt` 태스크를 통해 `build/reports/sql-ai-prompt.md` 생성.

## 4. 향후 작업 로드맵 (Action Items)
1. **정리:** 불필요한 Java 관련 규칙 및 테스트 코드 삭제 (SonarQube 중복 방지).
2. **구현:** JDBC 기반의 메타데이터 수집 및 EXPLAIN 실행 로직 개발.
3. **고도화:** MyBatis XML에서 테이블명을 추출하고 쿼리를 단순화하는 파서 개발.
4. **리포팅:** 최종 프롬프트 템플릿 설계 및 Gradle 플러그인 태스크 연결.

## 5. 비전
- 개발자가 "SQL이 돌아가기만 하면 된다"는 생각에서 벗어나, AI의 도움을 받아 `EXPLAIN`을 생활화하고 성능 최적화를 자연스럽게 수행하도록 돕는 UX 제공.
- 추후 사내 AI API와 연동하여 프롬프트 복사-붙여넣기 과정을 자동화할 수 있는 확장성 확보.
