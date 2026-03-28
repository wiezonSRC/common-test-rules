package com.example.sqlanalyzer.core;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class JdbcAnalyzer {

    @Getter
    @Builder
    public static class AnalysisResult {
        private String explainResult;
        private List<TableInfo> tables;
    }

    @Getter
    @Builder
    public static class TableInfo {
        private String tableName;
        private String createTableSql;
        private String indexInfo;
    }

    public static AnalysisResult analyze(String sql, String url, String user, String password) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            // 1. EXPLAIN 실행
            String explain = runExplain(conn, sql);

            // 2. 테이블 정보 수집
            Set<String> tableNames = extractTableNames(sql);
            List<TableInfo> tables = new ArrayList<>();
            for (String tableName : tableNames) {
                tables.add(getTableInfo(conn, tableName));
            }

            return AnalysisResult.builder()
                    .explainResult(explain)
                    .tables(tables)
                    .build();
        }
    }

    private static String runExplain(Connection conn, String sql) {
        StringBuilder sb = new StringBuilder();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            // Header
            for (int i = 1; i <= cols; i++) {
                sb.append(meta.getColumnName(i)).append(" ");
            }
            sb.append(" ");

            // Rows
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    sb.append(rs.getString(i)).append("	");
                }
                sb.append(" ");
            }
        } catch (SQLException e) {
            sb.append("EXPLAIN failed: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private static Set<String> extractTableNames(String sql) {
        try {
            net.sf.jsqlparser.statement.Statement statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
            return new HashSet<>(tablesNamesFinder.getTableList(statement));
        } catch (Exception e) {
            log.error("Failed to extract table names: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    private static TableInfo getTableInfo(Connection conn, String tableName) {
        String createSql = "";
        String indexInfo = "";

        // MySQL/MariaDB 기준
        try (java.sql.Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + tableName)) {
                if (rs.next()) createSql = rs.getString(2);
            }
            
            try (ResultSet rs = stmt.executeQuery("SHOW INDEX FROM " + tableName)) {
                StringBuilder sb = new StringBuilder();
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        sb.append(rs.getString(i)).append("	");
                    }
                    sb.append(" ");
                }
                indexInfo = sb.toString();
            }
        } catch (SQLException e) {
            createSql = "Failed to get table info: " + e.getMessage();
        }

        return TableInfo.builder()
                .tableName(tableName)
                .createTableSql(createSql)
                .indexInfo(indexInfo)
                .build();
    }
}
