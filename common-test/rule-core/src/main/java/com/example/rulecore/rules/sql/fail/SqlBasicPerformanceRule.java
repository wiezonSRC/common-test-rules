package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.SqlParamFactory;
import com.example.rulecore.util.Status;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SqlBasicPerformanceRule implements Rule {

    private static final String RULE_NAME = "SqlBasicPerformanceRule";

    @Override
    public List<RuleViolation> check(RuleContext context) throws Exception {
        List<RuleViolation> violations = new ArrayList<>();

        if (context.sqlSessionFactory() == null) return violations;

        Configuration cfg = context.sqlSessionFactory().getConfiguration();
        Set<String> processedIds = new HashSet<>();

        for (MappedStatement ms : cfg.getMappedStatements()) {
            if (processedIds.contains(ms.getId())) continue;
            processedIds.add(ms.getId());

            if (ms.getSqlCommandType() != SqlCommandType.SELECT) continue;

            try {
                BoundSql bs = ms.getBoundSql(SqlParamFactory.createDefault());
                String sql = bs.getSql();
                Statement stmt = CCJSqlParserUtil.parse(sql);

                //FIXME) Where 절 안에 서브쿼리 방지 추가 구현 필요
                if (stmt instanceof Select) {
                    Select select = (Select) stmt;
                    if (select.getSelectBody() instanceof PlainSelect) {
                        inspectSelect(ms.getId(), (PlainSelect) select.getSelectBody(), violations);
                    }
                }

            } catch (Exception e) {
                // Ignore parsing errors
            }
        }

        return violations;
    }

    private void inspectSelect(String queryId, PlainSelect plainSelect, List<RuleViolation> violations) {
        for (SelectItem item : plainSelect.getSelectItems()) {
            if (item.getExpression() instanceof ParenthesedSelect) {
                violations.add(new RuleViolation(
                        RULE_NAME,
                        Status.FAIL,
                        "Scalar subquery 존재. JOIN 사용 권고.",
                        queryId
                ));
            }
        }
    }
}
