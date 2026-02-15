package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.enums.Status;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.rules.sql.MybatisUnitBasedRule;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.xml.sax.Attributes;

import java.util.List;
import java.util.Set;

public class SqlBasicPerformanceRule extends MybatisUnitBasedRule {

    private final StringBuilder sqlBuffer = new StringBuilder();
    private boolean isSqlTag = false;
    private static final Set<String> SQL_TAGS = Set.of("select", "insert", "update", "delete");

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        if (SQL_TAGS.contains(qName.toLowerCase())) {
            isSqlTag = true;
            sqlBuffer.setLength(0);
        }
    }

    @Override
    public void characters(char[] ch, int start, int length){
        if (isSqlTag) {
            sqlBuffer.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        if (SQL_TAGS.contains(qName.toLowerCase())) {
            String cleanSql = sqlBuffer.toString().replaceAll("<[^>]*>", " ").trim();
            if (!cleanSql.isEmpty() && qName.equalsIgnoreCase("select")) {
                analyzeSql(cleanSql);
            }
            isSqlTag = false;
        }
    }

    private void analyzeSql(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select select) {
                if (select.getPlainSelect() != null) {
                    inspectPlainSelect(select.getPlainSelect());
                }
            }
        } catch (Exception ignored) {
            if (sql.toUpperCase().contains("SELECT *")) {
                reportViolation("SELECT * 사용 금지 (Heuristic 감지)");
            }
        }
    }

    private void inspectPlainSelect(PlainSelect plainSelect) {


        if (plainSelect.getSelectItems() == null) return;
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();

        for (SelectItem<?> selectItem : selectItems) {
            if (selectItem != null) {

                if (selectItem.toString().trim().equals("*")) {
                    reportViolation("SELECT * 사용 금지.");
                }

                if (selectItem.toString().trim().endsWith(".*")) {
                    reportViolation("SELECT t.* 사용 금지.");
                }


                // 스칼라 서브쿼리 감지 (4.8 방식)
                if (selectItem.getExpression() != null) {

                    selectItem.getExpression().accept(new ExpressionVisitorAdapter() {
                        @Override
                        public void visit(Select selectBody) {
                            reportViolation("SELECT 절 내 스칼라 서브쿼리 사용 금지.");
                        }

                    });
                }
            }

        }
    }

    private void reportViolation(String message) {
        currentViolations.add(new RuleViolation(
                "SqlBasicPerformanceRule",
                Status.FAIL,
                message,
                currentXmlPath.toString(),
                getLineNumber()
        ));
    }
}