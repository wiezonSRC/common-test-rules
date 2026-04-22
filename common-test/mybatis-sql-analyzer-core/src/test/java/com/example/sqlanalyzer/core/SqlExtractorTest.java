package com.example.sqlanalyzer.core;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.junit.jupiter.api.*;
import org.w3c.dom.Node;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlExtractorTest {

    private Connection connection;
    private final String queryId = "findBadPerformancePayments";
    private final String mapperPath = "src/test/resources/mapper/TestMapper.xml";
    private final Path mapperBaseDir = Path.of("src/test/resources/mapper");

    @BeforeEach
    void set() throws Exception {
        // H2 메모리 DB 설정 (테이블 및 인덱스 생성)
        connection = DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE payments (payment_id VARCHAR(50) PRIMARY KEY, order_id VARCHAR(50) NOT NULL, amount DECIMAL(10, 2) NOT NULL, status VARCHAR(20) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE orders (order_id VARCHAR(50) PRIMARY KEY, user_id VARCHAR(50) NOT NULL, mid VARCHAR(10) NOT NULL)");
            stmt.execute("CREATE TABLE merchants (mid VARCHAR(10) PRIMARY KEY, merchant_name VARCHAR(100) NOT NULL, status VARCHAR(20) DEFAULT 'ACTIVE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE refunds (refund_id VARCHAR(50) PRIMARY KEY, payment_id VARCHAR(50) NOT NULL, refund_amount DECIMAL(10, 2) NOT NULL, reason VARCHAR(255), refunded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            
            stmt.execute("CREATE INDEX payments_amount_idx ON payments(amount)");
            stmt.execute("CREATE INDEX orders_user_id_idx ON orders(user_id)");
            stmt.execute("CREATE INDEX idx_merchants_status ON merchants(status)");
            stmt.execute("CREATE INDEX idx_refunds_payment_id ON refunds(payment_id)");
        }
    }
    @AfterEach
    void remove() throws Exception {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    @Test
    @DisplayName("Dynamic Query 추출")
    void findDynamicQuery() throws Exception {
        Node queryIdNode = SqlExtractor.getQueryIdDetail(queryId, mapperPath);
        if (queryIdNode != null) {
            assertNotNull(queryIdNode.getTextContent());
        }else{
            fail();
        }
    }

    // 핵심 core 로직

    @Test
    @DisplayName("동적 쿼리 >> SQL 변경")
    void dynamicQueryChangeToSql() throws Exception {

        Node nodeList = SqlExtractor.getQueryIdDetail(queryId, mapperPath);
        Map<String, String> sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperBaseDir);

        // [mybatis 태그 확인]
        // explain용 fakeSql
        String namespace;
        String fakeSql = null;
        if (nodeList != null) {
            namespace = nodeList.getOwnerDocument().getDocumentElement().getAttribute("namespace");
            fakeSql = SqlExtractor.buildFakeSql(nodeList, true, namespace, sqlSnippetRegistry);
        }

        // 3. When: JSqlParser를 이용해 파싱 및 테이블명 추출
        net.sf.jsqlparser.statement.Statement statement = null;
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        if (fakeSql != null) {
            statement = CCJSqlParserUtil.parse(fakeSql);
        }
        Set<String> tableNames = new HashSet<>(tablesNamesFinder.getTableList(statement));

        // 4. Then: 최종 결과 검증
        Assertions.assertEquals(3, tableNames.size());

    }

    @Test
    @DisplayName("refId <sql> 캐싱하기")
    void getRefIdCache() throws Exception {
        Map<String, String> sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperBaseDir);
        Assertions.assertFalse(sqlSnippetRegistry.isEmpty());
    }

    @Test
    @DisplayName("findMapperFiles - 여러 파일에 걸쳐 queryId 검색 성공")
    void findMapperFiles_multipleFiles() throws Exception {
        List<Path> result = SqlExtractor.findMapperFiles(mapperBaseDir, queryId);

        List<String> fileNames = result.stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();

        assertEquals(4, result.size());
        assertEquals(List.of("SampleMapper.xml", "TestMapper.xml", "TestMapper2.xml", "TestMapper3.xml"), fileNames);
    }

    @Test
    @DisplayName("findMapperFiles - 존재하지 않는 queryId 는 빈 리스트 반환")
    void findMapperFiles_notFound() throws Exception {
        List<Path> result = SqlExtractor.findMapperFiles(mapperBaseDir, "nonExistentQueryId");
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findMapperFiles - 존재하지 않는 디렉토리는 빈 리스트 반환")
    void findMapperFiles_directoryNotFound() throws Exception {
        List<Path> result = SqlExtractor.findMapperFiles(Path.of("non/existent/path"), queryId);
        assertTrue(result.isEmpty());
    }

}