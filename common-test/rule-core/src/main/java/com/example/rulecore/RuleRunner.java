package com.example.rulecore;

import com.example.rulecore.rules.NoDollarExpressionRule;

import java.util.ArrayList;
import java.util.List;

public class RuleRunner {

    public static List<RuleViolation> runAll() {
        List<RuleViolation> all = new ArrayList<>();

        // 나중에 SPI / scan으로 확장 가능
        List<Rule> rules = List.of(
                new NoDollarExpressionRule()
        );

        for (Rule rule : rules) {
            all.addAll(rule.check());
        }

        return all;
    }

    public static void runOrFail() {
        List<RuleViolation> violations = runAll();

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
