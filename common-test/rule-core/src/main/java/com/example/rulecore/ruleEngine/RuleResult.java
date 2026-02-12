package com.example.rulecore.ruleEngine;

import com.example.rulecore.util.Status;
import java.util.List;

public record RuleResult(List<RuleViolation> violations) {
    public boolean hasError() {
        return violations.stream().anyMatch(v -> v.status() == Status.FAIL);
    }

    public List<RuleViolation> getViolations() {
        return violations;
    }
}
