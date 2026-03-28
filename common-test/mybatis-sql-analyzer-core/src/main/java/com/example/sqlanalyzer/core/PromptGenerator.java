package com.example.sqlanalyzer.core;


public class PromptGenerator {

    public static String generate(
            String queryId,
            String rawXml,
            String simplifiedSql,
            JdbcAnalyzer.AnalysisResult analysis) {

        StringBuilder sb = new StringBuilder();
        sb.append("# SQL AI Performance Analysis Prompt ");
        sb.append("Please analyze the following MyBatis SQL query for performance optimization. ");

        sb.append("## 1. Query Information ");
        sb.append("- **Query ID**: `").append(queryId).append("` ");

        sb.append("### Original MyBatis XML ");
        sb.append("```xml ").append(rawXml).append("```");

        sb.append("### Flattened SQL (for EXPLAIN) ");
        sb.append("```sql ").append(simplifiedSql).append("```");

        sb.append("## 2. Database Context ");
        for (JdbcAnalyzer.TableInfo table : analysis.getTables()) {
            sb.append("### Table: `").append(table.getTableName()).append("` ");
            sb.append("#### Create Table Statement ");
            sb.append("```sql ").append(table.getCreateTableSql()).append("``` ");
            sb.append("#### Index Information ");
            sb.append("```text ").append(table.getIndexInfo()).append("``` ");
        }

        sb.append("## 3. Execution Plan (EXPLAIN) ");
        sb.append("```text ").append(analysis.getExplainResult()).append("``` ");

        sb.append("## 4. Requirements ");
        sb.append("1. Identify potential performance bottlenecks (e.g., Full Table Scan, Lack of Indexes). ");
        sb.append("2. Suggest index improvements if necessary. ");
        sb.append("3. Review the MyBatis dynamic SQL tags for any inefficiencies. ");
        sb.append("4. Provide an optimized version of the query or index strategy. ");

        return sb.toString();
    }
}
