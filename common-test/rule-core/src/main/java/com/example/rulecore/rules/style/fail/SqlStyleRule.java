package com.example.rulecore.rules.style.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.SqlParamFactory;
import com.example.rulecore.util.Status;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
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

        if (context.getSqlSessionFactory() == null) {
            return violations;
        }

        Configuration cfg = context.getSqlSessionFactory().getConfiguration();
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
                        "Avoid using 'SELECT *'. Specify columns explicitly.",
                        queryId
                ));
            }
        }

        // 2. 암시적 JOIN 금지 (콤마 조인)
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                // 암시적 조인은 toString() 결과에 'JOIN' 키워드가 포함되지 않음 (예: ", TB_USER")
                // 반면 명시적 조인은 "INNER JOIN ...", "LEFT JOIN ..." 등으로 표시됨
                String joinStr = join.toString().toUpperCase();
                
                if (!joinStr.contains("JOIN")) {
                     violations.add(new RuleViolation(
                        RULE_NAME,
                        Status.FAIL,
                        "Avoid implicit joins (comma join). Use explicit INNER/OUTER JOIN with ON clause.",
                        queryId
                ));
                }
            }
        }
    }
}
