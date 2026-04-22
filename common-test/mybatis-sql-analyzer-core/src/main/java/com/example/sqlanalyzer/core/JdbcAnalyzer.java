package com.example.sqlanalyzer.core;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.sql.*;
import java.util.Set;

@Slf4j
public class JdbcAnalyzer {

    private JdbcAnalyzer() {}

    // table + index 정보 추출
    public static StringBuilder getMetaDataInfo(Set<String> tables, DatabaseMetaData metaData) throws SQLException {
        StringBuilder result = new StringBuilder();

        // 데이터베이스 정보 저장
        result.append("==============================\n");
        result.append("[DATABASE INFO] : ").append("(ProductName)").append(metaData.getDatabaseProductName()).append("\n");
        result.append("(ProductVersion)").append(metaData.getDatabaseProductVersion()).append("\n");
        result.append("(DriverName)").append(metaData.getDriverName()).append("\n");
        result.append("(DriverVersion)").append(metaData.getDriverVersion()).append("\n");



        if (metaData.getDatabaseProductName().equalsIgnoreCase("MariaDB") ||
                metaData.getDatabaseProductName().equalsIgnoreCase("MySQL")) {

            Connection connection = metaData.getConnection();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW VARIABLES WHERE Variable_name IN ('tx_isolation', 'transaction_isolation', 'sql_mode')")) {
                while (rs.next()) {
                    result.append(rs.getString("Variable_name")).append(rs.getString("Value"));
                }
            }
        }

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

                    result.append(" - ")
                            .append(columnName)
                            .append(" (Type: ")
                            .append(typeName)
                            .append(", Size: ")
                            .append(columnSize)
                            .append(")\n");
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

                    result.append(" - Index: ")
                            .append(indexName)
                            .append(", Column: ")
                            .append(columnName)
                            .append(", Unique: ")
                            .append(isUnique)
                            .append("\n");
                }
            }
            result.append("============================\n\n");
        }
        return result;
    }

    // 해당 쿼리에 사용된 테이블 리스트 추출
    public static Set<String> extractTableMethod(String fakeSql) throws JSQLParserException {


        net.sf.jsqlparser.statement.Statement stmt = null;
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();

        if (fakeSql != null) {
            stmt = CCJSqlParserUtil.parse(fakeSql);
            return tablesNamesFinder.getTables(stmt);
        }

        return Set.of();
    }

    /**
     * 지정된 SQL에 대한 EXPLAIN 실행 결과를 반환한다.
     *
     * <p>EXPLAIN은 DB 실행 계획을 보여주며, 인덱스 활용 여부, Full Table Scan 여부 등
     * 성능 분석에 필요한 정보를 포함한다.
     * 결과는 '컬럼헤더 | 구분선 | 데이터 행' 형태의 텍스트로 반환한다.
     *
     * <p>이전 구현의 버그: {@code for (int i=1; rs.next(); i++) { rs.getString(i) }} 형태는
     * i가 행 번호인데 컬럼 인덱스로 오용됨. MySQL EXPLAIN처럼 여러 컬럼이 있는 경우
     * 첫 번째 행의 첫 번째 컬럼만 출력되고 이후 데이터가 모두 누락되었음.
     *
     * @param connection DB 연결 객체 (호출자가 생명주기를 관리해야 함 — try-with-resources 권장)
     * @param fakeSql    실행할 SQL (MyBatis #{}, ${} 바인딩이 '?'로 치환된 fakeSql)
     * @return EXPLAIN 결과 텍스트 (컬럼헤더 + 구분선 + 데이터 행)
     * @throws SQLException EXPLAIN 실행 실패 시
     */
    public static String getExplainInfo(Connection connection, String fakeSql) throws SQLException {
        // PreparedStatement 대신 Statement 사용: EXPLAIN은 '?'가 없는 정적 명령
        // fakeSql의 '?'는 이미 치환된 상태이므로 파라미터 바인딩 불필요
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN " + fakeSql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            StringBuilder result = new StringBuilder();

            // 헤더 행: 컬럼명을 '|' 구분자로 나열
            for (int col = 1; col <= columnCount; col++) {
                result.append(meta.getColumnName(col));
                if (col < columnCount) {
                    result.append(" | ");
                }
            }
            result.append("\n");
            result.append("-".repeat(60)).append("\n");

            // 데이터 행: 각 행의 모든 컬럼 값을 '|' 구분자로 출력
            // null 값은 "NULL" 문자열로 표시 (DB의 NULL과 구분 불가한 빈 문자열 방지)
            while (rs.next()) {
                for (int col = 1; col <= columnCount; col++) {
                    String value = rs.getString(col);
                    result.append(value != null ? value : "NULL");
                    if (col < columnCount) {
                        result.append(" | ");
                    }
                }
                result.append("\n");
            }

            return result.toString();
        }
    }
}
