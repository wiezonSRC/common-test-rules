package com.example.rulecore.ruleEngine;

import com.example.rulecore.ruleEngine.enums.Status;

import java.util.List;

public record RuleResult(List<RuleViolation> violations) {
    public boolean hasError() {
        return violations.stream()
                .anyMatch(
                        violation -> violation.status() == Status.FAIL
                );
    }
}