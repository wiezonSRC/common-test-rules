package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.SqlParamFactory;
import com.example.rulecore.util.Status;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BoundSqlGenerationRule implements Rule {

    private static final String RULE_NAME = "BoundSqlGenerationRule";

    @Override
    public List<RuleViolation> check(RuleContext context) {

        List<RuleViolation> violations = new ArrayList<>();

        if (context.getSqlSessionFactory() == null) {
            // MyBatis 없는 프로젝트 >> skip
            return violations;
        }

        Configuration cfg = context.getSqlSessionFactory().getConfiguration();

        Map<String, Object> param = SqlParamFactory.createDefault();

        for (Object obj : cfg.getMappedStatements()) {

            if (!(obj instanceof MappedStatement ms)) {
                continue;
            }

            SqlCommandType type = ms.getSqlCommandType();

            if (!(type == SqlCommandType.SELECT
                    || type == SqlCommandType.UPDATE
                    || type == SqlCommandType.INSERT
                    || type == SqlCommandType.DELETE)) {
                continue;
            }

            try {
                BoundSql boundSql = ms.getBoundSql(param);

                if (boundSql == null || boundSql.getSql() == null) {
                    violations.add(new RuleViolation(
                            RULE_NAME,
                            Status.FAIL,
                            "BoundSql is null",
                            ms.getId()
                    ));
                }


            } catch (Exception e) {
                violations.add(new RuleViolation(
                        RULE_NAME,
                        Status.FAIL,
                        "BoundSql generation failed: " + e.getMessage(),
                        ms.getId()
                ));
            }
        }

        return violations;
    }
}
