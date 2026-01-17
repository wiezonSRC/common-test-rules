package com.example.rulecore.rules;

import com.example.rulecore.Rule;
import com.example.rulecore.RuleViolation;

import java.util.ArrayList;
import java.util.List;

public class NoDollarExpressionRule implements Rule {
    @Override
    public List<RuleViolation> check() {
        List<RuleViolation> violations = new ArrayList<>();

        // 예시 (실제로는 mapper scan)
        boolean found = true;

        if (found) {
            violations.add(
                    new RuleViolation(
                            "NoDollarExpressionRule",
                            "Found ${} usage in mapper XML (SQL Injection risk)"
                    )
            );
        }

        return violations;
    }
}
