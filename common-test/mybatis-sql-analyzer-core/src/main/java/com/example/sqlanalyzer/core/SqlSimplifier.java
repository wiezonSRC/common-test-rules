package com.example.sqlanalyzer.core;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SqlSimplifier {

    public static String simplify(String rawXmlSql) {
        // 1. XML 태그 제거 (단순 텍스트만 추출하거나 태그 내용을 공백으로)
        String sql = rawXmlSql.replaceAll("<[^>]*>", " ");
        
        // 2. MyBatis 파라미터 #{...} 치환 (EXPLAIN용 더미 값 '0' 또는 NULL)
        // SQL 문맥에 따라 다를 수 있지만 일단 '?'로 바꾸고 나중에 실제 더미 값을 주입할 수도 있음
        sql = sql.replaceAll("#\\{[^}]*\\}", "?");

        // 3. ${...} 파라미터 치환 (보통 상수로 쓰이므로 임의의 값 '1'로 치환)
        sql = sql.replaceAll("\\$\\{[^}]*\\}", "1");

        // 4. 불필요한 공백 정리
        sql = sql.replaceAll("\s+", " ").trim();

        // 5. 만약 SELECT 절이 없는 쿼리라면(예: update/delete) EXPLAIN이 가능하도록 정리 필요
        //FIXME) explain은 필요없으나, 쿼리가 제대로 작성되었는지 주의해야할점은 없는지에 대한 피드백은 필요

        return sql;
    }
    
    /**
     * JDBC에 직접 던지기 전 좀 더 정교한 정리가 필요할 때 사용
     */
    public static String finalizeSql(String simplifiedSql) {
        // EXPLAIN을 위해 '?' 문자를 실제 유효한 값으로 치환 (문자열인 경우 '')
        return simplifiedSql.replace("'?'", "''");
    }
}
