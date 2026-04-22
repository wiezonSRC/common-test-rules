package com.example.sqlanalyzer.intellij.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 프로젝트 루트의 .sql-analyzer.properties 파일을 읽어 DB 연결 정보와 매퍼 경로를 제공한다.
 *
 * <p>설정 파일 위치: {프로젝트 루트}/.sql-analyzer.properties
 *
 * <p>필수 키 (키가 아예 없으면 IllegalStateException):
 * <ul>
 *   <li>jdbc.url        - JDBC 연결 URL (예: jdbc:mariadb://localhost:3306/mydb)</li>
 *   <li>jdbc.user       - DB 사용자명</li>
 *   <li>jdbc.password   - DB 비밀번호 (빈 값 허용 — H2 등 비밀번호 없는 DB 지원)</li>
 *   <li>mapper.base.dir - 매퍼 XML 루트 디렉토리 (프로젝트 루트 기준 상대경로)</li>
 * </ul>
 *
 * <p>보안 주의: jdbc.password가 평문으로 저장되므로 .gitignore에 반드시 추가할 것.
 */
public class SqlAnalyzerConfig {

    private static final String JDBC_URL_KEY        = "jdbc.url";
    private static final String JDBC_USER_KEY       = "jdbc.user";
    private static final String JDBC_PASSWORD_KEY   = "jdbc.password";
    private static final String MAPPER_BASE_DIR_KEY = "mapper.base.dir";

    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final String mapperBaseDir;

    private SqlAnalyzerConfig(String jdbcUrl, String jdbcUser,
                              String jdbcPassword, String mapperBaseDir) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.mapperBaseDir = mapperBaseDir;
    }

    /**
     * 지정된 경로의 properties 파일을 읽어 설정 객체를 생성한다.
     *
     * @param configPath .sql-analyzer.properties 파일의 절대 경로
     * @return 파싱된 설정 객체
     * @throws IOException           파일을 열거나 읽는 데 실패한 경우
     * @throws IllegalStateException 필수 키가 파일에 없는 경우
     */
    public static SqlAnalyzerConfig load(Path configPath) throws IOException {
        Properties properties = new Properties();

        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }

        // 4개 키 모두 파일에 존재해야 함 (값은 빈 문자열 허용, 키 자체가 없으면 예외)
        String jdbcUrl        = requireKey(properties, JDBC_URL_KEY);
        String jdbcUser       = requireKey(properties, JDBC_USER_KEY);
        // password는 빈 값을 허용하므로 requireKey 사용 (null만 차단)
        String jdbcPassword   = requireKey(properties, JDBC_PASSWORD_KEY);
        String mapperBaseDir  = requireKey(properties, MAPPER_BASE_DIR_KEY);

        return new SqlAnalyzerConfig(jdbcUrl, jdbcUser, jdbcPassword, mapperBaseDir);
    }

    /**
     * 프로퍼티에서 키 값을 반환한다. 키가 없으면 IllegalStateException을 발생시킨다.
     *
     * <p>빈 문자열("")은 허용한다 — jdbc.password처럼 비밀번호가 없는 경우를 지원하기 위함.
     */
    private static String requireKey(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null) {
            throw new IllegalStateException(
                    ".sql-analyzer.properties에 필수 키가 없습니다: " + key +
                    "\n설정 파일 예시:\n" +
                    "  jdbc.url=jdbc:mariadb://localhost:3306/mydb\n" +
                    "  jdbc.user=root\n" +
                    "  jdbc.password=secret\n" +
                    "  mapper.base.dir=src/main/resources/mapper"
            );
        }

        return value.trim();
    }

    public String getJdbcUrl()       { return jdbcUrl; }
    public String getJdbcUser()      { return jdbcUser; }
    public String getJdbcPassword()  { return jdbcPassword; }
    public String getMapperBaseDir() { return mapperBaseDir; }
}
