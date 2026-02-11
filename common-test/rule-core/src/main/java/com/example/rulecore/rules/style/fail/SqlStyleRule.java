package com.example.rulecore.rules.style.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.SqlParamFactory;
import com.example.rulecore.util.Status;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SqlStyleRule implements Rule {

    private static final String RULE_NAME = "SqlStyleRule";

    @Override
    public List<RuleViolation> check(RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        if (context.sqlSessionFactory() == null) {
            return violations;
        }

        Configuration cfg = context.sqlSessionFactory().getConfiguration();
        Set<String> processedIds = new HashSet<>();

        for (MappedStatement ms : cfg.getMappedStatements()) {
            // 중복 처리 방지 (MyBatis는 같은 id로 여러 번 등록될 수 있음)
            if (processedIds.contains(ms.getId())) {
                continue;
            }
            processedIds.add(ms.getId());

            // SELECT 문만 검사
            if (ms.getSqlCommandType() != SqlCommandType.SELECT) {
                continue;
            }

            try {
                // 더미 파라미터로 SQL 생성
                BoundSql bs = ms.getBoundSql(SqlParamFactory.createDefault());
                String sql = bs.getSql();

                // JSQLParser로 파싱
                Statement stmt = CCJSqlParserUtil.parse(sql);

                if (stmt instanceof Select) {
                    Select select = (Select) stmt;
                    inspectSelect(ms.getId(), select, violations);
                }

            } catch (JSQLParserException e) {
                // 파싱 실패는 무시하거나 경고 (동적 SQL이 너무 복잡해서 깨진 경우 등)
                // violations.add(new RuleViolation(RULE_NAME, "SQL Parsing failed: " + e.getMessage(), ms.getId()));
            } catch (Exception e) {
                // 바인딩 실패 등
            }
        }

        return violations;
    }

    private void inspectSelect(String queryId, Select select, List<RuleViolation> violations) {
        
        // 0. WITH 절 검사 (snake_case)
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                String alias = withItem.getAlias().getName(); // WITH CTE_NAME AS ...
                if (!alias.matches("^[a-z][a-z0-9_]*$")) { // snake_case (소문자 기준)
                     // 보통 WITH절은 대문자나 PascalCase를 쓰기도 하는데, 요구사항이 snake_case라면 소문자_언더스코어 확인
                     // 만약 DB 테이블처럼 대문자 SNAKE_CASE를 의미한다면 정규식 수정 필요.
                     // "WITH 절 snake_case" -> 보통 table naming을 따르므로, 소문자 snake_case로 가정하거나
                     // 사용자 의도가 "구분자를 언더스코어로 쓰라"는 의미일 수 있음. 
                     // 여기서는 소문자 snake_case (^[a-z][a-z0-9_]*$)를 기준으로 하되, 대문자 허용 여부 확인 필요.
                     // 일반적인 Java/SQL 컨벤션 툴에서는 snake_case를 소문자로 정의함.
                     // 하지만 SQL에서는 대소문자 구분 없으므로 대문자 SNAKE_CASE도 허용하는게 안전할 수 있음.
                     // 여기서는 "snake_case"라는 용어 자체에 충실하여 소문자로 검사하되, 실패 메시지에 명시.
                     // (추가: 테이블/컬럼 소문자 포맷터 규칙이 있으므로 소문자 권장일듯)
                    
                    if (!alias.matches("^[a-z0-9_]+$")) { // 단순 소문자+숫자+언더스코어
                         violations.add(new RuleViolation(
                            RULE_NAME,
                            Status.FAIL,
                            "WITH 절 : '" + alias + "' snake_case 사용 권고 (소문자).",
                            queryId,
                                 0
                        ));
                    }
                }
            }
        }
    
        // PlainSelect (일반적인 SELECT)만 검사. UNION 등 복잡한 건 일단 패스
        if (!(select.getSelectBody() instanceof PlainSelect)) {
            return;
        }

        PlainSelect plainSelect = (PlainSelect) select.getSelectBody();

        // 1. SELECT * 금지
        for (SelectItem item : plainSelect.getSelectItems()) {
            if (item.getExpression() instanceof AllColumns) {
                violations.add(new RuleViolation(
                        RULE_NAME,
                        Status.FAIL,
                        "'SELECT *'. 사용 불가.",
                        queryId,
                        0
                ));
            }
        }

        // 2. 암시적 JOIN 금지 (콤마 조인)
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                // JSQLParser에서 isSimple()이 true면 "FROM A, B" 형태의 콤마 조인임
                if (join.isSimple()) {
                     violations.add(new RuleViolation(
                        RULE_NAME,
                        Status.FAIL,
                        "암시적 join 금지 (comma join). INNER/OUTER JOIN 사용.",
                        queryId,
                             0
                ));
                }
            }
        }
    }
}
