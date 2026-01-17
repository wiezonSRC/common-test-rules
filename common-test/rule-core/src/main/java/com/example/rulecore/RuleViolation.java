package com.example.rulecore;

public class RuleViolation {

    private final String ruleName;
    private final String message;

    public RuleViolation(String ruleName, String message) {
        this.ruleName = ruleName;
        this.message = message;
    }

    public String format() {
        return """
        [RULE] %s
        %s
        """.formatted(ruleName, message);
    }
}
