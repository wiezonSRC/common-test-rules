package com.example.sqlanalyzer.intellij.service;

import com.example.sqlanalyzer.core.PromptGenerator;
import com.example.sqlanalyzer.core.SqlExtractor;
import com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * IntelliJ 플러그인의 분석 오케스트레이터.
 *
 * <p>core 모듈의 SqlExtractor, JdbcAnalyzer, PromptGenerator를 순서대로 호출하여
 * AI 쿼리 튜닝 프롬프트를 생성한다.
 *
 * <p>이 클래스는 IntelliJ API(Project, ToolWindow 등)에 직접 의존하지 않으므로
 * 순수 JUnit + H2로 단위 테스트가 가능하다.
 * UI 관련 처리(백그라운드 태스크, 다이얼로그)는 SqlAnalyzerPanel이 담당한다.
 */
@Slf4j
public class SqlAnalyzerService {

    /**
     * 지정된 매퍼 파일의 queryId로 AI 프롬프트를 생성한다.
     *
     * <p>호출 전 전제 조건:
     * 1. projectBasePath/{@code .sql-analyzer.properties} 파일이 존재해야 함
     * 2. 해당 properties의 jdbc.url DB에 분석 대상 테이블이 존재해야 함
     *
     * @param projectBasePath    프로젝트 루트 절대 경로 (IntelliJ의 project.getBasePath() 값)
     * @param selectedMapperFile 분석할 매퍼 XML 파일 경로
     * @param queryId            분석할 MyBatis 쿼리 ID
     * @return 생성된 AI 프롬프트 문자열
     * @throws Exception DB 연결 실패, 파일 파싱 실패, queryId 미존재 등 모든 분석 예외
     */
    public String analyze(String projectBasePath, Path selectedMapperFile, String queryId) throws Exception {
        // 1. 설정 파일 로드 (JDBC 연결 정보 + 매퍼 디렉토리)
        Path configPath = Path.of(projectBasePath, ".sql-analyzer.properties");
        SqlAnalyzerConfig config = SqlAnalyzerConfig.load(configPath);

        log.info("SQL 분석 시작 - queryId: {}, mapperFile: {}", queryId, selectedMapperFile);

        // 2. 설정의 mapper.base.dir 을 절대경로로 변환
        //    (properties에 상대경로로 저장되어 있으면 프로젝트 루트 기준으로 해석)
        //    normalize(): ".." 등 상대 경로 참조를 제거하여 실제 경로로 변환
        Path mapperDir = Path.of(projectBasePath).resolve(config.getMapperBaseDir()).normalize();

        // 3. JDBC 연결 생성 후 프롬프트 생성 (try-with-resources로 연결 자동 해제)
        // IntelliJ 플러그인은 별도 classloader를 사용하므로 DriverManager가 드라이버를
        // 자동 탐지하지 못한다. URL 기반으로 드라이버 클래스를 명시적으로 로드한다.
        loadJdbcDriver(config.getJdbcUrl());

        try (Connection connection = DriverManager.getConnection(
                config.getJdbcUrl(), config.getJdbcUser(), config.getJdbcPassword())) {

            return PromptGenerator.generatePrompt(connection, queryId, selectedMapperFile, mapperDir).toString();
        }
    }

    /**
     * JDBC URL 접두사를 보고 드라이버 클래스를 명시적으로 로드한다.
     *
     * <p>IntelliJ 플러그인의 classloader는 시스템 classloader와 분리되어 있어,
     * DriverManager의 ServiceLoader 기반 드라이버 자동 탐지가 동작하지 않는다.
     * Class.forName()으로 플러그인 classloader에서 드라이버를 직접 로드하면
     * DriverManager에 등록되어 정상 연결이 가능해진다.
     */
    private void loadJdbcDriver(String jdbcUrl) throws ClassNotFoundException {
        if (jdbcUrl.startsWith("jdbc:mariadb:") || jdbcUrl.startsWith("jdbc:mysql:")) {
            Class.forName("org.mariadb.jdbc.Driver");
        } else if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            Class.forName("org.postgresql.Driver");
        } else if (jdbcUrl.startsWith("jdbc:h2:")) {
            Class.forName("org.h2.Driver");
        } else {
            log.warn("알 수 없는 JDBC URL 형식입니다. 드라이버를 수동으로 로드하지 않습니다: {}", jdbcUrl);
        }
    }

    /**
     * 지정된 매퍼 디렉토리에서 queryId를 포함하는 파일 목록을 반환한다.
     *
     * <p>UI 레이어(SqlAnalyzerPanel)에서 파일 선택 다이얼로그 구성에 활용한다:
     * - 결과 1개 → 즉시 analyze() 호출
     * - 결과 2개 이상 → 사용자 선택 다이얼로그 표시 후 analyze() 호출
     * - 결과 0개 → 오류 메시지 표시
     *
     * @param mapperBaseDir 매퍼 XML 파일들의 루트 디렉토리
     * @param queryId       검색할 MyBatis 쿼리 ID
     * @return queryId를 포함하는 파일 경로 목록 (없으면 빈 List)
     */
    public List<Path> findMatchingFiles(Path mapperBaseDir, String queryId) throws Exception {
        return SqlExtractor.findMapperFiles(mapperBaseDir, queryId);
    }
}
