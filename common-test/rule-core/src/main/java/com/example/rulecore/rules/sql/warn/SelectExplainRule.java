package com.example.rulecore.rules.sql.warn;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.SqlParamFactory;
import com.example.rulecore.util.Status;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SelectExplainRule implements Rule {

    private static final String RULE_NAME = "SelectExplainRule";

    @Override
    public List<RuleViolation> check(RuleContext context) {

        List<RuleViolation> violations = new ArrayList<>();

        DataSource dataSource = context.dataSource();
        Configuration cfg = context.sqlSessionFactory().getConfiguration();

        if (dataSource == null) {
            violations.add(new RuleViolation(
                    RULE_NAME,
                    Status.FAIL,
                    "DataSource not available (EXPLAIN skipped)",
                    ""
            ));
            return violations;
        }

        try (Connection conn = dataSource.getConnection()) {

            Set<String> processedIds = new HashSet<>();

            for (MappedStatement ms : cfg.getMappedStatements()) {

                if (processedIds.contains(ms.getId())) {
                    continue;
                }
                processedIds.add(ms.getId());

                if (ms.getSqlCommandType() != SqlCommandType.SELECT) {
                    continue;
                }

                BoundSql bs = ms.getBoundSql(SqlParamFactory.createDefault());
                String sql = SqlParamFactory.explainableSql(bs);

                //FIXME) Explain에서  All fullscan의 경우 warning 띄우기
                try (PreparedStatement ps =
                             conn.prepareStatement("EXPLAIN " + sql)) {

                    ps.executeQuery();

                } catch (SQLException e) {
                    violations.add(new RuleViolation(
                            RULE_NAME,
                            Status.WARN,
                            "EXPLAIN failed: " + e.getMessage(),
                            ms.getId()
                    ));
                }
            }

        } catch (SQLException e) {
            violations.add(new RuleViolation(
                    RULE_NAME,
                    Status.WARN,
                    "DB connection failed: " + e.getMessage(),
                    ""
            ));
        }

        return violations;
    }
}
