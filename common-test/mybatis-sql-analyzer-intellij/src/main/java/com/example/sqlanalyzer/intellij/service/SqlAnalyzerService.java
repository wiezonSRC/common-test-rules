package com.example.sqlanalyzer.intellij.service;

import com.example.sqlanalyzer.core.PromptGenerator;
import com.example.sqlanalyzer.core.SqlExtractor;
import com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * <p>DB 연결 정보는 DbSettingsDialog가 IntelliJ PropertiesComponent에 저장한 값을
     * SqlAnalyzerConfig 값 객체로 전달받는다. 파일 기반 설정(.sql-analyzer.properties)은
     * 사용하지 않는다.
     *
     * @param config             DB 연결 정보 값 객체 (jdbcUrl, jdbcUser, jdbcPassword)
     * @param selectedMapperFile 분석할 매퍼 XML 파일 경로
     * @param mapperBaseDir      매퍼 XML 파일들의 루트 디렉토리 (상대 경로 해석 기준)
     * @param queryId            분석할 MyBatis 쿼리 ID
     * @return 생성된 AI 프롬프트 문자열
     * @throws Exception DB 연결 실패, 파일 파싱 실패, queryId 미존재 등 모든 분석 예외
     */
    public String analyze(SqlAnalyzerConfig config, Path selectedMapperFile,
                          Path mapperBaseDir, String queryId) throws Exception {
        log.info("SQL 분석 시작 - queryId: {}, mapperFile: {}", queryId, selectedMapperFile);

        // JDBC 연결 생성 후 프롬프트 생성 (try-with-resources로 연결 자동 해제)
        // IntelliJ 플러그인은 별도 classloader를 사용하므로 DriverManager가 드라이버를
        // 자동 탐지하지 못한다. URL 기반으로 드라이버 클래스를 명시적으로 로드한다.
        loadJdbcDriver(config.getJdbcUrl());

        try (Connection connection = DriverManager.getConnection(
                config.getJdbcUrl(), config.getJdbcUser(), config.getJdbcPassword())) {

            return PromptGenerator.generatePrompt(
                    connection, queryId, selectedMapperFile, mapperBaseDir).toString();
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
     * @param mapperBaseDir 매퍼 XML 파일들의 루트 디렉토리
     * @param queryId       검색할 MyBatis 쿼리 ID
     * @return queryId를 포함하는 파일 경로 목록 (없으면 빈 List)
     */
    public List<Path> findMatchingFiles(Path mapperBaseDir, String queryId) throws Exception {
        return SqlExtractor.findMapperFiles(mapperBaseDir, queryId);
    }

    /**
     * 매퍼 베이스 디렉토리의 직속 서브디렉토리 목록을 반환한다.
     * 첫 번째 항목은 항상 "." (루트 자신)이며, 이후 알파벳순 정렬된 서브디렉토리명이 따른다.
     */
    public List<String> listSubDirectories(Path mapperBaseDir) throws IOException {
        List<String> dirs = new ArrayList<>();
        dirs.add(".");  // 루트(기본) 항상 첫 항목

        if (!Files.isDirectory(mapperBaseDir)) {
            return dirs;
        }

        try (Stream<Path> stream = Files.list(mapperBaseDir)) {
            stream.filter(Files::isDirectory)
                  .map(p -> p.getFileName().toString())
                  .sorted()
                  .forEach(dirs::add);
        }

        return dirs;
    }

    /**
     * 지정된 디렉토리에서 직속 XML 파일 이름 목록을 알파벳순으로 반환한다.
     * 하위 디렉토리는 포함하지 않는다.
     */
    public List<String> listXmlFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".xml"))
                         .map(p -> p.getFileName().toString())
                         .sorted()
                         .collect(Collectors.toList());
        }
    }
}
