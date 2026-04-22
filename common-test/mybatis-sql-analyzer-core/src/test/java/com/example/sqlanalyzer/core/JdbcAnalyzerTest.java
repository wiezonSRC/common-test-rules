package com.example.sqlanalyzer.core;

import org.junit.jupiter.api.*;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAnalyzerTest {

    private Connection connection;
    private final String queryId = "findBadPerformancePayments";
    private final Path mapperPath = Path.of("src/test/resources/mapper/TestMapper.xml");
    private final Path mapperPathDir = Path.of("src/test/resources/mapper");

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
    void remove() throws Exception{
        if(connection != null && !connection.isClosed()){
            try(Statement stmt = connection.createStatement()){
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    // sql 문장 필요

    @Test
    @DisplayName("EXPLAIN 실행")
    void doExplain() throws ParserConfigurationException, IOException, SAXException, SQLException, TransformerException {
        Node queryIdDetail = SqlExtractor.getQueryIdDetail(queryId, mapperPath);
        Map<String, String> sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperPathDir);
        String namespace;
        String fakeSql = null;

        if (queryIdDetail != null) {
            namespace = queryIdDetail.getOwnerDocument().getDocumentElement().getAttribute("namespace");
            fakeSql = SqlExtractor.buildFakeSql(queryIdDetail, true, namespace, sqlSnippetRegistry);
        }


        if(fakeSql != null){
            String explainInfo = JdbcAnalyzer.getExplainInfo(connection, fakeSql);
            assertNotNull(explainInfo);
        }
    }


    @Test
    @DisplayName("getExplainInfo - 결과에 컬럼 헤더와 구분선이 포함되어야 함")
    void getExplainInfo_includesColumnHeaderAndSeparator() throws Exception {
        // H2의 EXPLAIN은 PLAN 컬럼을 가진 단일 컬럼 결과를 반환하므로
        // 헤더 행 + 구분선 + 데이터 행의 구조인지 검증
        String fakeSql = "SELECT p.payment_id, p.amount FROM payments p WHERE p.amount > ?";
        String result = JdbcAnalyzer.getExplainInfo(connection, fakeSql);

        assertNotNull(result);
        assertFalse(result.isBlank(), "EXPLAIN 결과가 비어있으면 안 됩니다.");
        assertTrue(result.contains("-"), "컬럼 헤더와 데이터 사이의 구분선이 있어야 합니다.");
        assertTrue(result.lines().count() >= 3, "헤더 행 + 구분선 + 데이터 행 최소 3줄이어야 합니다.");
    }


    @Test
    @DisplayName("Table 추출")
    void extractTables() throws Exception{
        Node queryIdDetail = SqlExtractor.getQueryIdDetail(queryId, mapperPath);
        Map<String, String> sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperPathDir);

        String namespace;
        String fakeSql = null;
        if (queryIdDetail != null) {
            namespace = queryIdDetail.getOwnerDocument().getDocumentElement().getAttribute("namespace");
            fakeSql = SqlExtractor.buildFakeSql(queryIdDetail, true, namespace, sqlSnippetRegistry);
        }

        Set<String> tables = JdbcAnalyzer.extractTableMethod(fakeSql);

        System.out.println(tables);
        assertEquals(3, tables.size());
    }



    @Test
    @DisplayName("추출된 Table에 대한 DDL 및 Index 정보 찾기")
    void extractDDLAndIndex() throws Exception{
        Node queryIdDetail = SqlExtractor.getQueryIdDetail(queryId, mapperPath);
        Map<String, String> sqlSnippetRegistry = SqlExtractor.getSqlSnippetRegistry(mapperPathDir);

        String namespace;
        String fakeSql = null;
        if (queryIdDetail != null) {
            namespace = queryIdDetail.getOwnerDocument().getDocumentElement().getAttribute("namespace");
            fakeSql = SqlExtractor.buildFakeSql(queryIdDetail, true, namespace, sqlSnippetRegistry);
        }

        Set<String> tables = JdbcAnalyzer.extractTableMethod(fakeSql);
        DatabaseMetaData metaData = connection.getMetaData();

        StringBuilder result = JdbcAnalyzer.getMetaDataInfo(tables, metaData);
        System.out.println(result);
        Assertions.assertNotNull(result);

    }

}