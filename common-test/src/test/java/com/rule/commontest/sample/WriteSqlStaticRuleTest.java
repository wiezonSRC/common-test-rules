package com.rule.commontest.sample;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;


/**
 [Write SQL Static Rule Test]

 * [검증 목적]
 * - 전체 테이블 UPDATE / DELETE 방지
 * - WHERE 없는 파괴적 SQL 차단
 * - WHERE 1=1 only 상태 경고
 * - INSERT 컬럼/값 구조 오류 사전 감지
 * - ${} 기반 SQL 치환 금지
 * - OGNL 조건식 null-safe 검증
 * - <foreach collection> null-safe 검증


 * [검증 방식]
 * - 기본 파라미터(Map 비어 있음)로 BoundSql 생성
 * - 테스트 단계에서 null 상황을 강제로 노출


 * [비검증 범위]
 * - 비즈니스 로직 정합성
 * - 모든 dynamic SQL 분기 커버리지
 * - 실행 결과 검증
 */




public class WriteSqlStaticRuleTest extends SqlIntegrationTestBase{
    @Autowired
    SqlSessionFactory factory;

    private final static String warnWhereRegex = "(?s).*WHERE\\s+1\\s*=\\s*1\\s*$";
    private final static String whereRegex = "(?s).*\\bWHERE\\b.*";

    @Test
    @DisplayName("${} 사용 불가 - Injection 위험")
    void mapperXml_shouldNotContainDollarExpression() throws Exception {

        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();

        Resource[] resources =
                resolver.getResources("classpath*:mapper/**/*.xml");


        for (Resource resource : resources) {
            List<String> lines = Files.readAllLines(resource.getFile().toPath(),StandardCharsets.UTF_8);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if(line.contains("--")){
                    System.out.printf("[WARN] 주석처리 ${} 확인 필요 >> \n File : %s \n Line Number : %d \n Content : %s ", resource.getURI(), i+1,line.trim());
                } else if(line.contains("${")){
                    Assertions.fail("${} 발견 >>  \n File : %s \n Line Number : %d \n Content : %s ".formatted(resource.getURI(), i+1, line.trim()));
                }
            }
        }
    }

    @Test
    @DisplayName("INSERT / UPDATE / DELETE 정적 규칙 테스트")
    void writeSql_shouldFollowRules() {
        for (Object obj : factory.getConfiguration().getMappedStatements()) {

            if (!(obj instanceof MappedStatement ms)) {
                continue;
            }

            SqlCommandType type = ms.getSqlCommandType();
            if (type == SqlCommandType.SELECT) continue;

            BoundSql bs = ms.getBoundSql(SqlParamFactory.createDefault());
            String sql = SqlParamFactory.explainableSql(bs).toUpperCase();


            // UPDATE without WHERE
            if (type == SqlCommandType.UPDATE) {
                if(!hasWhereClause(sql)){
                    Assertions.fail("UPDATE without WHERE: " + ms.getId());
                }else if(hasWhereClause(sql) && sql.matches(warnWhereRegex)){
                    System.out.println("WARN: WHERE 1=1 only - " + ms.getId());
                }
            }

            // DELETE without WHERE
            if (type == SqlCommandType.DELETE) {
                if(!hasWhereClause(sql)){
                    Assertions.fail("DELETE without WHERE: " + ms.getId());
                }else if(hasWhereClause(sql) && sql.matches(warnWhereRegex)){
                    System.out.println("WARN: WHERE 1=1 only - " + ms.getId());
                }
            }

            // INSERT column/value mismatch
            if (type == SqlCommandType.INSERT) {
                int colCnt = countColumns(sql);
                int valCnt = countPlaceholders(sql);
                if (colCnt > 0 && colCnt != valCnt) {
                    System.out.println("WARN: INSERT column/value mismatch [Dynamic 확인 필요]>> " + ms.getId());
                }
            }

            // ${} 사용
            if (sql.contains("${")) {
                Assertions.fail("WARN: ${} 사용 - " + ms.getId());
            }
        }
    }

    private int countPlaceholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }

    private int countColumns(String sql) {
        if (!sql.contains("(") || !sql.contains(")")) return -1;
        String between = sql.substring(sql.indexOf("(") + 1, sql.indexOf(")"));
        return between.split(",").length;
    }

    private boolean hasWhereClause(String sql){
        return sql.matches(whereRegex);
    }
}
