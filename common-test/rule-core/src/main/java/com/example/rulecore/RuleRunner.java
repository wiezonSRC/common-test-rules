package com.example.rulecore;

import com.example.rulecore.rules.BoundSqlGenerationRule;
import com.example.rulecore.rules.NoDollarExpressionRule;
import com.example.rulecore.rules.SelectExplainRule;
import com.example.rulecore.rules.TransactionalSwallowExceptionRule;

import java.util.ArrayList;
import java.util.List;

public class RuleRunner {

    public static List<RuleViolation> runAll(RuleContext context) {
        List<RuleViolation> all = new ArrayList<>();

        // 나중에 SPI / scan으로 확장 가능
        List<Rule> rules = new ArrayList<>();

        rules.add(new NoDollarExpressionRule());
        rules.add(new TransactionalSwallowExceptionRule());
        if(context.getDataSource() != null) rules.add(new SelectExplainRule());


        for (Rule rule : rules) {
            try{
                all.addAll(rule.check(context));
            }catch(Exception e){
                new RuleViolation(
                        rule.getClass().getSimpleName(),
                        e.getMessage(),
                        ""
                );
            }
        }

        return all;
    }

    public static void runOrFail(RuleContext context) {
        List<RuleViolation> violations = runAll(context);

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n=== RULE CHECK FAILED ===\n");

            for (RuleViolation v : violations) {
                sb.append(v.format()).append("\n");
            }

            throw new AssertionError(sb.toString());
        }
    }
}
