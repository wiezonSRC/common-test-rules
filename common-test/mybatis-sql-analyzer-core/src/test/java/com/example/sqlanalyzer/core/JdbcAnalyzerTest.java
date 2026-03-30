package com.example.sqlanalyzer.core;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.junit.jupiter.api.*;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.sql.*;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAnalyzerTest {

    private Connection connection;
    private final String queryId = "findBadPerformancePayments";
    private final String mapperPath = "src/test/resources/mapper/TestMapper.xml";

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
    void doExplain() throws ParserConfigurationException, IOException, SAXException, SQLException {
        Node queryIdDetail = SqlExtractorTest.getQueryIdDetail(queryId, mapperPath);
        String namespace = queryIdDetail.getOwnerDocument().getDocumentElement().getAttribute("namespace");
        String fakeSql = SqlExtractorTest.buildFakeSql(queryIdDetail, true, namespace);
        ResultSet rs = null;

        if(fakeSql != null){
            try(Statement stmt = connection.createStatement()){
                String sql = "EXPLAIN " + fakeSql;


                rs = stmt.executeQuery(sql);
                StringBuilder result = new StringBuilder();
                for(int i = 1; rs.next(); i++){
                    result.append(rs.getString(i));
                }

                assertNotNull(result);
            }finally{
                if(rs != null) rs.close();
            }

        }
    }


    @Test
    @DisplayName("Table 추출")
    void extractTables() throws Exception{
        Set<String> tables = extractTableMethod(queryId, mapperPath);

        System.out.println(tables);
        assertEquals(3, tables.size());
    }



    @Test
    @DisplayName("추출된 Table에 대한 DDL 및 Index 정보 찾기")
    void extractDDLAndIndex() throws Exception{
        Set<String> tables = extractTableMethod(queryId, mapperPath);
        //TODO) 기록 필요) DB 마다 다른 명령어들로 인해 >> JAVA metaData 추출 사용
        DatabaseMetaData metaData = connection.getMetaData();

        StringBuilder result = getSchmemaInfo(tables, metaData);
        System.out.println(result);
        Assertions.assertNotNull(result);

    }

    public static StringBuilder getSchmemaInfo(Set<String> tables, DatabaseMetaData metaData) throws SQLException {
        StringBuilder result = new StringBuilder();

        for(String table : tables){
            String targetTable = table.toUpperCase();

            result.append("============================\n");
            result.append("[TABLE INFO] : ").append(targetTable).append("\n");

            // 2. 테이블 컬럼 정보 추출 (쿼리 실행 대신 getColumns API 사용)
            try (ResultSet rs = metaData.getColumns(null, null, targetTable, null)) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String typeName = rs.getString("TYPE_NAME");
                    String columnSize = rs.getString("COLUMN_SIZE");

                    result.append(String.format(" - %s (Type: %s, Size: %s)\n",
                            columnName, typeName, columnSize));
                }
            }

            result.append("\n[INDEX INFO] : ").append(targetTable).append("\n");

            // 3. 테이블 인덱스 정보 추출 (getIndexInfo API 사용)
            // 파라미터: catalog, schema, table, unique(false면 모든 인덱스), approximate
            try (ResultSet rs = metaData.getIndexInfo(null, null, targetTable, false, false)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");

                    // 테이블 기본 통계 정보(IndexName이 null)는 건너뜁니다.
                    if (indexName == null) continue;

                    String columnName = rs.getString("COLUMN_NAME");
                    boolean isUnique = !rs.getBoolean("NON_UNIQUE");

                    result.append(String.format(" - Index: %s, Column: %s, Unique: %s\n",
                            indexName, columnName, isUnique));
                }
            }
            result.append("============================\n\n");
        }
        return result;
    }

    public static Set<String> extractTableMethod(String queryId, String mapperPath) throws ParserConfigurationException, SAXException, IOException, JSQLParserException {
        Node queryIdDetail = SqlExtractorTest.getQueryIdDetail(queryId, mapperPath);
        String namespace = ((org.w3c.dom.Element) queryIdDetail.getOwnerDocument().getDocumentElement()).getAttribute("namespace");
        String fakeSql = SqlExtractorTest.buildFakeSql(queryIdDetail, true, namespace);

        net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(fakeSql);
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        Set<String> tables = tablesNamesFinder.getTables(stmt);
        return tables;
    }
}