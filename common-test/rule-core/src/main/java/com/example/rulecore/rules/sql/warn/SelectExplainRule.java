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
import java.sql.ResultSet;
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

                // Explain 실행
                try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + sql);
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        // MariaDB/MySQL EXPLAIN output columns: id, select_type, table, type, possible_keys, key, key_len, ref, rows, Extra
                        String type = rs.getString("type");
                        String extra = rs.getString("Extra");
                        String table = rs.getString("table");

                        if ("ALL".equalsIgnoreCase(type)) {
                            violations.add(new RuleViolation(
                                    RULE_NAME,
                                    Status.WARN,
                                    "Full 테이블 스캔 대상 (type=ALL) '" + table + "'. Check indexing.",
                                    ms.getId()
                            ));
                        }

                        if (extra != null && extra.contains("Using filesort")) {
                            violations.add(new RuleViolation(
                                    RULE_NAME,
                                    Status.WARN,
                                    "File Sort 감지 (Using filesort) :  '" + table + "'. 인덱스 고려 필요.",
                                    ms.getId()
                            ));
                        }
                    }

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
