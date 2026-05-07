package com.example.sqlanalyzer.intellij.service;

import com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfig;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.sql.*;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlAnalyzerServiceTest {

    // TestMapper.xml 에 정의된 queryId (테스트 리소스 파일과 일치)
    private static final String QUERY_ID = "findBadPerformancePayments";

    private Path tempDir;
    private Connection connection;
    private SqlAnalyzerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SqlAnalyzerService();
        tempDir = Files.createTempDirectory("sql-analyzer-service-test");

        // H2 인메모리 DB 세팅 (analyze() 호출 전 테이블이 있어야 EXPLAIN 실행 가능)
        // DB_CLOSE_DELAY=-1: 이 connection이 열려 있는 동안 DB 유지
        connection = DriverManager.getConnection("jdbc:h2:mem:svctest;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE payments (payment_id VARCHAR(50) PRIMARY KEY, order_id VARCHAR(50), amount DECIMAL(10,2), status VARCHAR(20), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE orders (order_id VARCHAR(50) PRIMARY KEY, user_id VARCHAR(50), mid VARCHAR(10))");
            stmt.execute("CREATE TABLE merchants (mid VARCHAR(10) PRIMARY KEY, merchant_name VARCHAR(100), status VARCHAR(20))");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    @DisplayName("analyze - 프롬프트에 필수 섹션 포함 여부 검증")
    void analyze_promptContainsRequiredSections() throws Exception {
        // 절대 경로 사용: JVM 워킹 디렉토리(mybatis-sql-analyzer-intellij)를 기준으로 정규화
        Path mapperFile = Path.of("../mybatis-sql-analyzer-core/src/test/resources/mapper/TestMapper.xml")
                .toAbsolutePath()
                .normalize();
        Path mapperBaseDir = mapperFile.getParent();

        // H2 JDBC 연결 정보를 SqlAnalyzerConfig 값 객체로 직접 전달
        SqlAnalyzerConfig config = new SqlAnalyzerConfig(
                "jdbc:h2:mem:svctest;DB_CLOSE_DELAY=-1", "sa", "");

        String prompt = service.analyze(config, mapperFile, mapperBaseDir, QUERY_ID);

        assertNotNull(prompt);
        assertTrue(prompt.contains("[Original Query]"), "원본 쿼리 섹션 없음");
        assertTrue(prompt.contains("[Remove Tag Query]"), "fakeSql 섹션 없음");
        assertTrue(prompt.contains("[Explain]"), "Explain 섹션 없음");
        assertTrue(prompt.contains("[MetaData]"), "MetaData 섹션 없음");
        assertTrue(prompt.contains("[요청 사항]"), "요청 사항 섹션 없음");
    }

    @Test
    @DisplayName("findMatchingFiles - queryId 포함 파일 목록 반환")
    void findMatchingFiles_returnsNonEmptyList() throws Exception {
        Path mapperDir = Path.of("../mybatis-sql-analyzer-core/src/test/resources/mapper")
                .toAbsolutePath()
                .normalize();
        List<Path> files = service.findMatchingFiles(mapperDir, QUERY_ID);

        assertFalse(files.isEmpty(), "매퍼 파일 목록이 비어있으면 안 됩니다.");
        assertEquals(4, files.size(), "테스트 매퍼 4개 파일에 findBadPerformancePayments 가 있어야 합니다.");
    }

    @Test
    @DisplayName("findMatchingFiles - 존재하지 않는 queryId 는 빈 리스트 반환")
    void findMatchingFiles_unknownQueryId_returnsEmpty() throws Exception {
        Path mapperDir = Path.of("../mybatis-sql-analyzer-core/src/test/resources/mapper")
                .toAbsolutePath()
                .normalize();
        List<Path> files = service.findMatchingFiles(mapperDir, "nonExistentQueryId_XYZ");

        assertTrue(files.isEmpty());
    }

    @Test
    @DisplayName("listSubDirectories - 존재하지 않는 디렉토리는 \".\" 만 반환")
    void listSubDirectories_nonExistentDir_returnsRootOnly() throws Exception {
        Path nonExistent = tempDir.resolve("not-here");
        List<String> dirs = service.listSubDirectories(nonExistent);

        assertEquals(1, dirs.size());
        assertEquals(".", dirs.get(0));
    }

    @Test
    @DisplayName("listXmlFiles - 존재하지 않는 디렉토리는 빈 리스트 반환")
    void listXmlFiles_nonExistentDir_returnsEmpty() throws Exception {
        Path nonExistent = tempDir.resolve("not-here");
        List<String> files = service.listXmlFiles(nonExistent);

        assertTrue(files.isEmpty());
    }
}
