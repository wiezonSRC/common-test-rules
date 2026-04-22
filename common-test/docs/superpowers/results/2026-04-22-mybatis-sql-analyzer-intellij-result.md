# MyBatis SQL Analyzer IntelliJ 플러그인 — 구현 결과

**완료일**: 2026-04-22

## 완료된 작업

- [x] Task 1: SqlExtractor.findMapperFiles() 추가 — queryId로 매퍼 파일 검색
- [x] Task 2: SqlExtractor static field 제거 — 테스트 격리 보장
- [x] Task 3: JdbcAnalyzer.getExplainInfo() 버그 수정 — ResultSet 컬럼 파싱 교정
- [x] Task 4: PromptGenerator mapperPath String→Path 타입 통일
- [x] Task 5: IntelliJ 모듈 골격 생성 (org.jetbrains.intellij.platform 2.6.0, Gradle 9 호환)
- [x] Task 6: SqlAnalyzerConfig — .sql-analyzer.properties 파싱 (TDD, 3 tests)
- [x] Task 7: SqlAnalyzerService — core 모듈 오케스트레이션 (TDD, 3 tests)
- [x] Task 8: XmlQueryIdReader — 매퍼 XML DML id 추출 유틸리티
- [x] Task 9: SqlAnalyzerPanel — Tool Window UI (GridBagLayout, ProgressManager, 클립보드)
- [x] Task 10: SqlAnalyzerToolWindow + AnalyzeSqlAction 구현
- [x] Task 11: 전체 빌드 검증

## 빌드 결과

### :mybatis-sql-analyzer-core:build
```
BUILD SUCCESSFUL in 1s
5 actionable tasks: 5 up-to-date
```

### :mybatis-sql-analyzer-intellij:build
```
BUILD SUCCESSFUL in 17s
16 actionable tasks: 1 executed, 15 up-to-date
```

### :mybatis-sql-analyzer-intellij:buildPlugin
```
BUILD SUCCESSFUL in 40s
16 actionable tasks: 6 executed, 10 up-to-date
```

**플러그인 배포 파일**: `mybatis-sql-analyzer-intellij/build/distributions/mybatis-sql-analyzer-intellij-0.1.0.zip`

## 주요 변경 사항 및 결정 사항

1. **IntelliJ Gradle Plugin 버전**: 스펙의 1.17.4 대신 2.6.0 사용 — Gradle 9.0.0에서 1.x가 제거된 `DefaultArtifactPublicationSet` API에 의존하여 빌드 불가
2. **Windows 경로 처리**: SqlAnalyzerServiceTest에서 Properties 파일에 경로 작성 시 백슬래시를 슬래시로 변환 — Java Properties.load()가 `\t`, `\n` 등을 이스케이프 시퀀스로 해석
3. **테스트 경로 설정**: analyze() 테스트에서 상대경로 대신 절대경로 사용 — tempDir 기반 projectBasePath와 실제 mapper 파일 경로가 다른 디렉토리에 위치

## 수동 검증 필요 항목 (runIde)

1. IDE 우측에 "MyBatis SQL Analyzer" Tool Window 표시 확인
2. TestMapper.xml 우클릭 → "Analyze SQL with AI" 메뉴 노출 확인
3. 클릭 시 Tool Window 자동 세팅 (파일 경로, queryId 드롭다운)
4. ".sql-analyzer.properties" 읽어 DB 연결 후 프롬프트 생성 확인
5. "Copy to Clipboard" 동작 확인

## 잔여 이슈 / 향후 작업

- [ ] `runIde` 수동 검증 (별도 진행 필요)
- [ ] FileChooserDescriptorFactory.createSingleFolderDescriptor() deprecation 경고 해소 (기능적 문제는 아님)
- [ ] .sql-analyzer.properties를 .gitignore에 추가 (이번 커밋에서 완료)
