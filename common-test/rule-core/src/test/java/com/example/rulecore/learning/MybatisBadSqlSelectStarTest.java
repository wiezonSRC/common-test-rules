package com.example.rulecore.learning;

import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.ruleEngine.enums.Status;
import com.example.rulecore.rules.sql.MybatisUnitBasedRule;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/***
 * Select
 *  └─ PlainSelect
 *       ├─ selectItems
 *       │    └─ Expression (AllColumns / Column / Function ...)
 *       │
 *       ├─ fromItem
 *       │    └─ ParenthesedSelect
 *       │         └─ Select → PlainSelect
 *       │
 *       ├─ joins
 *       │    └─ Join → ParenthesedSelect → Select
 *       │
 *       └─ where
 *            └─ Expression → ParenthesedSelect → Select
 */

@Slf4j
class MybatisBadSqlSelectStarTest {
    private RuleContext ruleContext;
    private Path mapperPath;

    @BeforeEach
    void setup() {
        mapperPath  = Paths.get("../rule-core/src/test/java/com/example/rulecore/mapper/BadSqlMapper.xml").toAbsolutePath();
        assertTrue(mapperPath.toFile().exists(), "Mapper 파일이 존재해야 합니다.");
        ruleContext = RuleContext.builder()
                .projectRoot(Paths.get("../sample-target").toAbsolutePath())
                .workspaceRoot(Paths.get("..").toAbsolutePath())
                .affectedFiles(List.of(mapperPath))
                .build();
    }


    @Test
    @DisplayName("Select * 금지 규칙")
    void prototypeSelectForbidLogic() throws Exception {
        final String targetId = "BadSqlSelectStar";
        List<RuleViolation> tempViolation = new ArrayList<>();

        MybatisUnitBasedRule prototypeRule = new MybatisUnitBasedRule() {
            private boolean isCapture = false;
            private StringBuilder sqlBuffer = new StringBuilder();

            @Override
            protected int getLineNumber() {
                return super.getLineNumber();
            }

            @Override
            protected String getCurrentAbsolutePath() {
                return super.getCurrentAbsolutePath();
            }

            @Override
            protected String getCurrentRelativePath() {
                return super.getCurrentRelativePath();
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                log.info("[Check] Query Find Id : {}", attributes.getValue("id"));
                if (targetId.equalsIgnoreCase(attributes.getValue("id"))) {
                    isCapture = true;
                    sqlBuffer.setLength(0);
                    log.info("[Target] {} 분석 시작!", targetId);
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) throws SAXException {
                if (isCapture) sqlBuffer.append(ch, start, length);
            }

            @Override
            public void endElement(String uri, String localName, String qName) throws SAXException {
                if (isCapture && List.of("select", "update", "delete", "insert").contains(qName.toLowerCase())) {
                    String sql = sqlBuffer.toString()
                            .replaceAll("<[^>]*>", " ")              // XML 제거
                            .replaceAll("#\\{[^}]*\\}", "?")         // MyBatis 파라미터 치환
                            .replaceAll("\\$\\{[^}]*\\}", "1")       // ${} 치환
                            .replaceAll("\\s+", " ")
                            .trim();

                    log.info("[Clean SQL] : {}", sql);


                    //TODO) 실제 추가될 Rule
                    runPrototypeRule(
                            sql,
                            tempViolation,
                            getCurrentRelativePath(),
                            getCurrentAbsolutePath(),
                            getLineNumber()
                    );


                    isCapture = false;
                }
            }
        };

        prototypeRule.check(ruleContext);

        log.info("위반 개수 : {}", tempViolation.size());
        tempViolation.forEach(violation -> {
            log.info(" -> {}", violation.message());
            log.info(" -> {}", violation.getIdeLink());
        });

        Assertions.assertFalse(tempViolation.isEmpty(), "위반사항을 찾지 못했습니다.");
    }

    // TODO) 실제 프로토타입용 rule
    private void runPrototypeRule_직접구현(String sql, List<RuleViolation> violations, String absolutePath, String relativePath,  Integer lineNumber) {
        try{
            log.info("runPrototypeRule");

            Statement stmt = CCJSqlParserUtil.parse(sql);

            if (stmt instanceof Select select) {
                // 전체 Select Context
                PlainSelect ps = select.getPlainSelect();

                // FROM Select Context
                if(ps.getFromItem() != null){
                    if( ps.getFromItem() instanceof ParenthesedSelect slt){
                        PlainSelect plainSelect = slt.getPlainSelect();
                        for(var item : plainSelect.getSelectItems()){
                            log.info("FROM ITEM : {}", item);
                            Expression expr = item.getExpression();
                            if (expr == null) continue;

                            // COUNT(*) 예외 처리
                            if (expr instanceof Function function) {
                                if ("COUNT".equalsIgnoreCase(function.getName())) {
                                    continue;
                                }
                            }

                            if(expr instanceof AllColumns){
                                violations.add(new RuleViolation(
                                        "MybatisSelectStarRule",
                                        Status.FAIL,
                                        "SELECT * 사용이 감지되었습니다.",
                                        absolutePath,
                                        relativePath,
                                        lineNumber
                                ));
                            }
                        }
                    }
                }

                // JOIN SELECT Context
                if (ps.getJoins() != null) {
                    for (Join join : ps.getJoins()) {

                        if (join.getRightItem() instanceof ParenthesedSelect psSelect) {
                            Select innerSelect = psSelect.getSelect();

                            for (SelectItem item : innerSelect.getPlainSelect().getSelectItems()) {
                                log.info("JOIN SUBQUERY ITEM: {}", item);

                                Expression expr = item.getExpression();
                                if (expr == null) continue;

                                // COUNT(*) 예외 처리
                                if (expr instanceof Function function) {
                                    if ("COUNT".equalsIgnoreCase(function.getName())) {
                                        continue;
                                    }
                                }

                                if(expr instanceof AllColumns){
                                    violations.add(new RuleViolation(
                                            "MybatisSelectStarRule",
                                            Status.FAIL,
                                            "SELECT * 사용이 감지되었습니다.",
                                            absolutePath,
                                            relativePath,
                                            lineNumber
                                    ));
                                }
                            }
                        }
                    }
                }

                // select Top Context
                for (var item : ps.getSelectItems()) {
                    log.info("Item : {}", item);

                    Expression expr = item.getExpression();
                    if (expr == null) continue;

                    // COUNT(*) 예외 처리
                    if (expr instanceof Function function) {
                        if ("COUNT".equalsIgnoreCase(function.getName())) {
                            continue;
                        }
                    }

                    if(expr instanceof AllColumns){
                        violations.add(new RuleViolation(
                                "MybatisSelectStarRule",
                                Status.FAIL,
                                "SELECT * 사용이 감지되었습니다.",
                                absolutePath,
                                relativePath,
                                lineNumber
                        ));
                    }
                }
            }
        }catch(Exception e){
            log.error(e.getMessage());
        }
    }

    private void runPrototypeRule(String sql,
                                  List<RuleViolation> violations,
                                  String absolutePath,
                                  String relativePath,
                                  Integer lineNumber) {

        try {
            log.info("runPrototypeRule");

            Statement stmt = CCJSqlParserUtil.parse(sql);

            if (!(stmt instanceof Select select)) return;

            final boolean[] hasViolation = {false};

            // 🔥 재귀 함수 (람다로 내부 처리)
            java.util.function.Consumer<Select> traverse = new java.util.function.Consumer<>() {
                @Override
                public void accept(Select sel) {

                    if (hasViolation[0]) return;

                    PlainSelect ps = sel.getPlainSelect();
                    if (ps == null) return;

                    // =========================
                    // 1. SELECT 절 검사
                    // =========================
                    for (SelectItem item : ps.getSelectItems()) {
                        log.info("SELECT 절 : {}" ,item);
                        Expression expr = item.getExpression();
                        if (expr == null) continue;

                        // COUNT(*) 허용
                        if (expr instanceof Function function) {
                            if ("COUNT".equalsIgnoreCase(function.getName())) {
                                continue;
                            }
                        }

                        if (expr instanceof AllColumns) {
                            violations.add(new RuleViolation(
                                    "MybatisSelectStarRule",
                                    Status.FAIL,
                                    "SELECT * 사용이 감지되었습니다.",
                                    absolutePath,
                                    relativePath,
                                    lineNumber
                            ));
                            hasViolation[0] = true;
                            return;
                        }

                        if (expr instanceof AllTableColumns atc) {
                            violations.add(new RuleViolation(
                                    "MybatisSelectStarRule",
                                    Status.FAIL,
                                    "SELECT " + atc.getTable() + ".* 사용이 감지되었습니다.",
                                    absolutePath,
                                    relativePath,
                                    lineNumber
                            ));
                            hasViolation[0] = true;
                            return;
                        }
                    }

                    // =========================
                    // 2. FROM 재귀
                    // =========================
                    if (ps.getFromItem() instanceof ParenthesedSelect psSelect) {
                        accept(psSelect.getSelect());
                    }

                    // =========================
                    // 3. JOIN 재귀
                    // =========================
                    if (ps.getJoins() != null) {
                        for (Join join : ps.getJoins()) {
                            if (join.getRightItem() instanceof ParenthesedSelect psSelect) {
                                accept(psSelect.getSelect());
                            }
                        }
                    }

                    // =========================
                    // 4. WHERE 재귀
                    // =========================
                    if (ps.getWhere() != null) {
                        ps.getWhere().accept(new ExpressionVisitorAdapter() {
                            @Override
                            public void visit(ParenthesedSelect psSelect) {
                                accept(psSelect.getSelect());
                            }
                        });
                    }
                }
            };

            // =========================
            // 실행
            // =========================
            traverse.accept(select);

            // =========================
            // UNION / UNION ALL 처리
            // =========================
            if (!hasViolation[0] && select.getSetOperationList() != null) {
                for (Select s : select.getSetOperationList().getSelects()) {
                    traverse.accept(s);
                }
            }

        } catch (Exception e) {
            log.error("SQL parse error: {}", e.getMessage());
        }
    }

}
