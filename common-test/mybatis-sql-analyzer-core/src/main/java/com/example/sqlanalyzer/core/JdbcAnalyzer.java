package com.example.sqlanalyzer.core;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.sql.*;
import java.text.MessageFormat;
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

    public static String getExplainInfo(Connection connection, String fakeSql) throws SQLException {

        ResultSet rs = null;

        try(Statement stmt = connection.createStatement()){
            String sql = MessageFormat.format("EXPLAIN {0}", fakeSql);
            rs = stmt.executeQuery(sql);
            StringBuilder result = new StringBuilder();
            for(int i = 1; rs.next(); i++){
                result.append(rs.getString(i));
            }

            return result.toString();
        }finally{
            if(rs != null) rs.close();
        }
    }
}
