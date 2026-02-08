package com.example.rulecore.rules.style.warn;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.SqlParamFactory;
import com.example.rulecore.util.Status;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
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

public class SqlAdvancedStyleRule implements Rule {

    private static final String RULE_NAME = "SqlAdvancedStyleRule";

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

                if (stmt instanceof Select) {
                    Select select = (Select) stmt;
                    if (select.getSelectBody() instanceof PlainSelect) {
                        inspectSelect(ms.getId(), (PlainSelect) select.getSelectBody(), violations, 1);
                    }
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        return violations;
    }

    private void inspectSelect(String queryId, PlainSelect plainSelect, List<RuleViolation> violations, int depth) {
        if (depth > 2) {
            violations.add(new RuleViolation(
                    RULE_NAME,
                    Status.WARN,
                    "Nested subquery depth " + depth + " exceeds limit (2). Simplify query.",
                    queryId
            ));
        }

        // 1. FROM 절 검사
        FromItem fromItem = plainSelect.getFromItem();
        checkFromItem(queryId, fromItem, violations, depth);

        // 2. JOIN 절 검사
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                checkFromItem(queryId, join.getRightItem(), violations, depth);
            }
        }
    }

    private void checkFromItem(String queryId, FromItem item, List<RuleViolation> violations, int depth) {
        if (item instanceof Table) {
            Table table = (Table) item;
            if (table.getAlias() != null) {
                String alias = table.getAlias().getName();
                String tableName = table.getName();
                
                // 간단한 별칭 규칙: 테이블명의 단어 첫 글자 조합 권장 (예: TB_USER_LOG -> tul)
                // 너무 엄격하지 않게, 별칭이 테이블명과 전혀 관련 없어 보이면 경고
                if (!isAbbreviation(tableName, alias)) {
                    violations.add(new RuleViolation(
                            RULE_NAME,
                            Status.WARN,
                            "Table alias '" + alias + "' for '" + tableName + "' does not follow convention (e.g. TB_USER_LOG -> tul).",
                            queryId
                    ));
                }
            }
        } else if (item instanceof ParenthesedSelect) {
            ParenthesedSelect subSelect = (ParenthesedSelect) item;
            
            // 서브쿼리 별칭 규칙: _agg, _grp 접미사 권장
            if (subSelect.getAlias() != null) {
                String alias = subSelect.getAlias().getName();
                if (!alias.endsWith("_agg") && !alias.endsWith("_grp")) {
                    violations.add(new RuleViolation(
                            RULE_NAME,
                            Status.WARN,
                            "Subquery alias '" + alias + "' should end with '_agg' or '_grp'.",
                            queryId
                    ));
                }
            }

            // 재귀 검사 (Depth 증가)
            if (subSelect.getSelectBody() instanceof PlainSelect) {
                inspectSelect(queryId, (PlainSelect) subSelect.getSelectBody(), violations, depth + 1);
            }
        }
    }

    private boolean isAbbreviation(String tableName, String alias) {
        // TB_, TBL_ 제거
        String cleanName = tableName.toUpperCase().replaceAll("^(TB_|TBL_)", "");
        String cleanAlias = alias.toUpperCase();

        // 1. 별칭이 테이블명 전체를 포함하면 OK
        if (cleanName.equals(cleanAlias)) return true;

        // 2. 이니셜 체크 (USER_LOG -> UL)
        StringBuilder initials = new StringBuilder();
        for (String part : cleanName.split("_")) {
            if (!part.isEmpty()) initials.append(part.charAt(0));
        }
        
        if (cleanAlias.equals(initials.toString())) return true;
        
        // 3. 또는 별칭의 모든 문자가 테이블명에 순서대로 등장하면 OK (유연한 규칙)
        // e.g. USER_LOG -> ULOG (OK)
        int tIdx = 0;
        int aIdx = 0;
        while (tIdx < cleanName.length() && aIdx < cleanAlias.length()) {
            if (cleanName.charAt(tIdx) == cleanAlias.charAt(aIdx)) {
                aIdx++;
            }
            tIdx++;
        }
        return aIdx == cleanAlias.length();
    }
}
